// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import org.scalatest.flatspec.AnyFlatSpec

import FpReference._

/** Seeded stimulus generators; operands are raw bit patterns. */
object FpStimulus {

  /** TestFloat-style boundary values and every special value, of both signs. */
  def boundary(t: FpType): Seq[BigInt] = {
    val man  = t.sigWidth
    val ones = mask(man)
    val half = BigInt(1) << (man - 1)
    def enc(e: Int, m: BigInt) = (BigInt(e) << man) | m
    val one    = enc(bias(t), 0)
    val mags   =
      if (t.isIEEE754) {
        val emax = maxExpField(t)
        Seq(
          BigInt(0),
          BigInt(1),
          BigInt(2),
          BigInt(3),
          half,
          ones,                   // subnormals: min, min+1ulp, .., max
          enc(1, 0),
          enc(1, 1),
          enc(1, ones),           // min normal, +1ulp, just below 2*min normal
          enc(bias(t) - 1, ones), // just below 1.0
          one,
          one | 1,
          one | half,
          one | half | 1,         // 1.0, 1.0+ulp, 1.5, 1.5+ulp
          enc(emax - 1, 0),
          enc(emax - 1, 1),
          enc(emax - 1, ones - 1),
          enc(emax - 1, ones),    // near overflow, max normal
          enc(emax, 0)            // infinity
        )
      } else {
        val emax = maxExpField(t)
        Seq(
          BigInt(0),      // zero
          BigInt(1),
          BigInt(2),
          ones,           // smallest magnitudes (exponent 0)
          enc(1, 0),
          enc(1, 1),
          enc(bias(t) - 1, ones),
          one,
          one | 1,
          one | half,
          enc(emax - 1, ones),
          enc(emax, 0),
          enc(emax, ones - 1),
          enc(emax, ones) // largest magnitudes
        )
      }
    val signed = for {
      s <- Seq(0, 1)
      m <- mags.distinct
    } yield zero(t, s) | m
    val nans   =
      if (!t.isIEEE754) Nil
      else {
        val e = enc(maxExpField(t), 0)
        Seq(e | half, e | half | 1, e | 1, e | ones).flatMap(n => Seq(n, zero(t, 1) | n)) // qNaN, payloads, sNaN
      }
    (signed ++ nans).distinct
  }

  /** Every combination of the given operand widths (only for small total widths). */
  def exhaustive(widths: Int*): Iterator[Seq[BigInt]] = {
    require(widths.sum <= 24, "exhaustive sweep too large")
    val total = 1L << widths.sum
    Iterator.range(0L, total).map { i =>
      var rest = i
      widths.reverse.map { w =>
        val v = rest & ((1L << w) - 1); rest >>= w; BigInt(v)
      }.reverse
    }
  }

  def cross(sets: Seq[BigInt]*): Iterator[Seq[BigInt]] =
    sets.foldLeft(Iterator(Seq.empty[BigInt])) { (acc, set) => acc.flatMap(prefix => set.iterator.map(prefix :+ _)) }

  def bits(rng: Random, width: Int): BigInt = BigInt(width, rng)

  /** Random finite value in [2^exp, 2^(exp+1)) (before rounding into `t`) with a random sign and mantissa. */
  def nearExponent(rng: Random, t: FpType, exp: Int): BigInt = {
    val mant = (BigInt(1) << t.sigWidth) | BigInt(t.sigWidth, rng)
    encode(t, rng.nextInt(2), mant, exp - t.sigWidth)
  }

  /** Unbiased exponents near underflow, the subnormal/normal transition and overflow. */
  def criticalExponents(t: FpType): Seq[Int] = {
    val minNormal = if (t.isIEEE754) 1 - bias(t) else -bias(t)
    val minSub    = if (t.isIEEE754) minNormal - t.sigWidth else minNormal
    val maxExp    = if (t.isIEEE754) maxExpField(t) - 1 - bias(t) else maxExpField(t) - bias(t)
    Seq(minSub - 2, minSub - 1, minSub, minSub + 1, minNormal - 1, minNormal, maxExp, maxExp + 1)
  }

  /** Unbiased exponent range of finite values of `t` (subnormals included). */
  def exponentRange(t: FpType): (Int, Int) = {
    val c = criticalExponents(t)
    (c(2), c(6))
  }

  /** Flip up to `maxUlps` low-order bits: a value within a few ulps of the input (sign kept). */
  def perturb(rng: Random, bits: BigInt, maxUlps: Int = 3): BigInt =
    if (rng.nextBoolean()) bits else bits ^ BigInt(1 + rng.nextInt(maxUlps))

  def negate(t: FpType, bits: BigInt): BigInt = bits ^ zero(t, 1)
}

/** Bit-exact sweep runner shared by the unit tests. */
trait FpTestHarness { this: AnyFlatSpec =>

  def rng(salt: String): Random = new Random(FpTestConfig.seed ^ salt.hashCode.toLong)

  def name(t: DataType): String =
    t match {
      case f: FpType  => f.getClass.getSimpleName.stripSuffix("$")
      case i: IntType => s"Int${i.width}"
    }

  private def show(t: DataType, bits: BigInt): String =
    t match {
      case f: FpType  => describe(f, bits)
      case i: IntType => s"${FpReference.intValue(i.width, bits)}"
    }

  /** Drives every vector through the DUT and compares the output with `ref` bit for bit. */
  def sweep(label: String, inTypes: Seq[DataType], outType: FpType, vectors: Iterator[Seq[BigInt]])(
    dut: Seq[BigInt] => BigInt
  )(ref: Seq[BigInt] => BigInt): Unit = {
    var total    = 0L
    var bad      = 0L
    val examples = ArrayBuffer.empty[String]
    vectors.foreach { v =>
      val expected = ref(v)
      val got      = dut(v)
      total += 1
      if (got != expected) {
        bad += 1
        if (examples.size < 12) {
          val ops = v.zip(inTypes).map { case (b, t) => show(t, b) }.mkString(", ")
          examples += s"  ($ops) -> expected ${show(outType, expected)}, got ${show(outType, got)}"
        }
      }
    }
    val summary  = s"$label: $bad / $total mismatches (FP_TEST_SEED=${FpTestConfig.seed})"
    println(summary)
    info(summary)
    if (bad > 0) fail((summary +: examples).mkString("\n"))
  }

  /** Elaborating `gen` must fail, with `fragment` in one of the messages of the exception chain. */
  def assertElaborationFails(gen: => chisel3.RawModule, fragment: String): Unit = {
    val e        = intercept[Throwable](circt.stage.ChiselStage.emitCHIRRTL(gen))
    val messages = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).map(t => String.valueOf(t.getMessage)).toSeq
    assert(messages.exists(_.contains(fragment)), s"expected '$fragment' in: ${messages.mkString(" / ")}")
  }
}
