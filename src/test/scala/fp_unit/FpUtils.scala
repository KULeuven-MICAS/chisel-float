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

  /** Right-shifts the value by given amounts, rounding the result with RNE (round-to-nearest, tie-on-even).
    * @returns
    *   (shifted and rounded value, carry out)
    */
  def round(value: BigInt, shift: Int, targetWidth: Int): (BigInt, Boolean) = {
    if (shift <= 0) { return (value << -shift, false) }

    val roundPos   = shift - 1
    val roundBit   = if (value.testBit(roundPos)) 1 else 0
    val stickyMask = (BigInt(1) << roundPos) - 1
    val stickyBit  = if ((value & stickyMask) != 0) 1 else 0

    val truncated = value >> shift
    val lsb       = if (truncated.testBit(0)) 1 else 0

    val roundUp = roundBit == 1 && (stickyBit == 1 || lsb == 1)

    if (roundUp) {
      val result = truncated + 1
      // Check for overflow. If the bit at targetWidth is set, it's a carry-out.
      if (result.testBit(targetWidth)) {
        (result & ((BigInt(1) << targetWidth) - 1), true)
      } else {
        (result, false)
      }
    } else {
      (truncated, false)
    }
  }

  /** Generalized helper function to encode Float as UInt */
  def floatToUInt(expWidth: Int, sigWidth: Int, value: Float): BigInt = {
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
        // Canonical NaN: all exponent bits 1, MSB of fraction 1, rest 0
        val expAllOnes = (1 << expWidth) - 1
        val fracMSB    = 1 << (sigWidth - 1)
        (sign << expSigWidth) | (expAllOnes << sigWidth) | fracMSB

      } else if (exponent == 0xff && frac == 0) {
        // Infinity: all exponent bits 1, fraction 0
        val expAllOnes = (1 << expWidth) - 1
        (sign << expSigWidth) | (expAllOnes << sigWidth)

      } else if (exponent == 0 && frac == 0) {
        // True zero
        sign << expSigWidth

      } else if (tentativeExp > maxExpTarget) {
        // Overflow -> Inf (all 1's)
        (sign << expSigWidth) | ((1 << expSigWidth) - 1)

      } else if (exponent == 0 && tentativeExp <= 0) {
        // From subnormal to subnormal
        val shift                  = -tentativeExp + 23 - sigWidth
        val (subnormalFrac, carry) = round(frac, shift, sigWidth)
        if (carry) {
          // Rounded up to the smallest normal number
          (sign << expSigWidth) | (1 << sigWidth)
        } else {
          (sign << expSigWidth) | subnormalFrac.toLong
        }

      } else if (exponent == 0 && tentativeExp > 0) {
        // From subnormal to normal
        throw new NotImplementedError("From subnormal to normal")

      } else if (exponent > 0 && tentativeExp <= 0) {
        // From normal to subnormal
        val mantissa32             = 0x800000 | frac
        val shift                  = (1 - tentativeExp) + (23 - sigWidth)
        val (subnormalFrac, carry) = round(mantissa32, shift, sigWidth)
        if (carry) {
          // Rounded up to the smallest normal number
          (sign << expSigWidth) | (1 << sigWidth)
        } else {
          (sign << expSigWidth) | subnormalFrac.toLong
        }

      } else {
        // Normal to normal
        val shift                = 23 - sigWidth
        val (roundedFrac, carry) = round(frac, shift, sigWidth)
        val expFinal             = if (carry) tentativeExp + 1 else tentativeExp
        if (expFinal >= maxExpTarget) {
          // Overflow to infinity
          val expAllOnes = (1 << expWidth) - 1
          (sign << expSigWidth) | (expAllOnes << sigWidth)
        } else {
          (sign << expSigWidth) | (expFinal << sigWidth) | roundedFrac.toLong
        }
      }

    BigInt(bitsTarget & ((1L << totalWidth) - 1)) // Mask to ensure valid bit-width
  }

  /** Generalized helper function to decode UInt to Float */
  def uintToFloat(expWidth: Int, sigWidth: Int, bits: BigInt): Float = {
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

  def floatToUInt(fpType: FpType, value: Float):  BigInt = floatToUInt(fpType.expWidth, fpType.sigWidth, value)
  def uintToFloat(fpType: FpType, value: BigInt): Float  = uintToFloat(fpType.expWidth, fpType.sigWidth, value)
  def uintToFloat(fpType: FpType, value: UInt):   Float  = uintToFloat(fpType.expWidth, fpType.sigWidth, value.litValue)
  def quantize(fpType:    FpType, value: Float):  Float  = uintToFloat(fpType, floatToUInt(fpType, value))

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
    val expMargin   = 0.4
    val maxExpWidth = math.max((expMargin * fpType.expWidth).toInt, 1)
    val maxExponent = ((1 << (maxExpWidth - 1)) - 1)
    val maxVal      = (1 << maxExponent).toFloat
    val r           = rng.getOrElse(new scala.util.Random())
    (2 * r.nextFloat() - 1f) * maxVal
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

  /** Computes the exponential function using the Schraudolph method.
    *
    * This function should have the exact same functionality as the corresponding CUDA kernel.
    *
    * @param fmaIsAccurate
    *   If true, `scale` is computed as a single FMA operation, without recasting the intermediate result (from mul to
    *   add) to FPTYPE. On GPU, this is true.
    */
  def fastExp(x: Float, fpType: FpType = FP16, fmaIsAccurate: Boolean = true): Float = {
    require(x <= 11.0f, "fastExp only supports inputs <= 11.0f")
    require(fpType == FP16, "FastExp only supports FP16")

    val INV_LN2     = 1.4427f // Hardwired value
    val TUNING_BIAS = 0f

    val fpExpBias = expBias(fpType.expWidth)
    val scaleBias = quantize(fpType, fpExpBias - TUNING_BIAS) // Hardwired value

    val scaleMul = (INV_LN2, fpType) * (x, fpType)
    val addInput = if (fmaIsAccurate) scaleMul else quantize(fpType, scaleMul)
    val scale    = quantize(fpType, addInput + scaleBias)

    // Set subnormal values are clipped to zero
    if (scale < 0) return 0f

    val scaleBits     = floatToUInt(fpType, scale)
    val scaleExp      = (scaleBits >> fpType.sigWidth) & ((1 << fpType.expWidth) - 1)
    val scaleFrac     = scaleBits & ((1 << fpType.sigWidth) - 1)
    val scaleMantissa = 1 << fpType.sigWidth | scaleFrac

    val shift           = scaleExp.toInt - fpExpBias
    val mantissaShifted =
      if (shift < 0) (scaleMantissa >> -shift) else (scaleMantissa << shift)

    uintToFloat(fpType, mantissaShifted)
  }

  /** Returns true iff the hardware result a (as UInt) correctly represents the float. The software reference uses RNE
    * (Round to Nearest, ties to Even).
    *
    * -0 and +0 are also accepted as equal.
    */
  def fpEqualsHardware(expected: Float, from_hw: UInt, typeB: FpType) = {
    val expected_bigint = floatToUInt(typeB, expected)
    val from_hw_bigint  = from_hw.litValue
    val eqZero          = expected_bigint == 0          && isZero(from_hw_bigint, typeB)
    val eqNaN           = isNaN(expected_bigint, typeB) && isNaN(from_hw_bigint, typeB)

    from_hw_bigint == expected_bigint || eqZero || eqNaN
  }

  /** Returns true iff the hardware result a (as UInt) correctly represents the float. The result is allowed to differ
    * in `lsbTolerance` LSB bits, as a result from rounding errors propagated through operations.
    *
    * TODO remove. There should be no more rounding errors compared to hardware...
    */
  def fpAlmostEqualsHardware(expected: Float, from_hw: UInt, typeB: FpType) = {
    val lsbTolerance    = 4
    val expected_bigint = floatToUInt(typeB, expected)
    val from_hw_bigint  = from_hw.litValue
    val eqZero          = expected_bigint == 0          && isZero(from_hw_bigint, typeB)
    val eqNaN           = isNaN(expected_bigint, typeB) && isNaN(from_hw_bigint, typeB)
    (from_hw_bigint - expected_bigint).abs <= ((BigInt(1) << lsbTolerance) - 1) || eqZero || eqNaN
  }

  def isNaN(bits: BigInt, fpType: FpType): Boolean = {
    val expMask = ((BigInt(1) << fpType.expWidth) - 1) << fpType.sigWidth
    val exp     = (bits & expMask) >> fpType.sigWidth
    val frac    = bits & ((BigInt(1) << fpType.sigWidth) - 1)
    exp == (BigInt(1) << fpType.expWidth) - 1 && frac != 0
  }

  def isZero(bits: BigInt, fpType: FpType): Boolean = bits == BigInt(1) << fpType.width - 1

  /** Define operator symbol for mulFpHardware. Signature:  ((Float, FpType), (Float, FpType)) => Float */
  implicit class FpHardwareOps(a: (Float, FpType)) {
    def *(b: (Float, FpType)): Float = fpOperationHardware(a._1, b._1, a._2, b._2, _ * _)
    def +(b: (Float, FpType)): Float = fpOperationHardware(a._1, b._1, a._2, b._2, _ + _)

    /** Not to be confused with the Chisel3 === operator */
    def ===(b: UInt): Boolean = fpEqualsHardware(a._1, b, a._2)

  }

  def uintToStr(bits: BigInt, fpType: FpType): String =
    bits.toString(2).reverse.padTo(fpType.width, '0').reverse.grouped(4).mkString("_")

}
