// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

/** Bit-exact model of every unit: exact arithmetic on BigInt, rounded once. */
object FpReference {

  // Format helpers

  def bias(t:        FpType): Int    = (1 << (t.expWidth - 1)) - 1
  def mask(w:        Int):    BigInt = (BigInt(1) << w) - 1
  def maxExpField(t: FpType): Int    = (1 << t.expWidth) - 1

  def signOf(t:   FpType, bits: BigInt): Int    = ((bits >> (t.width - 1)) & 1).toInt
  def expField(t: FpType, bits: BigInt): Int    = ((bits >> t.sigWidth) & mask(t.expWidth)).toInt
  def manField(t: FpType, bits: BigInt): BigInt = bits & mask(t.sigWidth)

  def zero(t: FpType, sign: Int): BigInt = BigInt(sign) << (t.width - 1)

  def infinity(t: FpType, sign: Int): BigInt = {
    require(t.isIEEE754, s"$t has no infinity")
    zero(t, sign) | (BigInt(maxExpField(t)) << t.sigWidth)
  }

  def canonicalNaN(t: FpType): BigInt = {
    require(t.isIEEE754, s"$t has no NaN")
    (BigInt(maxExpField(t)) << t.sigWidth) | (BigInt(1) << (t.sigWidth - 1))
  }

  def maxFinite(t: FpType, sign: Int): BigInt =
    if (t.isIEEE754) zero(t, sign) | (BigInt(maxExpField(t) - 1) << t.sigWidth) | mask(t.sigWidth)
    else zero(t, sign) | mask(t.width - 1)

  // Decoding

  sealed trait Value

  /** (-1)^sign * mant * 2^scale; mant == 0 encodes a (signed) zero. */
  final case class Finite(sign: Int, mant: BigInt, scale: Int) extends Value {
    def isZero: Boolean = mant == 0
  }
  final case class Infinity(sign: Int)                         extends Value
  case object NaN                                              extends Value

  def decode(t: FpType, bits: BigInt): Value = {
    val s = signOf(t, bits); val e = expField(t, bits); val m = manField(t, bits)
    if (t.isIEEE754) {
      if (e == maxExpField(t)) { if (m != 0) NaN else Infinity(s) }
      else if (e == 0) Finite(s, m, 1 - bias(t) - t.sigWidth) // zero or subnormal
      else Finite(s, m | (BigInt(1) << t.sigWidth), e - bias(t) - t.sigWidth)
    } else {
      // Non-IEEE: only exponent == mantissa == 0 is special (zero); all other encodings are normal numbers
      if (e == 0 && m == 0) Finite(s, 0, 0)
      else Finite(s, m | (BigInt(1) << t.sigWidth), e - bias(t) - t.sigWidth)
    }
  }

  def toDouble(t: FpType, bits: BigInt): Double =
    decode(t, bits) match {
      case NaN              => Double.NaN
      case Infinity(s)      => if (s == 1) Double.NegativeInfinity else Double.PositiveInfinity
      case Finite(s, m, sc) =>
        val v = m.toDouble * math.pow(2, sc)
        if (s == 1) -v else v
    }

  def describe(t: FpType, bits: BigInt): String = {
    val hexDigits = (t.width + 3) / 4
    val hex       = bits.toString(16).reverse.padTo(hexDigits, '0').reverse
    val value     = decode(t, bits) match {
      case NaN                       => if (bits == canonicalNaN(t)) "qNaN" else "NaN(non-canonical)"
      case Finite(s, m, _) if m == 0 => if (s == 1) "-0" else "+0"
      case _                         => toDouble(t, bits).toString
    }
    s"0x$hex ($value)"
  }

  // Rounding and encoding

  /** Shift right by `shift` with RNE (shift <= 0 is an exact left shift). */
  def roundRNE(value: BigInt, shift: Int): BigInt =
    if (shift <= 0) value << -shift
    else {
      val kept     = value >> shift
      val roundBit = value.testBit(shift - 1)
      val sticky   = (value & mask(shift - 1)) != 0
      kept + (if (roundBit && (sticky || kept.testBit(0))) 1 else 0)
    }

  /** Rounds (-1)^sign * mant * 2^scale into IEEE format `t`. */
  def encodeIEEE(t: FpType, sign: Int, mant: BigInt, scale: Int): BigInt = {
    require(t.isIEEE754)
    require(mant >= 0)
    val man      = t.sigWidth
    val signBits = zero(t, sign)
    if (mant == 0) return signBits
    val unbExp   = scale + mant.bitLength - 1                // value in [2^unbExp, 2^(unbExp+1))
    val lsbW     = math.max(unbExp - man, 1 - bias(t) - man) // weight of the destination LSB
    val m2       = roundRNE(mant, lsbW - scale)
    if (m2 == 0) return signBits // underflow to zero
    val nb     = m2.bitLength
    val biased = lsbW + nb - 1 + bias(t)
    if (biased <= 0) signBits | m2 // subnormal (m2 == 2^man encodes min normal)
    else if (biased >= maxExpField(t)) infinity(t, sign) // overflow
    else signBits | (BigInt(biased) << man) | ((m2 << (man + 1 - nb)) & mask(man))
  }

