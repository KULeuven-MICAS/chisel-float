// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Xiaoling Yi <xiaoling.yi@kuleuven.be>
// Modified by: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import FpStimulus._

class FpMulFpTest extends AnyFlatSpec with ChiselScalatestTester with FpTestHarness {
  behavior of "FpMulFp"

  val ieeeTypes = Seq(FP32, FP16, BF16, FP8)

  /** IEEE 754 combinations. FP_TEST_LEVEL=extended runs all 64. */
  val ieeeCombos: Seq[(FpType, FpType, FpType)] =
    if (FpTestConfig.isExtended) for {
      a <- ieeeTypes
      b <- ieeeTypes
      c <- ieeeTypes
    } yield (a, b, c)
    else
      Seq(
        (FP32, FP32, FP32),
        (FP16, FP16, FP16),
        (BF16, BF16, BF16),
        (FP8, FP8, FP8),
        (FP16, FP16, FP32),
        (BF16, BF16, FP32),
        (BF16, FP16, FP32),
        (FP16, BF16, FP32),
        (FP8, FP8, FP32),
        (FP8, FP8, FP16),
        (FP8, FP8, BF16),
        (FP16, FP8, FP32),
        (FP8, FP32, FP16),
        (FP16, FP32, FP16), // narrowing
        (FP16, FP32, BF16), // narrowing
        (FP32, FP32, FP16), // narrowing
        (FP32, FP32, BF16), // narrowing
        (BF16, BF16, FP8)   // narrowing
      )

  /** Non-IEEE FP8_ALT operands, into every output format. */
  val altCombos: Seq[(FpType, FpType, FpType)] =
    Seq(FP8_ALT, BF16, FP16, FP32, FP8).map(out => (FP8_ALT, FP8_ALT, out))

  /** Exhaustive for 8x8-bit operands, otherwise boundary, random and threshold vectors. */
  def stimulus(tA: FpType, tB: FpType, tC: FpType): Iterator[Seq[BigInt]] = {
    if (tA.width + tB.width <= 16) return exhaustive(tA.width, tB.width)
    val r = rng(s"mul-${name(tA)}-${name(tB)}-${name(tC)}")
    val n = FpTestConfig.scaled(20000)

    val corners    = cross(boundary(tA), boundary(tB))
    val random     = Iterator.fill(n)(Seq(bits(r, tA.width), bits(r, tB.width)))
    // product exponent ea + eb around a critical exponent of the output format
    val (loA, hiA) = exponentRange(tA)
    val (loB, hiB) = exponentRange(tB)
    val crit       = criticalExponents(tC)
    val near       = Iterator.fill(n) {
      val e    = crit(r.nextInt(crit.length))
      val from = math.max(loA, e - hiB)
      val to   = math.min(hiA, e - loB)
      val ea   = if (from <= to) from + r.nextInt(to - from + 1) else loA + r.nextInt(hiA - loA + 1)
      Seq(nearExponent(r, tA, ea), nearExponent(r, tB, e - ea - r.nextInt(2)))
    }
    corners ++ random ++ near
  }

  for ((tA, tB, tC) <- ieeeCombos ++ altCombos) {
    val label = s"${name(tA)} x ${name(tB)} -> ${name(tC)}"
    it should s"match the reference model bit-exactly for $label" in {
      test(new FpMulFp(tA, tB, tC)).withAnnotations(FpTestConfig.annotations) { dut =>
        sweep(s"FpMulFp $label", Seq(tA, tB), tC, stimulus(tA, tB, tC)) { v =>
          dut.io.in_a.poke(v(0).U)
          dut.io.in_b.poke(v(1).U)
          dut.io.out.peek().litValue
        } { v => FpReference.mul(tA, tB, tC, v(0), v(1)) }
      }
    }
  }

  it should "reject IEEE x IEEE -> FP8_ALT and mixed IEEE/non-IEEE operands" in {
    assertElaborationFails(new FpMulFp(FP16, FP16, FP8_ALT), "FP8_ALT")
    assertElaborationFails(new FpMulFp(FP8_ALT, FP16, FP32), "IEEE 754")
  }
}
