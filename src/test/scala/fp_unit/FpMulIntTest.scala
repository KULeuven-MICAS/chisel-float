// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Xiaoling Yi <xiaoling.yi@kuleuven.be>
// Modified by: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import scala.util.Random

import chisel3._

import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FpMulIntTest extends AnyFlatSpec with Matchers with ChiselScalatestTester with FpUtils {
  behavior of "FpMulInt"

  val test_num = 1000

  def genRandomInt(intType: IntType) = Random.nextInt(1 << intType.width) - (1 << (intType.width - 1))

  def testSingle(dut: FpMulInt, test_id: Int, a: Float, b: Int) = {

    val expected =
      if (dut.typeB.width == 1) quantize(dut.typeA, a) * (if (b == 0) -1 else 1)
      else quantize(dut.typeA, a) * b

    // Quantize the inputs
    val a_uint = floatToUInt(dut.typeA, a)
    val b_uint = (BigInt(b) & ((BigInt(1) << dut.typeB.width) - 1))

    dut.io.in_a.poke(a_uint.U)
    dut.io.in_b.poke(b_uint.U)
    dut.clock.step(1)
    val result = dut.io.out.peek()

    // Debugging
    val a_fp          = quantize(dut.typeA, a)
    val result_fp     = uintToFloat(dut.typeC, result)
    val expected_uint = floatToUInt(dut.typeC, expected)
    val expected_fp   = quantize(dut.typeC, expected)

    withClue(
      s"❌[Test $test_id] $a_fp x $b = $expected_fp (expected) != $result_fp (got)\n" +
        s"(expected) ${uintToStr(expected_uint, dut.typeC)} (got) ${uintToStr(result.litValue, dut.typeC)}"
    ) { (expected_fp, dut.typeC) === result shouldBe true }
  }

  def testAll(dut: FpMulInt) = {
    val testCases = Seq.fill(test_num)((genRandomValue(dut.typeA), genRandomInt(dut.typeB)))
    testCases.zipWithIndex.foreach { case ((a, b), index) => testSingle(dut, index + 1, a, b) }
  }

  def testSpecialCases(dut: FpMulInt) = {
    val specialCases = Seq(
      (0.0f, 0),                   // Zero cases
      (0.0f, 1),
      (1.0f, 0),
      (Float.NaN, 1),              // NaN cases
      (Float.PositiveInfinity, 1), // Infinity cases
      (Float.NegativeInfinity, 1),
      (Float.MinPositiveValue, 0)
    )
    specialCases.zipWithIndex.foreach { case ((a, b), index) => testSingle(dut, index + 1, a, b) }
  }

  it should "perform FP16-int4 multiply correctly" in {
    test(new FpMulInt(typeB = Int4)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      testAll(dut)
    }
  }

  it should "perform FP16-int3 multiply correctly" in {
    test(new FpMulInt(typeB = Int3)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      testAll(dut)
    }
  }

  it should "perform FP16-int2 multiply correctly" in {
    test(new FpMulInt(typeB = Int2)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      testAll(dut)
    }
  }

  it should "perform FP16-int1 multiply correctly" in {
    test(new FpMulInt(typeB = Int1)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      testAll(dut)
    }
  }

  it should "handle special cases" in {
    test(new FpMulInt(typeB = Int3)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      testSpecialCases(dut)
    }
  }

  it should "perform FP16 x Int2 -> FP16 multiply correctly" in {
    test(new FpMulInt(FP16, Int2, FP16)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      testAll(dut)
    }
  }
}