  /** Rounds (-1)^sign * mant * 2^scale into FP8_ALT: tiny values flush to ±0, overflow saturates to ±max. */
  def encodeAlt(t: FpType, sign: Int, mant: BigInt, scale: Int): BigInt = {
    require(!t.isIEEE754)
    require(mant >= 0)
    val man           = t.sigWidth
    if (mant == 0) return zero(t, sign)
    val unbExp        = scale + mant.bitLength - 1
    if (unbExp + bias(t) < 0) return zero(t, sign)
    val m2            = roundRNE(mant, unbExp - man - scale) // man+1 bits, or man+2 bits on a rounding carry
    val (sig, biased) =
      if (m2.bitLength > man + 1) (m2 >> 1, unbExp + bias(t) + 1) else (m2, unbExp + bias(t))
    if (biased > maxExpField(t)) maxFinite(t, sign)
    else zero(t, sign) | (BigInt(biased) << man) | (sig & mask(man))
  }

  def encode(t: FpType, sign: Int, mant: BigInt, scale: Int): BigInt =
    if (t.isIEEE754) encodeIEEE(t, sign, mant, scale) else encodeAlt(t, sign, mant, scale)

  // Unit models

  def add(typeA: FpType, typeB: FpType, typeC: FpType, a: BigInt, b: BigInt): BigInt = {
    require(typeA.isIEEE754 && typeB.isIEEE754 && typeC.isIEEE754)
    (decode(typeA, a), decode(typeB, b)) match {
      case (NaN, _) | (_, NaN)                      => canonicalNaN(typeC)
      case (Infinity(sa), Infinity(sb))             => if (sa != sb) canonicalNaN(typeC) else infinity(typeC, sa)
      case (Infinity(s), _)                         => infinity(typeC, s)
      case (_, Infinity(s))                         => infinity(typeC, s)
      case (Finite(sa, ma, ea), Finite(sb, mb, eb)) =>
        if (ma == 0 && mb == 0) zero(typeC, sa & sb) // (-0) + (-0) = -0, otherwise +0
        else {
          val e0  = math.min(ea, eb)
          val sum = signed(sa, ma << (ea - e0)) + signed(sb, mb << (eb - e0))
          if (sum == 0) zero(typeC, 0) // exact cancellation: +0 under RNE
          else encodeIEEE(typeC, if (sum < 0) 1 else 0, sum.abs, e0)
        }
    }
  }

  def mul(typeA: FpType, typeB: FpType, typeC: FpType, a: BigInt, b: BigInt): BigInt = {
    require(typeA.isIEEE754 == typeB.isIEEE754)
    require(typeC.isIEEE754 || !typeA.isIEEE754, "IEEE inputs require an IEEE output")
    (decode(typeA, a), decode(typeB, b)) match {
      case (NaN, _) | (_, NaN)                      => canonicalNaN(typeC)
      case (Infinity(_), z: Finite) if z.isZero     => canonicalNaN(typeC) // inf * 0
      case (z: Finite, Infinity(_)) if z.isZero     => canonicalNaN(typeC) // 0 * inf
      case (Infinity(sa), Infinity(sb))             => infinity(typeC, sa ^ sb)
      case (Infinity(sa), Finite(sb, _, _))         => infinity(typeC, sa ^ sb)
      case (Finite(sa, _, _), Infinity(sb))         => infinity(typeC, sa ^ sb)
      case (Finite(sa, ma, ea), Finite(sb, mb, eb)) => encode(typeC, sa ^ sb, ma * mb, ea + eb)
    }
  }

