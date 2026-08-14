// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Xiaoling Yi <xiaoling.yi@kuleuven.be>
// Modified by: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._

import chiseltest._
import chiseltest.simulator.VcsBackendAnnotation
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FpAddFpTest extends AnyFlatSpec with Matchers with ChiselScalatestTester with FpUtils {
  behavior of "FpAddFp"

  val test_num        = 1000
  val cornerRandomNum = 20000

  val cornerCombos = Seq(
    (BF16, BF16, BF16),
    (FP16, FP16, FP16),
    (FP32, FP32, FP32),
    (FP16, FP32, FP32),
    (BF16, BF16, FP32),
    (FP16, FP16, FP32),
    (FP16, FP32, FP16), // narrowing
    (FP32, FP16, BF16), // narrowing
    (FP32, FP32, FP16)  // narrowing
  )

  def fmtName(t: FpType): String = t.getClass.getSimpleName.stripSuffix("$")

  def testSingle(dut: FpAddFp, test_id: Int, a: Float, b: Float) = {

    val expected      = addHardware(a, b, dut.typeA, dut.typeB, dut.typeC)
    val expected_uint = floatToUInt(dut.typeC, expected)

    // Quantize the inputs
    val a_uint = floatToUInt(dut.typeA, a)
    val b_uint = floatToUInt(dut.typeB, b)

    dut.io.in_a.poke(a_uint.U)
    dut.io.in_b.poke(b_uint.U)
    dut.clock.step(1)
    val result = dut.io.out.peek()

    // Debugging
    val a_fp        = quantize(dut.typeA, a)
    val b_fp        = quantize(dut.typeB, b)
    val result_fp   = uintToFloat(dut.typeC, result)
    val expected_fp = quantize(dut.typeC, expected)

    withClue(
      s"❌[Test $test_id] $a_fp + $b_fp = $expected_fp (expected) != $result_fp (got)\n" +
        s"(expected) ${uintToStr(expected_uint, dut.typeC)} (got) ${uintToStr(result.litValue, dut.typeC)}"
    ) { (expected, dut.typeC) === result shouldBe true }

  }

  def test_all_fp_add_fp(dut: FpAddFp) = {
    val testCases = Seq.fill(test_num)((genRandomValue(dut.typeA), genRandomValue(dut.typeB)))
    testCases.zipWithIndex.foreach { case ((a, b), index) => testSingle(dut, index + 1, a, b) }
  }

  def testSpecialCases(dut: FpAddFp) = {
    val specialCases = Seq(
      (0.0f, 0.0f),                                     // Zero cases
      (0.0f, 1.0f),
      (1.0f, 0.0f),
      (Float.NaN, 1.0f),                                // NaN cases
      (1.0f, Float.NaN),
      (Float.NaN, Float.NaN),
      (Float.PositiveInfinity, 1.0f),                   // Infinity cases
      (1.0f, Float.PositiveInfinity),
      (Float.NegativeInfinity, 1.0f),
      (1.0f, Float.NegativeInfinity),
      (Float.PositiveInfinity, Float.NegativeInfinity), // +inf + -inf = NaN
      (Float.NegativeInfinity, Float.PositiveInfinity), // -inf + +inf = NaN
      (Float.MinPositiveValue, Float.MinPositiveValue), // Smallest positive
      (Float.MinPositiveValue, 0.0f),
      (0.0f, Float.MinPositiveValue),
      (Float.MaxValue, Float.MaxValue),                 // Large values
      (Float.MaxValue, 1.0f),
      (-Float.MaxValue, -Float.MaxValue),
      (-Float.MaxValue, 1.0f)
    )

    specialCases.zipWithIndex.foreach { case ((a, b), index) => testSingle(dut, index + 1, a, b) }
  }

  /** TestFloat-style boundary encodings: zeros, subnormal/normal/overflow edges, rounding ties, specials. */
  def boundaryPatterns(t: FpType): Seq[BigInt] = {
    val bias = expBias(t.expWidth)
    val emax = (1 << t.expWidth) - 1
    val man1 = (BigInt(1) << t.sigWidth) - 1 // all-ones mantissa
    val half = BigInt(1) << (t.sigWidth - 1) // tie bit
    val mags = Seq[BigInt](
      0, // zero
      1,
      2,
      half,
      man1,                                    // subnormals: min, min+1, mid, max
      BigInt(1) << t.sigWidth,
      (BigInt(1) << t.sigWidth) | 1,           // min normal, +1ulp
      (BigInt(bias - 1) << t.sigWidth) | man1, // just below 1.0
      BigInt(bias) << t.sigWidth,
      (BigInt(bias) << t.sigWidth) | 1,        // 1.0, 1.0+1ulp
      (BigInt(bias) << t.sigWidth) | half,
      (BigInt(bias) << t.sigWidth) | half | 1, // 1.5, 1.5+1ulp
      BigInt(emax - 1) << t.sigWidth,
      (BigInt(emax - 1) << t.sigWidth) | 1,    // near-overflow
      (BigInt(emax - 1) << t.sigWidth) | man1, // max normal
      BigInt(emax) << t.sigWidth               // inf
    ).distinct
    val signed = for (s <- Seq(0, 1); m <- mags) yield (BigInt(s) << (t.width - 1)) | m
    signed :+ ((BigInt(emax) << t.sigWidth) | half) // one NaN
  }

  /** Boundary cross product + random + near-cancellation + magnitude-skew operand pairs (as raw bit patterns). */
  def cornerStimulus(dut: FpAddFp): Iterator[(BigInt, BigInt)] = {
    val rng = new scala.util.Random(2025)
    val wA  = dut.typeA.width; val wB = dut.typeB.width
    def rnd(w: Int) = BigInt(w, rng)

    val bndA  = boundaryPatterns(dut.typeA)
    val bndB  = boundaryPatterns(dut.typeB)
    val cross = for (a <- bndA.iterator; b <- bndB) yield (a, b)

    val randoms = Iterator.fill(cornerRandomNum)((rnd(wA), rnd(wB)))

    // near-cancellation: b ~= -a (rounded into B's format), half of them off by 1-3 ulp
    val cancels = Iterator.fill(cornerRandomNum) {
      val aB   = rnd(wA)
      val negA = floatToUInt(dut.typeB, quantize(dut.typeB, -uintToFloat(dut.typeA, aB)))
      (aB, if (rng.nextBoolean()) negA ^ BigInt(1 + rng.nextInt(3)) else negA)
    }

    // magnitude skew: tiny vs random operand (far-apart exponents, sticky stress)
    def tiny(t: FpType) = Seq[BigInt](1, 2, 3, (BigInt(1) << t.sigWidth) - 1, BigInt(1) << t.sigWidth)
    val tinyA           = tiny(dut.typeA); val tinyB = tiny(dut.typeB)
    val skews           = Iterator.fill(cornerRandomNum) {
      if (rng.nextBoolean()) (rnd(wA), tinyB(rng.nextInt(tinyB.length)) | (BigInt(rng.nextInt(2)) << (wB - 1)))
      else (tinyA(rng.nextInt(tinyA.length)) | (BigInt(rng.nextInt(2)) << (wA - 1)), rnd(wB))
    }

    cross ++ randoms ++ cancels ++ skews
  }

  /** Drives the corner stimulus through the RTL and checks it bit-exactly (signed zeros included, NaN as a class)
    * against the software reference addHardware — which is itself verified against MPFR in FpGoldenVectorTest.
    */
  def testCornerSweep(dut: FpAddFp, name: String): Unit = {
    var total = 0; var mism = 0; var shown = 0
    for ((aB, bB) <- cornerStimulus(dut)) {
      val a    = uintToFloat(dut.typeA, aB)
      val b    = uintToFloat(dut.typeB, bB)
      val expB = floatToUInt(dut.typeC, addHardware(a, b, dut.typeA, dut.typeB, dut.typeC))
      dut.io.in_a.poke(aB.U(dut.typeA.width.W))
      dut.io.in_b.poke(bB.U(dut.typeB.width.W))
      dut.clock.step(1)
      val resB = dut.io.out.peek().litValue
      total += 1
      val ok   = if (isNaN(expB, dut.typeC)) isNaN(resB, dut.typeC) else resB == expB
      if (!ok) {
        mism += 1
        if (shown < 20) {
          println(f"MISMATCH $name a=0x$aB%08x b=0x$bB%08x expected=0x$expB%08x hw=0x$resB%08x"); shown += 1
        }
      }
    }
    println(s"FpAddFp $name vs addHardware: $mism / $total mismatches")
    assert(mism == 0)
  }

  it should "perform FP32 + FP32 -> FP32 correctly" in {
    test(
      new FpAddFp(typeA = FP32, typeB = FP32, typeC = FP32)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => test_all_fp_add_fp(dut) }
  }

  it should "perform FP16 + FP16 -> FP16 correctly" in {
    test(
      new FpAddFp(typeA = FP16, typeB = FP16, typeC = FP16)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => test_all_fp_add_fp(dut) }
  }

  it should "perform FP16 + FP16 -> FP32 correctly" in {
    test(
      new FpAddFp(typeA = FP16, typeB = FP16, typeC = FP32)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => test_all_fp_add_fp(dut) }
  }

  it should "perform FP16 + FP32 -> FP32 correctly" in {
    test(
      new FpAddFp(typeA = FP16, typeB = FP32, typeC = FP32)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => test_all_fp_add_fp(dut) }
  }

  it should "perform FP16 + FP32 -> FP16 correctly" in {
    test(
      new FpAddFp(typeA = FP16, typeB = FP32, typeC = FP16)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => test_all_fp_add_fp(dut) }
  }

  it should "perform BF16 + BF16 -> FP32 correctly" in {
    test(
      new FpAddFp(typeA = BF16, typeB = BF16, typeC = FP32)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => test_all_fp_add_fp(dut) }
  }

  it should "perform BF16 + BF16 -> BF16 correctly" in {
    test(
      new FpAddFp(typeA = BF16, typeB = BF16, typeC = BF16)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => test_all_fp_add_fp(dut) }
  }

  it should "perform FP32 + BF16 -> FP32 correctly" in {
    test(
      new FpAddFp(typeA = FP32, typeB = BF16, typeC = FP32)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => test_all_fp_add_fp(dut) }
  }

// Special cases
  it should "handle special cases in FP16 + FP32 -> FP16" in {
    test(
      new FpAddFp(typeA = FP16, typeB = FP32, typeC = FP16)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

  it should "handle special cases in FP16 + FP32 -> FP32" in {
    test(
      new FpAddFp(typeA = FP16, typeB = FP32, typeC = FP32)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

  it should "handle special cases in FP32 + FP16 -> BF16" in {
    test(
      new FpAddFp(typeA = FP32, typeB = FP16, typeC = BF16)
    ).withAnnotations(Seq(VcsBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

// Corner sweep
  for ((tA, tB, tC) <- cornerCombos) {
    val n = s"${fmtName(tA)}_${fmtName(tB)}_${fmtName(tC)}"
    it should s"match addHardware on the corner sweep ($n)" in {
      test(new FpAddFp(tA, tB, tC)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        testCornerSweep(dut, n)
      }
    }
  }
}
