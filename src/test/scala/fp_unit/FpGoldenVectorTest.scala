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

/** Checks the software reference models against MPFR golden vectors (`pixi run golden`). */
class FpGoldenVectorTest extends AnyFlatSpec with FpUtils {
  behavior of "The software reference models (vs. MPFR golden vectors)"

  private val required = sys.env.get("FP_GOLDEN_REQUIRED").exists(v => v == "1" || v.equalsIgnoreCase("true"))

  /** Vectors live in generated/fp_golden; also resolve them when the tests run from a parent project. */
  private def loadVectors(file: String): Iterator[Array[BigInt]] = {
    val candidates = Seq("generated/fp_golden", "chisel-float/generated/fp_golden", "../generated/fp_golden")
    val found      = candidates.map(d => new File(d, file)).find(_.isFile)
    if (found.isEmpty) {
      val msg = s"missing generated/fp_golden/$file: run `pixi run golden`"
      if (required) fail(msg) else cancel(msg)
    }
    Source
      .fromInputStream(new GZIPInputStream(new FileInputStream(found.get)))
      .getLines()
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map(_.split(" ").map(BigInt(_, 16)))
  }

  private def typeName(t: FpType) = t.getClass.getSimpleName.stripSuffix("$")

  /** Checks every vector (operands..., expected) against each of the given models. */
  private def check(file: String, outType: FpType, models: (String, Seq[BigInt] => BigInt)*): Unit = {
    var total = 0
    val bad   = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val shown = scala.collection.mutable.ArrayBuffer.empty[String]
    for (vec <- loadVectors(file)) {
      val ops      = vec.init.toSeq
      val expected = vec.last
      total += 1
      for ((model, f) <- models) {
        val got = f(ops)
        if (got != expected) {
          bad(model) += 1
          if (shown.size < 20)
            shown += s"$model ${ops.map(_.toString(16)).mkString(" ")}: mpfr=${FpReference.describe(outType, expected)} " +
              s"got=${FpReference.describe(outType, got)}"
        }
      }
    }
    val summary = models.map { case (m, _) => s"$m: ${bad(m)} / $total mismatches" }.mkString(", ")
    println(s"$file: $summary")
    info(summary)
    assert(total > 0, s"$file contains no vectors: regenerate it with `pixi run golden`")
    assert(bad.values.sum == 0, (summary +: shown).mkString("\n"))
  }

  private def bitsOf(t: FpType, f: Float):  BigInt = floatToUInt(t, f)
  private def valOf(t:  FpType, b: BigInt): Float  = uintToFloat(t, b)

  val fmaCombos = Seq(
    (BF16, BF16, BF16),
    (FP16, FP16, FP16),
    (FP32, FP32, FP32),
    (FP8, FP8, FP8),
    (BF16, FP16, BF16),
    (FP16, BF16, FP16),
    (FP16, FP16, FP32),
    (BF16, BF16, FP32),
    (FP8, FP16, FP16),
    (FP32, FP32, FP8)
  )

  val addCombos = Seq(
    (BF16, BF16, BF16),
    (FP16, FP16, FP16),
    (FP32, FP32, FP32),
    (FP8, FP8, FP8),
    (FP16, FP32, FP32),
    (BF16, BF16, FP32),
    (FP16, FP16, FP32),
    (FP16, FP32, FP16), // narrowing
    (FP32, FP16, BF16), // narrowing
    (FP32, FP32, FP16), // narrowing
    (FP16, FP16, FP8)   // narrowing
  )

  val ieeeTypes = Seq(FP32, FP16, BF16, FP8)

  for ((tA, tB, tC) <- fmaCombos) {
    val n = s"${typeName(tA)}_${typeName(tB)}_${typeName(tC)}"
    it should s"match MPFR for fma ${typeName(tA)}*${typeName(tB)}+${typeName(tC)}" in {
      check(
        s"fma_$n.txt.gz",
        tC,
        "FpReference.fma"  -> (v => FpReference.fma(tA, tB, tC, v(0), v(1), v(2))),
        "FpUtils.fmaExact" -> (v => bitsOf(tC, fmaExact(valOf(tA, v(0)), valOf(tB, v(1)), valOf(tC, v(2)), tA, tB, tC)))
      )
    }
    it should s"match MPFR for mul ${typeName(tA)}*${typeName(tB)}->${typeName(tC)}" in {
      check(
        s"mul_$n.txt.gz",
        tC,
        "FpReference.mul"  -> (v => FpReference.mul(tA, tB, tC, v(0), v(1))),
        "FpUtils.mulExact" -> (v => bitsOf(tC, mulExact(valOf(tA, v(0)), valOf(tB, v(1)), tA, tB, tC)))
      )
    }
  }

  for ((tA, tB, tC) <- addCombos) {
    val n = s"${typeName(tA)}_${typeName(tB)}_${typeName(tC)}"
    it should s"match MPFR for add ${typeName(tA)}+${typeName(tB)}->${typeName(tC)}" in {
      check(
        s"add_$n.txt.gz",
        tC,
        "FpReference.add"  -> (v => FpReference.add(tA, tB, tC, v(0), v(1))),
        "FpUtils.addExact" -> (v => bitsOf(tC, addExact(valOf(tA, v(0)), valOf(tB, v(1)), tA, tB, tC)))
      )
    }
  }

  for {
    tA <- ieeeTypes
    tC <- ieeeTypes if tA != tC
  } {
    it should s"match MPFR for convert ${typeName(tA)}->${typeName(tC)}" in {
      check(
        s"cvt_${typeName(tA)}_${typeName(tC)}.txt.gz",
        tC,
        "FpReference.convert" -> (v => FpReference.convert(tA, tC, v(0))),
        "FpUtils.floatToUInt" -> (v => bitsOf(tC, valOf(tA, v(0))))
      )
    }
  }

  for (t <- Seq(BF16, FP16, FP32)) {
    it should s"match MPFR for div and sqrt in ${typeName(t)}" in {
      check(s"div_${typeName(t)}.txt.gz", t, "FpReference.div"   -> (v => FpReference.div(t, v(0), v(1))))
      check(s"sqrt_${typeName(t)}.txt.gz", t, "FpReference.sqrt" -> (v => FpReference.sqrt(t, v(0))))
    }
  }
}
