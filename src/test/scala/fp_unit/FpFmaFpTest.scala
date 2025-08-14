// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._

import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FpFmaFpTest extends AnyFlatSpec with Matchers with ChiselScalatestTester with FpUtils {
  behavior of "FpFmaFp"

  val test_num = 1000

  def testSingle(dut: FpFmaFp, test_id: Int, a: Float, b: Float, c: Float) = {
    // If typeC is FP32 (float), there will be rounding between mul and add, but not in the hardware FMA.
    // TODO is there a way to perform a true FMA operation in software?
    val lsbTolerance = if (dut.typeC == FP32) (dut.typeA.sigWidth + dut.typeB.sigWidth + 2) else 0

    val mul_fp   = (a, dut.typeA) * (b, dut.typeB)
    val expected = (mul_fp, FP32) + (c, dut.typeC)
    // val mul_fp   = quantize(dut.typeA, a).toDouble * quantize(dut.typeB, b).toDouble
    // val expected = mul_fp + quantize(dut.typeC, c).toDouble

    // Quantize the inputs
    val a_uint = floatToUInt(dut.typeA, a)
    val b_uint = floatToUInt(dut.typeB, b)
    val c_uint = floatToUInt(dut.typeC, c)

    dut.io.in_a.poke(a_uint.U)
    dut.io.in_b.poke(b_uint.U)
    dut.io.in_c.poke(c_uint.U)
    dut.clock.step(1)
    val result = dut.io.out.peek()

    // Debugging
    val a_fp          = quantize(dut.typeA, a)
    val b_fp          = quantize(dut.typeB, b)
    val c_fp          = quantize(dut.typeC, c)
    val result_fp     = uintToFloat(dut.outType, result)
    val expected_uint = floatToUInt(dut.outType, expected)
    val expected_fp   = quantize(dut.outType, expected)

    withClue(
      s"❌[Test $test_id] ($a_fp * $b_fp) + $c_fp = $expected_fp (expected) != $result_fp (got)\n" +
        s"(expected) ${uintToStr(expected_uint, dut.outType)} (got) ${uintToStr(result.litValue, dut.outType)}"
    ) { fpEqualsHardware(expected, result, dut.outType, lsbTolerance) shouldBe true }
  }

  def testAll(dut: FpFmaFp, lsbTolerance: Int = 0) = {
    val testCases =
      Seq.fill(test_num)((genRandomValue(dut.typeA), genRandomValue(dut.typeB), genRandomValue(dut.typeC)))
    testCases.zipWithIndex.foreach { case ((a, b, c), index) => testSingle(dut, index + 1, a, b, c) }
  }

  def testSpecialCases(dut: FpFmaFp) = {
    val specialCases = Seq(
      (0.0f, 0.0f, 0.0f),                                     // Zero cases
      (0.0f, 1.0f, 0.0f),
      (1.0f, 0.0f, 0.0f),
      (0.0f, 0.0f, 1.0f),
      (1.0f, 1.0f, 0.0f),
      (0.0f, 1.0f, 1.0f),
      (1.0f, 0.0f, 1.0f),
      (Float.NaN, 1.0f, 0.0f),                                // NaN cases
      (1.0f, Float.NaN, 0.0f),
      (1.0f, 0.0f, Float.NaN),
      (Float.NaN, Float.NaN, 1.0f),
      (1.0f, Float.NaN, Float.NaN),
      (Float.NaN, 1.0f, Float.NaN),
      (Float.NaN, Float.NaN, Float.NaN),
      (Float.PositiveInfinity, 1.0f, 0.0f),                   // Infinity cases
      (1.0f, Float.PositiveInfinity, 0.0f),
      (1.0f, 0.0f, Float.PositiveInfinity),
      (Float.PositiveInfinity, Float.PositiveInfinity, 1.0f),
      (Float.PositiveInfinity, 1.0f, Float.PositiveInfinity),
      (1.0f, Float.PositiveInfinity, Float.PositiveInfinity),
      (Float.PositiveInfinity, Float.PositiveInfinity, Float.PositiveInfinity),
      (Float.NegativeInfinity, 1.0f, 0.0f),
      (1.0f, Float.NegativeInfinity, 0.0f),
      (1.0f, 0.0f, Float.NegativeInfinity),
      (Float.NegativeInfinity, Float.NegativeInfinity, 1.0f),
      (Float.NegativeInfinity, 1.0f, Float.NegativeInfinity),
      (1.0f, Float.NegativeInfinity, Float.NegativeInfinity),
      (Float.NegativeInfinity, Float.NegativeInfinity, Float.NegativeInfinity),
      (Float.PositiveInfinity, Float.NegativeInfinity, 0.0f), // Mixed infinity and NaN
      (Float.NegativeInfinity, Float.PositiveInfinity, 0.0f),
      (Float.PositiveInfinity, Float.NegativeInfinity, Float.NaN),
      (Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity),
      (Float.MinPositiveValue, Float.MinPositiveValue, 0.0f), // Smallest positive
      (Float.MinPositiveValue, 0.0f, Float.MinPositiveValue),
      (0.0f, Float.MinPositiveValue, Float.MinPositiveValue),
      (Float.MinPositiveValue, Float.MinPositiveValue, Float.MinPositiveValue),
      (Float.MaxValue, Float.MaxValue, 0.0f),                 // Large values
      (Float.MaxValue, 1.0f, Float.MaxValue),
      (1.0f, Float.MaxValue, Float.MaxValue),
      (Float.MaxValue, Float.MaxValue, Float.MaxValue),
      (-Float.MaxValue, -Float.MaxValue, 0.0f),
      (-Float.MaxValue, 1.0f, -Float.MaxValue),
      (1.0f, -Float.MaxValue, -Float.MaxValue),
      (-Float.MaxValue, -Float.MaxValue, -Float.MaxValue)
    )

    specialCases.zipWithIndex.foreach { case ((a, b, c), index) => testSingle(dut, index + 1, a, b, c) }
  }

  // it should "debug" in {
  //   test(new FpFmaFp(typeA = FP32, typeB = FP32, typeC = FP32))
  //     .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
  //       testSingle(dut, 1, 123.245f, 0.1234f, 0.0f)
  //     }
  // }

  it should "perform FP32 x FP32 + FP32 FMA correctly" in {
    test(new FpFmaFp(typeA = FP32, typeB = FP32, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP16 x FP16 + FP16 FMA correctly" in {
    test(new FpFmaFp(typeA = FP16, typeB = FP16, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP16 x FP16 + FP32 FMA correctly" in {
    test(new FpFmaFp(typeA = FP16, typeB = FP16, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP16 x FP32 + FP32 FMA correctly" in {
    test(new FpFmaFp(typeA = FP16, typeB = FP32, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform BF16 x BF16 + FP32 FMA correctly" in {
    test(new FpFmaFp(typeA = BF16, typeB = BF16, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform BF16 x BF16 + BF16 FMA correctly" in {
    test(new FpFmaFp(typeA = BF16, typeB = BF16, typeC = BF16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP32 x BF16 + FP32 FMA correctly" in {
    test(new FpFmaFp(typeA = FP32, typeB = BF16, typeC = FP32))
      // The software reference works in float (FP32), and casts to FP32 after mul, losing precision
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP8 x FP8 + FP32 FMA correctly" in {
    test(new FpFmaFp(typeA = FP8, typeB = FP8, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP8 x FP16 + FP16 FMA correctly" in {
    test(new FpFmaFp(typeA = FP8, typeB = FP16, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP16 x FP8 + FP16 FMA correctly" in {
    test(new FpFmaFp(typeA = FP16, typeB = FP8, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP8 x FP16 + FP32 FMA correctly" in {
    test(new FpFmaFp(typeA = FP8, typeB = FP16, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP16 x FP8 + FP32 FMA correctly" in {
    test(new FpFmaFp(typeA = FP16, typeB = FP8, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "handle FP8 x FP16 + FP16 special cases" in {
    test(new FpFmaFp(typeA = FP8, typeB = FP16, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

}
