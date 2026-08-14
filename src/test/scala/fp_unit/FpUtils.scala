// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import scala.Float

import chisel3._

trait FpUtils {

  val BIAS32 = 127 // IEEE 754 bias for 32-bit float

  def expBias(expWidth: Int) = (1 << (expWidth - 1)) - 1

  /** Right-shifts the value by `shift`, rounding with RNE (round-to-nearest, ties-to-even); `shift <= 0` shifts left
    * (exact).
    */
  private def roundRNE(value: BigInt, shift: Int): BigInt =
    if (shift <= 0) value << -shift
    else {
      val kept     = value >> shift
      val roundBit = value.testBit(shift - 1)
      val sticky   = (value & ((BigInt(1) << (shift - 1)) - 1)) != 0
      kept + (if (roundBit && (sticky || kept.testBit(0))) 1 else 0)
    }

  /** Generalized helper function to encode Float as UInt. Decomposes the float32 exactly (no rounding).
    */
  private def floatToUInt(expWidth: Int, sigWidth: Int, value: Float): BigInt = {
    val bits32 = java.lang.Float.floatToIntBits(value)
    val sign   = (bits32 >>> 31) & 0x1
    val exp32  = (bits32 >>> 23) & 0xff
    val frac   = BigInt(bits32 & 0x7fffff)

    if (exp32 == 0xff) {
      // NaN -> canonical NaN (sign preserved); Infinity -> Infinity
      val fracBits = if (frac != 0) BigInt(1) << (sigWidth - 1) else BigInt(0)
      (BigInt(sign) << (expWidth + sigWidth)) | (BigInt((1 << expWidth) - 1) << sigWidth) | fracBits
    } else {
      // Exact decomposition: |value| = mant * 2^scale
      val (mant, scale) = if (exp32 == 0) (frac, 1 - BIAS32 - 23) else (frac | BigInt(0x800000), exp32 - BIAS32 - 23)
      roundExactRNE(expWidth, sigWidth, sign, mant, scale)
    }
  }

  /** Generalized helper function to encode Float as UInt, doesn't follow IEEE 754 standard but uses all bins for value
    * representation. This means there are no subnormals or special values.
    */
  private def floatToUInt_nonIEEE754(expWidth: Int, sigWidth: Int, value: Float): BigInt = {
    val expSigWidth = expWidth + sigWidth
    val totalWidth  = expSigWidth + 1

    // Convert to IEEE 754 32-bit float representation
    val bits32 = java.lang.Float.floatToIntBits(value)

    // Extract sign, exponent, and significand
    val sign     = (bits32 >>> 31) & 0x1
    val exponent = (bits32 >>> 23) & 0xff
    val frac     = BigInt(bits32 & 0x7fffff)

    // Re-normalize the exponent to fit expWidth
    val biasTarget   = expBias(expWidth)
    val maxExpTarget = (1 << expWidth) - 1
    val tentativeExp = (exponent - BIAS32 + biasTarget)

    val bitsTarget =
      if (exponent == 0xff && frac != 0) {
        // NaN: non-IEEE 754 format has no NaN encoding -> map to zero
        0

      } else if (exponent == 0xff && frac == 0) {
        // Infinity: saturate to max representable value (same as overflow)
        (sign << expSigWidth) | (maxExpTarget << sigWidth) | ((1 << sigWidth) - 1)

      } else if (exponent == 0 && frac == 0) {
        // True zero
        sign << expSigWidth

      } else if (tentativeExp > maxExpTarget) {
        // Overflow -> max value
        (sign << expSigWidth) | (maxExpTarget << sigWidth) | ((1 << sigWidth) - 1)

      } else if (tentativeExp < 0) {
        // No subnormals in non-IEEE 754 format -> return zero
        sign << expSigWidth

      } else if (exponent == 0 && tentativeExp > 0) {
        // From subnormal to normal
        throw new NotImplementedError("From subnormal to normal")

      } else {
        // Normal to normal
        // tentativeExp can be = 0 (which is a subnormal value in IEEE 754, but we now consider it normal)
        val rounded     = roundRNE(frac, 23 - sigWidth)
        val carry       = rounded.testBit(sigWidth) // rounded up past the mantissa: bump the exponent
        val roundedFrac = rounded & ((BigInt(1) << sigWidth) - 1)
        val expFinal    = if (carry) tentativeExp + 1 else tentativeExp
        if (expFinal > maxExpTarget) {
          // Overflow -> max value
          (sign << expSigWidth) | (maxExpTarget << sigWidth) | ((1 << sigWidth) - 1)
        } else {
          (sign << expSigWidth) | (expFinal << sigWidth) | roundedFrac.toLong
        }
      }

    BigInt(bitsTarget & ((1L << totalWidth) - 1)) // Mask to ensure valid bit-width
  }

