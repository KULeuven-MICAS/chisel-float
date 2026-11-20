// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import FpStimulus._

class FpFmaFpTest extends AnyFlatSpec with ChiselScalatestTester with FpTestHarness {
  behavior of "FpFmaFp"

  val ieeeTypes = Seq(FP32, FP16, BF16, FP8)

  /** FP_TEST_LEVEL=extended runs all 64 combinations. */
  val combos: Seq[(FpType, FpType, FpType)] =
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
        (FP16, FP32, FP32),
        (FP32, BF16, FP32),
        (FP8, FP8, FP32),
        (FP8, FP8, FP16),
        (FP8, FP16, FP16),
        (FP16, FP8, FP16),
        (FP8, FP16, FP32),
        (BF16, FP16, FP16),
        (BF16, BF16, FP16),
        (FP32, FP32, FP16), // narrowing addend/result
        (FP32, FP16, FP8),
        (BF16, FP32, FP8),
        (FP32, FP8, BF16)
      )

  /** Boundary, random, cancellation and product/addend alignment vectors. */
  def stimulus(tA: FpType, tB: FpType, tC: FpType): Iterator[Seq[BigInt]] = {
    val r = rng(s"fma-${name(tA)}-${name(tB)}-${name(tC)}")
    val n = FpTestConfig.scaled(12000)

    val corners    = cross(boundary(tA), boundary(tB), boundary(tC))
    val random     = Iterator.fill(n)(Seq(bits(r, tA.width), bits(r, tB.width), bits(r, tC.width)))
    // c ~= -(a*b) (rounded into C's format), half of them off by a few ulps
    val cancel     = Iterator.fill(n) {
      val a = bits(r, tA.width); val b = bits(r, tB.width)
      Seq(a, b, perturb(r, negate(tC, FpReference.mul(tA, tB, tC, a, b))))
    }
    // addend exponent swept relative to the product exponent, over and beyond the datapath width
    val precC      = tC.sigWidth + 1
    val (loA, hiA) = exponentRange(tA)
    val (loB, hiB) = exponentRange(tB)
    val (loC, hiC) = exponentRange(tC)
    val align      = Iterator.fill(n) {
      val ea = loA + r.nextInt(hiA - loA + 1)
      val eb = loB + r.nextInt(hiB - loB + 1)
      val ec = math.max(loC, math.min(hiC, ea + eb + r.nextInt(5 * precC + 12) - 3 * precC - 8))
      Seq(nearExponent(r, tA, ea), nearExponent(r, tB, eb), nearExponent(r, tC, ec))
    }
    // product and addend around a critical exponent of the output format
    val crit       = criticalExponents(tC)
    val near       = Iterator.fill(n) {
      val e    = crit(r.nextInt(crit.length))
      val from = math.max(loA, e - hiB)
      val to   = math.min(hiA, e - loB)
      val ea   = if (from <= to) from + r.nextInt(to - from + 1) else loA + r.nextInt(hiA - loA + 1)
      Seq(nearExponent(r, tA, ea), nearExponent(r, tB, e - ea), nearExponent(r, tC, e - r.nextInt(3)))
    }
    corners ++ random ++ cancel ++ align ++ near
  }

  for ((tA, tB, tC) <- combos) {
    val label = s"${name(tA)} x ${name(tB)} + ${name(tC)}"
    it should s"match the reference model bit-exactly for $label" in {
      test(new FpFmaFp(tA, tB, tC)).withAnnotations(FpTestConfig.annotations) { dut =>
        sweep(s"FpFmaFp $label", Seq(tA, tB, tC), tC, stimulus(tA, tB, tC)) { v =>
          dut.io.in_a.poke(v(0).U)
          dut.io.in_b.poke(v(1).U)
          dut.io.in_c.poke(v(2).U)
          dut.io.out.peek().litValue
        } { v => FpReference.fma(tA, tB, tC, v(0), v(1), v(2)) }
      }
    }
  }

  it should "reject non-IEEE 754 formats" in {
    assertElaborationFails(new FpFmaFp(FP8_ALT, FP8_ALT, BF16), "IEEE 754")
  }
}
