// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51
//
// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

import scala.io.Source

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Verifies the software references fmaExact and mulExact against golden vectors produced  by MPFR (an external,
  * independently validated IEEE-754 oracle)
  */
class FpGoldenVectorTest extends AnyFlatSpec with Matchers with FpUtils {
  behavior of "fmaExact/mulExact vs MPFR golden vectors"

  private def isNaNBits(t: FpType, bits: BigInt): Boolean = {
    val expAllOnes = (1 << t.expWidth) - 1
    ((bits >> t.sigWidth) & expAllOnes) == expAllOnes && (bits & ((BigInt(1) << t.sigWidth) - 1)) != 0
  }

  /** Vectors live in generated/fp_golden. Resolve the root regardless of whether tests run from chisel-float itself or
    * from a parent project.
    */
  private def loadVectors(name: String): Iterator[Array[BigInt]] = {
    val candidates = Seq("generated/fp_golden", "chisel-float/generated/fp_golden", "../generated/fp_golden")
    val file       = candidates.map(d => new File(d, name)).find(_.isFile).getOrElse {
      cancel(s"Missing generated/fp_golden/$name. run: python3 src/test/resources/gen_fp_golden_vectors.py")
    }
    Source
      .fromInputStream(new GZIPInputStream(new FileInputStream(file)))
      .getLines()
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map(_.split(" ").map(BigInt(_, 16)))
  }

  /** Compare `result` (already in tC bits) against expected bits; NaN compared as a class. */
  private def matches(tC: FpType, resultBits: BigInt, expectedBits: BigInt): Boolean =
    if (isNaNBits(tC, expectedBits)) isNaNBits(tC, resultBits) else resultBits == expectedBits

  private def checkFma(tA: FpType, tB: FpType, tC: FpType, name: String): Unit = {
    var total = 0; var mism = 0; var shown = 0
    for (Array(aB, bB, cB, expB) <- loadVectors(s"fma_${name}.txt.gz")) {
      val a    = uintToFloat(tA, aB); val b = uintToFloat(tB, bB); val c = uintToFloat(tC, cB)
      val resB = floatToUInt(tC, fmaExact(a, b, c, tA, tB, tC))
      total += 1
      if (!matches(tC, resB, expB)) {
        mism += 1
        if (shown < 20) {
          println(
            f"MISMATCH $name a=0x$aB%04x b=0x$bB%04x c=0x$cB%04x mpfr=0x$expB%04x got=0x$resB%04x " +
              f"($a%.8g * $b%.8g + $c%.8g)"
          )
          shown += 1
        }
      }
    }
    println(s"fmaExact $name: $mism / $total mismatches vs MPFR")
    assert(mism == 0)
  }

  private def checkMul(tA: FpType, tB: FpType, tC: FpType, name: String): Unit = {
    var total = 0; var mism = 0; var shown = 0
    for (Array(aB, bB, expB) <- loadVectors(s"mul_${name}.txt.gz")) {
      val a    = uintToFloat(tA, aB); val b = uintToFloat(tB, bB)
      val resB = floatToUInt(tC, mulExact(a, b, tA, tB, tC))
      total += 1
      if (!matches(tC, resB, expB)) {
        mism += 1
        if (shown < 20) {
          println(f"MISMATCH $name a=0x$aB%04x b=0x$bB%04x mpfr=0x$expB%04x got=0x$resB%04x ($a%.8g * $b%.8g)")
          shown += 1
        }
      }
    }
    println(s"mulExact $name: $mism / $total mismatches vs MPFR")
    assert(mism == 0)
  }

  val combos = Seq(
    (BF16, BF16, BF16),
    (FP16, FP16, FP16),
    (FP32, FP32, FP32),
    (BF16, FP16, BF16),
    (FP16, BF16, FP16),
    (FP16, FP16, FP32),
    (BF16, BF16, FP32)
  )

  def name(t: FpType): String = t.getClass.getSimpleName.stripSuffix("$")

  for ((tA, tB, tC) <- combos) {
    val n = s"${name(tA)}_${name(tB)}_${name(tC)}"
    it should s"match MPFR for fmaExact ${name(tA)}*${name(tB)}+${name(tC)}" in checkFma(tA, tB, tC, n)
    it should s"match MPFR for mulExact ${name(tA)}*${name(tB)}->${name(tC)}" in checkMul(tA, tB, tC, n)
  }
}