  /** Generalized helper function to decode UInt to Float */
  private def uintToFloat(expWidth: Int, sigWidth: Int, bits: BigInt): Float = {
    // Extract sign, exponent, and significand
    val signSrc     = (bits >> (expWidth + sigWidth)) & 0x1
    val exponentSrc = (bits >> sigWidth) & ((1 << expWidth) - 1)
    val fracSrc     = bits & ((1 << sigWidth) - 1)

    val biasSrc   = expBias(expWidth)
    val maxExpSrc = (1 << expWidth) - 1
    val biasDiff  = BIAS32 - biasSrc

    val bits32 =
      if (exponentSrc == 0 && fracSrc == 0) {
        // True zero
        signSrc << 31

      } else if (exponentSrc == maxExpSrc) {
        // Inf or NaN
        val isNaN  = fracSrc != 0
        val frac32 = if (isNaN) 0x200000 else 0
        (signSrc << 31) | (0xff << 23) | frac32

      } else if (exponentSrc == 0 && biasDiff > 0) {
        // Subnormal to normal
        val leading    = Integer.numberOfLeadingZeros(fracSrc.toInt) - (32 - sigWidth)
        // Put MSB of source mantissa at implicit 1 position
        val normalized = (fracSrc << (leading + 1)) & ((1 << sigWidth) - 1)
        // Shift source mantissa into FP32 precision
        val frac32     = normalized << (23 - sigWidth)
        // Re-normalize exponent
        val exp32      = biasDiff - leading
        (signSrc << 31) | (exp32 << 23) | frac32

      } else if (exponentSrc == 0 && biasDiff <= 0) {
        // Subnormal to subnormal
        val subnormalFrac = fracSrc >> (-biasDiff + sigWidth - 23)
        (signSrc << 31) | subnormalFrac

      } else if (biasDiff < 0) {
        // Normal to subnormal
        throw new NotImplementedError("From normal to subnormal")

      } else {
        val frac32 = fracSrc.toInt << (23 - sigWidth)
        val exp32  = exponentSrc - biasSrc + BIAS32
        (signSrc << 31) | (exp32 << 23) | frac32
      }

    java.lang.Float.intBitsToFloat(bits32.toInt)
  }

  /** Generalized helper function to decode UInt to Float, doesn't follow IEEE 754 standard but uses all bins for value
    * representation. This means there are no subnormals or special values.
    */
  private def uintToFloat_nonIEEE754(expWidth: Int, sigWidth: Int, bits: BigInt): Float = {
    // Extract sign, exponent, and significand
    val signSrc     = (bits >> (expWidth + sigWidth)) & 0x1
    val exponentSrc = (bits >> sigWidth) & ((1 << expWidth) - 1)
    val fracSrc     = bits & ((1 << sigWidth) - 1)

    val biasSrc  = expBias(expWidth)
    val biasDiff = BIAS32 - biasSrc

    val bits32 =
      if (exponentSrc == 0 && fracSrc == 0) {
        // True zero
        signSrc << 31

      } else if (biasDiff < 0) {
        // Normal to subnormal
        throw new NotImplementedError("From normal to subnormal")

      } else {
        val frac32 = fracSrc.toInt << (23 - sigWidth)
        val exp32  = exponentSrc - biasSrc + BIAS32
        (signSrc << 31) | (exp32 << 23) | frac32
      }

    java.lang.Float.intBitsToFloat(bits32.toInt)
  }

