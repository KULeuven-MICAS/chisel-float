// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51
//
// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._

import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FpDivSqrtTest extends AnyFlatSpec with Matchers with ChiselScalatestTester with FpUtils {
  behavior of "FpDivSqrt"

  // Note: this unit is iterative and uses a BlackBox; keep test count reasonable.
  val test_num           = 1000
  val maxLatencyCycles   = 300
  val maxReadyWaitCycles = 50

  private def stepUntil(dut: FpDivSqrt, maxCycles: Int)(pred: => Boolean): Unit = {
    var cycles = 0
    while (!pred && cycles < maxCycles) {
      dut.clock.step(1)
      cycles += 1
    }
    pred shouldBe true
  }

  /** Issue one div/sqrt request (both inputs in same cycle) and return the output bits when valid. */
  private def issueAndGetResult(dut: FpDivSqrt, modeSqrt: Boolean, a: Float, b: Float): UInt = {
    val fpType = BF16

    dut.io.mode.poke(modeSqrt.B)
    dut.io.out.ready.poke(true.B)

    dut.io.in_a.valid.poke(true.B)
    dut.io.in_b.valid.poke(true.B)
    dut.io.in_a.bits.poke(floatToUInt(fpType, a).U)
    dut.io.in_b.bits.poke(floatToUInt(fpType, b).U)

    // Wait until the unit is ready to accept the request. Keep valids asserted until accepted.
    stepUntil(dut, maxReadyWaitCycles) { dut.io.in_a.ready.peekBoolean() && dut.io.in_b.ready.peekBoolean() }

    // Fire happens in the current cycle (valids are high). Step once to register the start.
    dut.clock.step(1)
    dut.io.in_a.valid.poke(false.B)
    dut.io.in_b.valid.poke(false.B)

    // Wait for the result.
    stepUntil(dut, maxLatencyCycles) { dut.io.out.valid.peekBoolean() }
    val result = dut.io.out.bits.peek()

    // Consume/clear for next iteration (out.ready is already high).
    dut.clock.step(1)
    result
  }

  private def testSingleDiv(dut: FpDivSqrt, test_id: Int, a: Float, b: Float): Unit = {
    val fpType = BF16

    val expected      = (a, fpType) / (b, fpType)
    val expected_uint = floatToUInt(fpType, expected)
    val expected_fp   = quantize(fpType, expected)

    val result    = issueAndGetResult(dut, modeSqrt = false, a = a, b = b)
    val result_fp = uintToFloat(fpType, result)

    // Debugging
    val a_fp = quantize(fpType, a)
    val b_fp = quantize(fpType, b)

    withClue(
      s"❌[Div Test $test_id] $a_fp / $b_fp = $expected_fp (expected) != $result_fp (got)\n" +
        s"(expected) ${uintToStr(expected_uint, fpType)} (got) ${uintToStr(result.litValue, fpType)}"
    ) { (expected_fp, fpType) =~= result shouldBe true }
  }

  private def testSingleSqrt(dut: FpDivSqrt, test_id: Int, a: Float): Unit = {
    val fpType = BF16

    // Reference (FpUtils) + normalize -0.0f to +0.0f for stability.
    val ref      = fpSqrtHardware(a, fpType)
    val expected = if (ref == -0.0f) 0.0f else ref

    val expected_uint = floatToUInt(fpType, expected)
    val expected_fp   = quantize(fpType, expected)

    // Operand_b is ignored in sqrt mode by the underlying unit; drive something benign.
    val result    = issueAndGetResult(dut, modeSqrt = true, a = a, b = 1.0f)
    val result_fp = uintToFloat(fpType, result)

    val a_fp = quantize(fpType, a)
    withClue(
      s"❌[Sqrt Test $test_id] sqrt($a_fp) = $expected_fp (expected) != $result_fp (got)\n" +
        s"(expected) ${uintToStr(expected_uint, fpType)} (got) ${uintToStr(result.litValue, fpType)}"
    ) { (expected_fp, fpType) =~= result shouldBe true }
  }

  private def testAllDiv(dut: FpDivSqrt): Unit = {
    val fpType    = BF16
    val testCases =
      Seq.fill(test_num)((genRandomValue(fpType), genRandomValue(fpType))) ++
        Seq.fill(test_num)((getTrueRandomValue(fpType), getTrueRandomValue(fpType)))

    testCases.zipWithIndex.foreach { case ((a, b), index) => testSingleDiv(dut, index + 1, a, b) }
  }

  private def testAllSqrt(dut: FpDivSqrt): Unit = {
    val fpType    = BF16
    val testCases =
      Seq.fill(test_num)(genRandomValue(fpType)) ++
        Seq.fill(test_num)(getTrueRandomValue(fpType))

    testCases.zipWithIndex.foreach { case (a, index) => testSingleSqrt(dut, index + 1, a) }
  }

  private def testDivSpecialCases(dut: FpDivSqrt): Unit = {
    val specialCases = Seq(
      (0.0f, 1.0f),
      (1.0f, 0.0f),
      (0.0f, 0.0f),
      (-0.0f, 2.0f),
      (2.0f, -0.0f),
      (Float.NaN, 1.0f),
      (1.0f, Float.NaN),
      (Float.NaN, Float.NaN),
      (Float.PositiveInfinity, 1.0f),
      (1.0f, Float.PositiveInfinity),
      (Float.NegativeInfinity, 1.0f),
      (1.0f, Float.NegativeInfinity),
      (Float.PositiveInfinity, Float.NegativeInfinity),
      (Float.MinPositiveValue, Float.MinPositiveValue),
      (Float.MinPositiveValue, 0.0f),
      (0.0f, Float.MinPositiveValue)
    )
    specialCases.zipWithIndex.foreach { case ((a, b), index) => testSingleDiv(dut, index + 1, a, b) }
  }

  private def testSqrtSpecialCases(dut: FpDivSqrt): Unit = {
    val specialCases = Seq(
      0.0f,
      -0.0f,
      1.0f,
      4.0f,
      Float.MinPositiveValue,
      -1.0f,                   // -> NaN
      -Float.MinPositiveValue, // -> NaN (or -0 depending on quantization; reference handles it)
      Float.NaN,
      Float.PositiveInfinity,
      Float.NegativeInfinity   // -> NaN
    )
    specialCases.zipWithIndex.foreach { case (a, index) => testSingleSqrt(dut, index + 1, a) }
  }

  it should "perform BF16 division correctly (random)" in {
    test(new FpDivSqrt())
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllDiv(dut) }
  }

  it should "perform BF16 sqrt correctly (random)" in {
    test(new FpDivSqrt())
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testAllSqrt(dut) }
  }

  it should "handle BF16 division special cases" in {
    test(new FpDivSqrt())
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testDivSpecialCases(dut) }
  }

  it should "handle BF16 sqrt special cases" in {
    test(new FpDivSqrt())
      .withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut => testSqrtSpecialCases(dut) }
  }
}