  def fma(typeA: FpType, typeB: FpType, typeC: FpType, a: BigInt, b: BigInt, c: BigInt): BigInt = {
    require(typeA.isIEEE754 && typeB.isIEEE754 && typeC.isIEEE754)
    (decode(typeA, a), decode(typeB, b), decode(typeC, c)) match {
      case (NaN, _, _) | (_, NaN, _) | (_, _, NaN)                      => canonicalNaN(typeC)
      case (Infinity(_), z: Finite, _) if z.isZero                      => canonicalNaN(typeC) // inf * 0
      case (z: Finite, Infinity(_), _) if z.isZero                      => canonicalNaN(typeC) // 0 * inf
      case (Infinity(sa), Infinity(sb), dc)                             => fmaInfiniteProduct(typeC, sa ^ sb, dc)
      case (Infinity(sa), Finite(sb, _, _), dc)                         => fmaInfiniteProduct(typeC, sa ^ sb, dc)
      case (Finite(sa, _, _), Infinity(sb), dc)                         => fmaInfiniteProduct(typeC, sa ^ sb, dc)
      case (_: Finite, _: Finite, Infinity(sc))                         => infinity(typeC, sc)
      case (Finite(sa, ma, ea), Finite(sb, mb, eb), Finite(sc, mc, ec)) =>
        val prodSign = sa ^ sb
        val pm       = ma * mb
        val pe       = ea + eb
        if (pm == 0 && mc == 0) zero(typeC, prodSign & sc) // sum of zeros: -0 only if both are -0
        else {
          val e0  = math.min(pe, ec)
          val sum = signed(prodSign, pm << (pe - e0)) + signed(sc, mc << (ec - e0))
          if (sum == 0) zero(typeC, 0) // exact cancellation: +0 under RNE
          else encodeIEEE(typeC, if (sum < 0) 1 else 0, sum.abs, e0)
        }
    }
  }

  private def fmaInfiniteProduct(typeC: FpType, prodSign: Int, c: Value): BigInt =
    c match {
      case Infinity(sc) if sc != prodSign => canonicalNaN(typeC) // inf - inf
      case _                              => infinity(typeC, prodSign)
    }

  def convert(typeA: FpType, typeC: FpType, a: BigInt): BigInt =
    if (typeA == typeC) a
    else
      decode(typeA, a) match {
        case NaN              => if (typeC.isIEEE754) canonicalNaN(typeC) else zero(typeC, 0)
        case Infinity(s)      => if (typeC.isIEEE754) infinity(typeC, s) else maxFinite(typeC, s)
        case Finite(s, m, sc) => encode(typeC, s, m, sc)
      }

  def intValue(width: Int, b: BigInt): BigInt = {
    val v = b & mask(width)
    if (width == 1) { if (v == 1) BigInt(1) else BigInt(-1) }
    else if (v.testBit(width - 1)) v - (BigInt(1) << width)
    else v
  }

  def mulInt(width: Int, a: BigInt, b: BigInt): BigInt = {
    val iv = intValue(width, b)
    val si = if (iv < 0) 1 else 0
    decode(FP16, a) match {
      case NaN              => canonicalNaN(FP32)
      case Infinity(s)      => if (iv == 0) canonicalNaN(FP32) else infinity(FP32, s ^ si)
      case Finite(s, m, sc) => encodeIEEE(FP32, s ^ si, m * iv.abs, sc)
    }
  }

  def div(t: FpType, a: BigInt, b: BigInt): BigInt = {
    require(t.isIEEE754)
    (decode(t, a), decode(t, b)) match {
      case (NaN, _) | (_, NaN)                      => canonicalNaN(t)
      case (Infinity(_), Infinity(_))               => canonicalNaN(t)
      case (Infinity(sa), Finite(sb, _, _))         => infinity(t, sa ^ sb)
      case (Finite(sa, _, _), Infinity(sb))         => zero(t, sa ^ sb)
      case (Finite(sa, ma, ea), Finite(sb, mb, eb)) =>
        if (ma == 0 && mb == 0) canonicalNaN(t)
        else if (mb == 0) infinity(t, sa ^ sb)
        else if (ma == 0) zero(t, sa ^ sb)
        else {
          // Quotient with >= sigWidth+4 significant bits; the remainder becomes a sticky bit below them
          val k      = t.sigWidth + 4 + mb.bitLength
          val (q, r) = (ma << k) /% mb
          encodeIEEE(t, sa ^ sb, (q << 1) | (if (r != 0) 1 else 0), ea - eb - k - 1)
        }
    }
  }

  def sqrt(t: FpType, a: BigInt): BigInt = {
    require(t.isIEEE754)
    decode(t, a) match {
      case NaN              => canonicalNaN(t)
      case Infinity(s)      => if (s == 0) infinity(t, 0) else canonicalNaN(t)
      case Finite(s, m, e0) =>
        if (m == 0) zero(t, s)
        else if (s == 1) canonicalNaN(t)
        else {
          // Make the exponent even, then take an integer root with >= sigWidth+4 significant bits
          val (mm, e) = if ((e0 & 1) != 0) (m << 1, e0 - 1) else (m, e0)
          val k       = 2 * (t.sigWidth + 4)
          val big     = mm << k
          val root    = BigInt(big.bigInteger.sqrt())
          encodeIEEE(t, 0, (root << 1) | (if (root * root != big) 1 else 0), (e - k) / 2 - 1)
        }
    }
  }

  private def signed(sign: Int, mag: BigInt): BigInt = if (sign == 1) -mag else mag
}