  def floatToUInt(fpType: FpType, value: Float): BigInt =
    if (fpType.isIEEE754) floatToUInt(fpType.expWidth, fpType.sigWidth, value)
    else floatToUInt_nonIEEE754(fpType.expWidth, fpType.sigWidth, value)

  def uintToFloat(fpType: FpType, value: BigInt): Float =
    if (fpType.isIEEE754) uintToFloat(fpType.expWidth, fpType.sigWidth, value)
    else uintToFloat_nonIEEE754(fpType.expWidth, fpType.sigWidth, value)

  def uintToFloat(fpType: FpType, value: UInt):  Float = uintToFloat(fpType, value.litValue)
  def quantize(fpType:    FpType, value: Float): Float = uintToFloat(fpType, floatToUInt(fpType, value))

  /** Generate a true random value in the given FpType, where exponent and mantissa are sampled independently */
  def getTrueRandomValue(fpType: FpType)(implicit rng: Option[scala.util.Random] = None): Float = {
    val r          = rng.getOrElse(new scala.util.Random())
    val randomBits = BigInt(fpType.width, r)
    uintToFloat(fpType, randomBits)
  }

  /** Generated a bounded random float in the given FpType. Maximum value should be calculated such that a large number
    * of operations on randomly sampled numbers will not overflow with high probability
    */
  def genRandomValue(fpType: FpType)(implicit rng: Option[scala.util.Random] = None): Float = {
    val extraMargin = 0.2f
    val expMargin   = 0.1
    val maxExpWidth = math.max((expMargin * fpType.expWidth).toInt, 1)
    val maxExponent = ((1 << (maxExpWidth - 1)) - 1)
    val maxVal      = (1 << maxExponent).toFloat
    val r           = rng.getOrElse(new scala.util.Random())
    (2 * r.nextFloat() - 1f) * maxVal * extraMargin
    // Test with ints
    // ((2 * r.nextFloat() - 1f) * maxVal).toInt.toFloat
  }

  /** Process two floating point numbers in a given format by introducing the hardware limitations of this format */
  def fpOperationHardware(a: Float, b: Float, typeA: FpType, typeB: FpType, op: (Float, Float) => Float) = {
    val r = op(quantize(typeA, a), quantize(typeB, b))
    if (r == -0f) 0 else r
  }

  /** Multiplies two floating point numbers in a given format by introducing the hardware limitations of this format */
  def fpOperationHardware(a: Float, typeA: FpType, op: Float => Float) = op(quantize(typeA, a))

  def fpSqrtHardware(a: Float, typeA: FpType): Float = fpOperationHardware(a, typeA, (x: Float) => Math.sqrt(x).toFloat)

  def isNaN(bits: BigInt, fpType: FpType): Boolean = {
    val expMask = ((BigInt(1) << fpType.expWidth) - 1) << fpType.sigWidth
    val exp     = (bits & expMask) >> fpType.sigWidth
    val frac    = bits & ((BigInt(1) << fpType.sigWidth) - 1)
    exp == (BigInt(1) << fpType.expWidth) - 1 && frac != 0
  }

  def isZero(bits: BigInt, fpType: FpType): Boolean = bits == BigInt(1) << fpType.width - 1

  /** Returns true iff the hardware result a (as UInt) correctly represents the float. The result is allowed to differ
    * in `lsbTolerance` LSB bits, as a result from rounding errors propagated through operations.
    *
    * The software reference uses RNE (Round to Nearest, ties to Even). -0 and +0 are also accepted as equal.
    */
  def fpEqualsHardware(expected: Float, from_hw: UInt, typeB: FpType, lsbTolerance: Int = 0) = {
    val expected_bigint = floatToUInt(typeB, expected)
    val from_hw_bigint  = from_hw.litValue
    val eqZero          = expected_bigint == 0          && isZero(from_hw_bigint, typeB)
    val eqNaN           = isNaN(expected_bigint, typeB) && isNaN(from_hw_bigint, typeB)
    (from_hw_bigint - expected_bigint).abs <= ((BigInt(1) << lsbTolerance) - 1) || eqZero || eqNaN
  }

