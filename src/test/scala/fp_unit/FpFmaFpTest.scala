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

    val mul_fp   = (a, dut.typeA) * (b, dut.typeB) // Note there is no rounding on the mul output
    val expected = (mul_fp, dut.typeC) + (c, dut.typeC)

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
    val result_fp     = uintToFloat(dut.typeD, result)
    val expected_uint = floatToUInt(dut.typeD, expected)
    val expected_fp   = quantize(dut.typeD, expected)

    withClue(
      s"❌[Test $test_id] ($a_fp * $b_fp) + $c_fp = $expected_fp (expected) != $result_fp (got)\n" +
        s"(expected) ${uintToStr(expected_uint, dut.typeD)} (got) ${uintToStr(result.litValue, dut.typeD)}"
    ) { (expected_fp, dut.typeD) === result shouldBe true }
  }

  def testAll(dut: FpFmaFp) = {
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

  it should "perform FP32 FMA correctly" in {
    test(
      new FpFmaFp(typeA = FP32, typeB = FP32, typeC = FP32, typeD = FP32)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP16 FMA correctly" in {
    test(
      new FpFmaFp(typeA = FP16, typeB = FP16, typeC = FP16, typeD = FP16)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP16 FMA with FP32 accumulation correctly" in {
    test(
      new FpFmaFp(typeA = FP16, typeB = FP16, typeC = FP32, typeD = FP32)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform mixed FP16/FP32 FMA correctly" in {
    test(
      new FpFmaFp(typeA = FP16, typeB = FP32, typeC = FP32, typeD = FP32)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform BF16 FMA with FP32 accumulation correctly" in {
    test(
      new FpFmaFp(typeA = BF16, typeB = BF16, typeC = FP32, typeD = FP32)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform BF16 FMA correctly" in {
    test(
      new FpFmaFp(typeA = BF16, typeB = BF16, typeC = BF16, typeD = BF16)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform mixed FP32/BF16 FMA correctly" in {
    test(
      new FpFmaFp(typeA = FP32, typeB = BF16, typeC = FP32, typeD = FP32)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }
}
