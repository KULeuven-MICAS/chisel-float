// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Xiaoling Yi <xiaoling.yi@kuleuven.be>
// Modified by: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FpMulFpTest extends AnyFlatSpec with Matchers with ChiselScalatestTester with FpUtils {
  behavior of "FpMulFp"

  val test_num = 1000

  def testSingle(dut: FpMulFp, test_id: Int, a: Float, b: Float) = {

    val expected = (a, dut.typeA) * (b, dut.typeB)

    // Quantize the inputs
    val a_uint = floatToUInt(dut.typeA, a)
    val b_uint = floatToUInt(dut.typeB, b)

    dut.io.in_a.poke(a_uint.U)
    dut.io.in_b.poke(b_uint.U)
    dut.clock.step(1)
    val result = dut.io.out.peek()

    // Debugging
    val a_fp          = quantize(dut.typeA, a)
    val b_fp          = quantize(dut.typeB, b)
    val result_fp     = uintToFloat(dut.typeC, result)
    val expected_uint = floatToUInt(dut.typeC, expected)
    val expected_fp   = quantize(dut.typeC, expected)

    withClue(
      s"❌[Test $test_id] $a_fp x $b_fp = $expected_fp (expected) != $result_fp (got)\n" +
        s"(expected) ${uintToStr(expected_uint, dut.typeC)} (got) ${uintToStr(result.litValue, dut.typeC)}"
    ) { (expected_fp, dut.typeC) === result shouldBe true }
  }

  def testAll(dut: FpMulFp) = {
    val testCases = Seq.fill(test_num)((genRandomValue(dut.typeA), genRandomValue(dut.typeB)))
    testCases.zipWithIndex.foreach { case ((a, b), index) => testSingle(dut, index + 1, a, b) }
  }

  def testSpecialCases(dut: FpMulFp) = {
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
      (0.0f, Float.MinPositiveValue)
    )
    specialCases.zipWithIndex.foreach { case ((a, b), index) => testSingle(dut, index + 1, a, b) }
  }

  it should "perform FP16 x FP16 = FP32 correctly" in {
    test(
      new FpMulFp(typeA = FP16, typeB = FP16, typeC = FP32)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP16 x FP16 = FP16 correctly" in {
    test(new FpMulFp(typeA = FP16, typeB = FP16, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform BF16 x BF16 = FP32 correctly" in {
    test(
      new FpMulFp(typeA = BF16, typeB = BF16, typeC = FP32)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform BF16 x BF16 = BF16 correctly" in {
    test(
      new FpMulFp(typeA = BF16, typeB = BF16, typeC = BF16)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform BF16 x FP16 = FP32 correctly" in {
    test(new FpMulFp(typeA = BF16, typeB = FP16, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP8 x FP8 = FP16 correctly" in {
    test(new FpMulFp(typeA = FP8, typeB = FP8, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP8 x FP32 = FP16 correctly" in {
    test(new FpMulFp(typeA = FP8, typeB = FP32, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  it should "perform FP16 x FP8 = FP32 correctly" in {
    test(new FpMulFp(typeA = FP16, typeB = FP8, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAll(dut) }
  }

  // Special cases
  it should "handle special cases (NaN, Infinity, Underflow) for FP16 x FP32 -> FP16" in {
    test(new FpMulFp(typeA = FP16, typeB = FP32, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

  it should "handle special cases (NaN, Infinity, Underflow) for FP16 x BF16 -> FP32" in {
    test(new FpMulFp(typeA = FP16, typeB = BF16, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

  it should "handle special cases (NaN, Infinity, Underflow) for FP16 x FP32 -> BF16" in {
    test(new FpMulFp(typeA = FP16, typeB = FP32, typeC = BF16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

}
