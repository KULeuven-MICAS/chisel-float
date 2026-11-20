// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Xiaoling Yi <xiaoling.yi@kuleuven.be>
// Modified by: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import FpReference.mask
import FpStimulus._

class FpMulIntTest extends AnyFlatSpec with ChiselScalatestTester with FpTestHarness {
  behavior of "FpMulInt"

  val widths = 1 to 11

  /** FP16 boundary values against every integer (or its boundary values), plus random pairs. */
  def stimulus(w: Int): Iterator[Seq[BigInt]] = {
    if (FpTestConfig.isExtended && w <= 4) return exhaustive(16, w)
    val r      = rng(s"mulint-$w")
    val ints   =
      if (w <= 8) (0 until (1 << w)).map(BigInt(_))
      else {
        val min = BigInt(1) << (w - 1)
        (Seq[BigInt](0, 1, 2, 3, mask(w), mask(w) - 1, min, min + 1, min - 1) ++ Seq.fill(64)(bits(r, w))).distinct
      }
    val random = Iterator.fill(FpTestConfig.scaled(20000))(Seq(bits(r, 16), bits(r, w)))
    cross(boundary(FP16), ints) ++ random
  }

  for (w <- widths) {
    it should s"match the reference model bit-exactly for FP16 x Int$w -> FP32" in {
      test(new FpMulInt(typeB = new IntType(w))).withAnnotations(FpTestConfig.annotations) { dut =>
        sweep(s"FpMulInt FP16 x Int$w -> FP32", Seq(FP16, new IntType(w)), FP32, stimulus(w)) { v =>
          dut.io.in_a.poke(v(0).U)
          dut.io.in_b.poke(v(1).U)
          dut.io.out.peek().litValue
        } { v => FpReference.mulInt(w, v(0), v(1)) }
      }
    }
  }

  it should "reject unsupported formats and integer widths" in {
    assertElaborationFails(new FpMulInt(typeB = new IntType(12)), "width")
    assertElaborationFails(new FpMulInt(typeB = new IntType(0)), "width")
    assertElaborationFails(new FpMulInt(typeA = BF16, typeB = Int4), "FP16")
    assertElaborationFails(new FpMulInt(typeB = Int4, typeC = BF16), "FP32")
  }
}