  /** Define operator symbol for mulFpHardware. Signature:  ((Float, FpType), (Float, FpType)) => Float */
  implicit class FpHardwareOps(a: (Float, FpType)) {
    def *(b: (Float, FpType)): Float = fpOperationHardware(a._1, b._1, a._2, b._2, _ * _)
    def +(b: (Float, FpType)): Float = fpOperationHardware(a._1, b._1, a._2, b._2, _ + _)
    def -(b: (Float, FpType)): Float = fpOperationHardware(a._1, b._1, a._2, b._2, _ - _)
    def /(b: (Float, FpType)): Float = fpOperationHardware(a._1, b._1, a._2, b._2, _ / _)

    /** Not to be confused with the Chisel3 === operator */
    def ===(b: UInt): Boolean = fpEqualsHardware(a._1, b, a._2, lsbTolerance = 0)
    def =~=(b: UInt): Boolean = fpEqualsHardware(a._1, b, a._2, lsbTolerance = 1)
  }

  def uintToStr(bits: BigInt, fpType: FpType): String =
    bits.toString(2).reverse.padTo(fpType.width, '0').reverse.grouped(4).mkString("_")

  /** Exact fused multiply-add: computes a*b + c exactly (BigInt) and rounds once with RNE to `inTypeC`.
    *
    * This is the IEEE-correct single-rounding reference. Inputs are interpreted with IEEE semantics, so non-IEEE types
    * (FP8_ALT) are not supported here. Verified in FpGoldenVectorTest.
    */
  def fmaExact(a: Float, b: Float, c: Float, inTypeA: FpType, inTypeB: FpType, inTypeC: FpType): Float = {
    def out(bits: BigInt): Float = uintToFloat(inTypeC, bits)
    val da = decodeIEEEBits(inTypeA, floatToUInt(inTypeA, a))
    val db = decodeIEEEBits(inTypeB, floatToUInt(inTypeB, b))
    val dc = decodeIEEEBits(inTypeC, floatToUInt(inTypeC, c))
    def isInf(x:   Either[String, _]) = x.left.toOption.exists(_.endsWith("inf"))
    def infSign(x: Either[String, _]) = if (x.left.toOption.contains("-inf")) 1 else 0
    if (da == Left("nan") || db == Left("nan") || dc == Left("nan")) return out(canonicalNaNBits(inTypeC))
    val aZero = da.exists(_._2 == 0); val bZero = db.exists(_._2 == 0)
    if ((isInf(da) && bZero) || (aZero && isInf(db))) return out(canonicalNaNBits(inTypeC))
    val prodSign     =
      (if (isInf(da)) infSign(da) else da.toOption.get._1) ^ (if (isInf(db)) infSign(db) else db.toOption.get._1)
    if (isInf(da) || isInf(db)) {
      if (isInf(dc) && infSign(dc) != prodSign) return out(canonicalNaNBits(inTypeC))
      return out(infinityBits(inTypeC, prodSign))
    }
    if (isInf(dc)) return out(infinityBits(inTypeC, infSign(dc)))
    val (_, ma, ea)  = da.toOption.get
    val (_, mb, eb)  = db.toOption.get
    val (sc, mc, ec) = dc.toOption.get
    val pm           = ma * mb
    val pe           = ea + eb
    // Both product and addend zero: sign follows the IEEE addition-of-zeros rule (RNE)
    if (pm == 0 && mc == 0)
      return out(BigInt(if (prodSign == 1 && sc == 1) 1 else 0) << (inTypeC.expWidth + inTypeC.sigWidth))
    val e0 = math.min(pe, ec)
    val sum = (if (prodSign == 1) -(pm << (pe - e0)) else pm << (pe - e0)) +
      (if (sc == 1) -(mc << (ec - e0)) else mc << (ec - e0))
    if (sum == 0) return 0.0f // exact cancellation -> +0 in RNE
    out(roundExactRNE(inTypeC.expWidth, inTypeC.sigWidth, if (sum < 0) 1 else 0, sum.abs, e0))
  }

