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

class FpConvertTest extends AnyFlatSpec with Matchers with ChiselScalatestTester with FpUtils {
  behavior of "FpConvert"

  val test_num = 1000

  def testSingle(dut: FpConvert, test_id: Int, a: Float) = {

    val in_fp = quantize(dut.typeA, a)

    val expected_uint = floatToUInt(dut.typeC, in_fp)
    val expected_fp   = uintToFloat(dut.typeC, expected_uint)

    // Quantize the inputs
    val a_uint    = floatToUInt(dut.typeA, a)
    dut.io.in_a.poke(a_uint.U)
    dut.clock.step(1)
    val result    = dut.io.out.peek()
    val result_fp = uintToFloat(dut.typeC, result)

    withClue(
      s"❌[Test $test_id] $in_fp -> $expected_fp (expected) != $result_fp (got)\n" +
        s"(expected) ${uintToStr(expected_uint, dut.typeC)} (got) ${uintToStr(result.litValue, dut.typeC)}"
    ) { (in_fp, dut.typeC) === result shouldBe true }

  }

  def testAllConvert(dut: FpConvert) = {
    val testCases = Seq.fill(test_num)(genRandomValue(dut.typeA))
    testCases.zipWithIndex.foreach { case (a, index) => testSingle(dut, index + 1, a) }
  }

  def testSpecialCases(dut: FpConvert) = {
    val specialCases =
      Seq(0.0f, 1.0f, Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity, Float.MinPositiveValue)

    specialCases.zipWithIndex.foreach { case (a, index) => testSingle(dut, index + 1, a) }
  }

  it should "convert FP32 to FP32 correctly" in {
    test(new FpConvert(typeA = FP32, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  it should "convert FP16 to FP16 correctly" in {
    test(new FpConvert(typeA = FP16, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  it should "convert FP8 to BF16 correctly" in {
    test(new FpConvert(typeA = FP8, typeC = BF16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }
  it should "convert FP16 to FP32 correctly" in {
    test(new FpConvert(typeA = FP16, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  it should "convert FP32 to FP16 correctly" in {
    test(new FpConvert(typeA = FP32, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  it should "convert BF16 to FP32 correctly" in {
    test(new FpConvert(typeA = BF16, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  it should "convert BF16 to BF16 correctly" in {
    test(new FpConvert(typeA = BF16, typeC = BF16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  it should "convert FP32 to BF16 correctly" in {
    test(new FpConvert(typeA = FP32, typeC = BF16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  // Special cases
  it should "handle special cases in FP16 to FP32" in {
    test(new FpConvert(typeA = FP16, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

  it should "handle special cases in FP32 to FP16" in {
    test(new FpConvert(typeA = FP32, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

  it should "handle special cases in FP32 to BF16" in {
    test(new FpConvert(typeA = FP32, typeC = BF16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

  it should "convert FP8 to FP8 correctly" in {
    test(new FpConvert(typeA = FP8, typeC = FP8))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  it should "convert FP8 to FP16 correctly" in {
    test(new FpConvert(typeA = FP8, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  it should "convert FP8 to FP32 correctly" in {
    test(new FpConvert(typeA = FP8, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  it should "convert FP16 to FP8 correctly" in {
    test(new FpConvert(typeA = FP16, typeC = FP8))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  it should "convert FP32 to FP8 correctly" in {
    test(new FpConvert(typeA = FP32, typeC = FP8))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllConvert(dut) }
  }

  // Special case tests for FP8
  it should "handle special cases in FP8 to FP16" in {
    test(new FpConvert(typeA = FP8, typeC = FP16))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

  it should "handle special cases in FP8 to FP32" in {
    test(new FpConvert(typeA = FP8, typeC = FP32))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

  it should "handle special cases in FP16 to FP8" in {
    test(new FpConvert(typeA = FP16, typeC = FP8))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }

  it should "handle special cases in FP32 to FP8" in {
    test(new FpConvert(typeA = FP32, typeC = FP8))
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSpecialCases(dut) }
  }
}
