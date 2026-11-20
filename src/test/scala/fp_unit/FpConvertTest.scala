// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import FpReference.zero
import FpStimulus._

class FpConvertTest extends AnyFlatSpec with ChiselScalatestTester with FpTestHarness {
  behavior of "FpConvert"

  val ieeeTypes = Seq(FP32, FP16, BF16, FP8)

  /** Every supported conversion: all IEEE pairs (identity included), IEEE <-> FP8_ALT and the FP8_ALT identity. */
  val pairs: Seq[(FpType, FpType)] =
    (for {
      a <- ieeeTypes
      c <- ieeeTypes
    } yield (a, c)) ++
      ieeeTypes.map(a => (a, FP8_ALT)) ++
      ieeeTypes.map(c => (FP8_ALT, c)) :+
      ((FP8_ALT, FP8_ALT))

  /** Exhaustive for inputs of up to 16 bits, otherwise boundary, per-exponent and threshold vectors. */
  def stimulus(tA: FpType, tC: FpType): Iterator[Seq[BigInt]] = {
    if (tA.width <= 16) return exhaustive(tA.width)
    val r          = rng(s"cvt-${name(tA)}-${name(tC)}")
    val perExp     = FpTestConfig.scaled(64)
    val n          = FpTestConfig.scaled(20000)
    val corners    = boundary(tA).iterator.map(Seq(_))
    val everyExp   = for {
      s <- Iterator(0, 1)
      e <- Iterator.range(0, 1 << tA.expWidth)
      _ <- Iterator.range(0, perExp)
    } yield Seq(zero(tA, s) | (BigInt(e) << tA.sigWidth) | bits(r, tA.sigWidth))
    val crit       = criticalExponents(tC)
    val near       = Iterator.fill(n)(Seq(nearExponent(r, tA, crit(r.nextInt(crit.length)))))
    // the full mantissa range just below/above rounding ties into the output precision
    val tieOffsets = Iterator.fill(n) {
      val e    = crit(r.nextInt(crit.length)) + r.nextInt(5) - 2
      val keep = math.max(tC.sigWidth, 1)
      val tie  = (BigInt(1) << tA.sigWidth) | (BigInt(keep, r) << (tA.sigWidth - keep)) |
        (BigInt(1) << (tA.sigWidth - keep - 1))
      Seq(FpReference.encode(tA, r.nextInt(2), perturb(r, tie), e - tA.sigWidth))
    }
    corners ++ everyExp ++ near ++ tieOffsets
  }

  for ((tA, tC) <- pairs) {
    val label = s"${name(tA)} -> ${name(tC)}"
    it should s"match the reference model bit-exactly for $label" in {
      test(new FpConvert(tA, tC)).withAnnotations(FpTestConfig.annotations) { dut =>
        sweep(s"FpConvert $label", Seq(tA), tC, stimulus(tA, tC)) { v =>
          dut.io.in_a.poke(v(0).U)
          dut.io.out.peek().litValue
        } { v => FpReference.convert(tA, tC, v(0)) }
      }
    }
  }
}