  /** Exact multiply: a*b rounded once with RNE to `typeC`, with IEEE multiply semantics for the sign of zero (a zero or
    * underflowed result carries the XOR of the operand signs, unlike an FMA with a +0 addend). Verified in
    * FpGoldenVectorTest.
    */
  def mulExact(a: Float, b: Float, typeA: FpType, typeB: FpType, typeC: FpType): Float = {
    val r       = fmaExact(a, b, 0.0f, typeA, typeB, typeC)
    val signXor =
      ((floatToUInt(typeA, a) >> (typeA.width - 1)) ^ (floatToUInt(typeB, b) >> (typeB.width - 1))) == 1
    if (r == 0.0f && !r.isNaN && signXor) -0.0f else r
  }

  /** Software model of the hardware FMA for any FpType combination: dispatches to the exact IEEE single-rounding
    * reference (fmaExact) when all types are IEEE 754, and to a float-based fallback for non-IEEE types (FP8_ALT).
    */
  def fmaHardware(a: Float, b: Float, c: Float, typeA: FpType, typeB: FpType, typeC: FpType): Float =
    if (typeA.isIEEE754 && typeB.isIEEE754 && typeC.isIEEE754) fmaExact(a, b, c, typeA, typeB, typeC)
    else {
      val r = quantize(typeA, a) * quantize(typeB, b) + quantize(typeC, c)
      if (r == -0f) 0f else r
    }

  /** Software model of the hardware multiplier for any FpType combination: mulExact for IEEE 754 types, float-based
    * fallback for non-IEEE types (FP8_ALT).
    */
  def mulHardware(a: Float, b: Float, typeA: FpType, typeB: FpType, typeC: FpType): Float =
    if (typeA.isIEEE754 && typeB.isIEEE754 && typeC.isIEEE754) mulExact(a, b, typeA, typeB, typeC)
    else (a, typeA) * (b, typeB)

  /** Exact add: a+b rounded once with RNE to `typeC`, with IEEE addition semantics for the sign of zero. IEEE 754
    * types only. Verified in FpGoldenVectorTest.
    */
  def addExact(a: Float, b: Float, typeA: FpType, typeB: FpType, typeC: FpType): Float = {
    require(typeA.isIEEE754 && typeB.isIEEE754 && typeC.isIEEE754, "addExact only supports IEEE 754 types")
    def out(bits: BigInt): Float = uintToFloat(typeC, bits)
    val da = decodeIEEEBits(typeA, floatToUInt(typeA, a))
    val db = decodeIEEEBits(typeB, floatToUInt(typeB, b))
    def isInf(x:   Either[String, _]) = x.left.toOption.exists(_.endsWith("inf"))
    def infSign(x: Either[String, _]) = if (x.left.toOption.contains("-inf")) 1 else 0
    if (da == Left("nan") || db == Left("nan")) return out(canonicalNaNBits(typeC))
    if (isInf(da) || isInf(db)) {
      if (isInf(da) && isInf(db) && infSign(da) != infSign(db)) return out(canonicalNaNBits(typeC))
      return out(infinityBits(typeC, if (isInf(da)) infSign(da) else infSign(db)))
    }
    val (sa, ma, ea) = da.toOption.get
    val (sb, mb, eb) = db.toOption.get
    // Both operands zero: sign follows the IEEE addition-of-zeros rule (RNE)
    if (ma == 0 && mb == 0) return out(BigInt(if (sa == 1 && sb == 1) 1 else 0) << (typeC.expWidth + typeC.sigWidth))
    val e0  = math.min(ea, eb)
    val sum = (if (sa == 1) -(ma << (ea - e0)) else ma << (ea - e0)) +
      (if (sb == 1) -(mb << (eb - e0)) else mb << (eb - e0))
    if (sum == 0) return 0.0f // exact cancellation -> +0 in RNE
    out(roundExactRNE(typeC.expWidth, typeC.sigWidth, if (sum < 0) 1 else 0, sum.abs, e0))
  }

  /** Software model of the hardware adder for any FpType combination: addExact for IEEE 754 types, float-based fallback for non-IEEE types (FP8_ALT).
    */
  def addHardware(a: Float, b: Float, typeA: FpType, typeB: FpType, typeC: FpType): Float =
    if (typeA.isIEEE754 && typeB.isIEEE754 && typeC.isIEEE754) addExact(a, b, typeA, typeB, typeC)
    else (a, typeA) + (b, typeB)

