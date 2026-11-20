// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Xiaoling Yi <xiaoling.yi@kuleuven.be>
// Modified by: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import FpReference.{mask, zero}
import FpStimulus._

class FpAddFpTest extends AnyFlatSpec with ChiselScalatestTester with FpTestHarness {
  behavior of "FpAddFp"

  val ieeeTypes = Seq(FP32, FP16, BF16, FP8)

  /** Same-format, widening, mixed and narrowing combinations. FP_TEST_LEVEL=extended runs all 64. */
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
        (FP8, FP8, FP16),
        (FP8, FP16, FP16),
        (BF16, FP16, FP32),
        (FP16, FP32, FP16), // narrowing
        (FP32, FP16, BF16), // narrowing
        (FP32, FP32, FP16), // narrowing
        (FP32, FP32, BF16), // narrowing
        (FP16, FP16, FP8),  // narrowing
        (BF16, FP16, FP8)   // narrowing
      )

  /** Exhaustive for 8+8-bit operands, otherwise boundary, random, cancellation and alignment vectors. */
  def stimulus(tA: FpType, tB: FpType, tC: FpType): Iterator[Seq[BigInt]] = {
    if (tA.width + tB.width <= 16) return exhaustive(tA.width, tB.width)
    val r = rng(s"add-${name(tA)}-${name(tB)}-${name(tC)}")
    val n = FpTestConfig.scaled(15000)

    val corners         = cross(boundary(tA), boundary(tB))
    val random          = Iterator.fill(n)(Seq(bits(r, tA.width), bits(r, tB.width)))
    // b ~= -a (rounded into B's format), half of them off by a few ulps
    val cancel          = Iterator.fill(n) {
      val a = bits(r, tA.width)
      Seq(a, perturb(r, negate(tB, FpReference.convert(tA, tB, a))))
    }
    // tiny vs. random operand: maximal alignment shifts
    def tiny(t: FpType) =
      zero(t, r.nextInt(2)) | Seq[BigInt](1, 2, 3, mask(t.sigWidth), BigInt(1) << t.sigWidth)(
        r.nextInt(5)
      )
    val skew            = Iterator.fill(n) {
      if (r.nextBoolean()) Seq(bits(r, tA.width), tiny(tB)) else Seq(tiny(tA), bits(r, tB.width))
    }
    // operands (and thus results) around the output format's critical exponents
    val crit            = criticalExponents(tC)
    val near            = Iterator.fill(n) {
      val e = crit(r.nextInt(crit.length))
      Seq(nearExponent(r, tA, e), nearExponent(r, tB, e - r.nextInt(4)))
    }
    corners ++ random ++ cancel ++ skew ++ near
  }

  for ((tA, tB, tC) <- combos) {
    val label = s"${name(tA)} + ${name(tB)} -> ${name(tC)}"
    it should s"match the reference model bit-exactly for $label" in {
      test(new FpAddFp(tA, tB, tC)).withAnnotations(FpTestConfig.annotations) { dut =>
        sweep(s"FpAddFp $label", Seq(tA, tB), tC, stimulus(tA, tB, tC)) { v =>
          dut.io.in_a.poke(v(0).U)
          dut.io.in_b.poke(v(1).U)
          dut.io.out.peek().litValue
        } { v => FpReference.add(tA, tB, tC, v(0), v(1)) }
      }
    }
  }

  it should "match the combinational adder after the pipeline latency (FpAddFpSequential)" in {
    val (pre, post) = (2, 1)
    test(new FpAddFpSequential(FP16, FP16, FP16, pre, post, "fp_add_seq"))
      .withAnnotations(FpTestConfig.annotations) { dut =>
        val r       = rng("add-seq")
        val vectors = Seq.fill(FpTestConfig.scaled(2000))((bits(r, 16), bits(r, 16)))
        val outputs = vectors.map { case (a, b) =>
          dut.io.in_a.poke(a.U)
          dut.io.in_b.poke(b.U)
          dut.clock.step()
          dut.io.out.peek().litValue
        }
        // Output at cycle i belongs to the inputs of cycle i - (pre + post) + 1
        val lat     = pre + post - 1
        vectors.zip(outputs.drop(lat)).foreach { case ((a, b), out) =>
          assert(out == FpReference.add(FP16, FP16, FP16, a, b))
        }
      }
  }

  it should "reject non-IEEE 754 formats" in {
    assertElaborationFails(new FpAddFp(FP8_ALT, FP8_ALT, BF16), "IEEE 754")
    assertElaborationFails(new FpAddFp(BF16, BF16, FP8_ALT), "IEEE 754")
  }
}