  /** Same-format add, the common case in accelerator datapaths. */
  def addHardware(a: Float, b: Float, fpType: FpType): Float = addHardware(a, b, fpType, fpType, fpType)


  /** Decode IEEE bits to (sign, significand, scale) with value = (-1)^sign * mant * 2^scale, or Left(special). */
  private def decodeIEEEBits(t: FpType, bits: BigInt): Either[String, (Int, BigInt, Int)] = {
    val EXP     = t.expWidth; val MAN = t.sigWidth
    val s       = ((bits >> (EXP + MAN)) & 1).toInt
    val e       = ((bits >> MAN) & ((1 << EXP) - 1)).toInt
    val m       = bits & ((BigInt(1) << MAN) - 1)
    val allOnes = (1 << EXP) - 1
    if (e == allOnes && m != 0) Left("nan")
    else if (e == allOnes) Left(if (s == 1) "-inf" else "+inf")
    else if (e == 0) Right((s, m, 1 - expBias(EXP) - MAN))
    else Right((s, m | (BigInt(1) << MAN), e - expBias(EXP) - MAN))
  }

  private def canonicalNaNBits(t: FpType): BigInt =
    (BigInt((1 << t.expWidth) - 1) << t.sigWidth) | (BigInt(1) << (t.sigWidth - 1))

  private def infinityBits(t: FpType, sign: Int): BigInt =
    (BigInt(sign) << (t.expWidth + t.sigWidth)) | (BigInt((1 << t.expWidth) - 1) << t.sigWidth)

  /** RNE-round the exact value (-1)^sign * mant * 2^scale to IEEE bits (single rounding, gradual underflow, overflow to
    * infinity). Together with roundRNE this is the single source of truth for all IEEE rounding: floatToUInt (and thus
    * quantize), fmaExact, mulExact, and roundDoubleToFp all delegate here.
    */
  def roundExactRNE(expWidth: Int, sigWidth: Int, sign: Int, mant: BigInt, scale: Int): BigInt = {
    val EXP      = expWidth; val MAN = sigWidth; val BIAS = expBias(EXP)
    val signBits = BigInt(sign) << (EXP + MAN)
    if (mant == 0) return signBits
    val unbExp   = scale + mant.bitLength - 1 // value in [2^unbExp, 2^(unbExp+1))
    val lsbW     = math.max(unbExp - MAN, 1 - BIAS - MAN) // weight of the target LSB
    val shift    = lsbW - scale
    val m2       = roundRNE(mant, shift)
    if (m2 == 0) return signBits // rounded to zero
    val nb     = m2.bitLength
    val biased = lsbW + nb - 1 + BIAS
    if (biased <= 0) signBits | m2 // subnormal (m2 == 2^MAN encodes min normal)
    else if (biased >= (1 << EXP) - 1) signBits | (BigInt((1 << EXP) - 1) << MAN) // overflow -> infinity
    else signBits | (BigInt(biased) << MAN) | ((m2 & ((BigInt(1) << MAN) - 1)) << (MAN - (nb - 1)))
  }

  /** Round a double to `fpType` with RNE (single rounding). Decomposes the double exactly and delegates to
    * roundExactRNE. Exact-zero doubles return +0, matching the HW adders, which emit +0 for exact-zero sums.
    */
  def roundDoubleToFp(fpType: FpType, x: Double): Float = {
    if (x.isNaN) return Float.NaN
    if (x == 0.0) return uintToFloat(fpType, BigInt(0)) // +0 (HW emits +0 for exact-zero adds)
    val bits = java.lang.Double.doubleToLongBits(x)
    val sign = ((bits >>> 63) & 1).toInt
    val e    = ((bits >>> 52) & 0x7ff).toInt
    val frac = BigInt(bits) & ((BigInt(1) << 52) - 1)
    if (e == 0x7ff) return if (sign == 1) Float.NegativeInfinity else Float.PositiveInfinity // inf (NaN handled above)
    val (mant, scale) = if (e == 0) (frac, 1 - 1023 - 52) else (frac | (BigInt(1) << 52), e - 1023 - 52)
    uintToFloat(fpType, roundExactRNE(fpType.expWidth, fpType.sigWidth, sign, mant, scale))
  }

}
