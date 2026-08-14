# Copyright 2025 KU Leuven.
# Solderpad Hardware License, Version 0.51, see LICENSE for details.
# SPDX-License-Identifier: SHL-0.51
#
# Author: Robin Geens <robin.geens@kuleuven.be>
#
# Generate golden test vectors for the Scala software FP references (fmaExact, mulExact,
# addExact) using MPFR as the IEEE-754 oracle.


import gzip
import math
import random
from pathlib import Path

import gmpy2
from gmpy2 import mpfr, context, RoundToNearest

SEED = 2025
N_RANDOM = 100_000
N_CANCEL = 30_000
N_SKEW = 20_000

OUT_DIR = Path(__file__).resolve().parents[3] / "generated" / "fp_golden"

# (name, expWidth, sigWidth). sigWidth = fraction bits, as in fp_unit.FpType
FORMATS = {"FP16": (5, 10), "BF16": (8, 7), "FP32": (8, 23)}
FMA_COMBOS = [
    ("BF16", "BF16", "BF16"),
    ("FP16", "FP16", "FP16"),
    ("FP32", "FP32", "FP32"),
    ("BF16", "FP16", "BF16"),
    ("FP16", "BF16", "FP16"),
    ("FP16", "FP16", "FP32"),
    ("BF16", "BF16", "FP32"),
]
MUL_COMBOS = FMA_COMBOS
ADD_COMBOS = [
    ("BF16", "BF16", "BF16"),
    ("FP16", "FP16", "FP16"),
    ("FP32", "FP32", "FP32"),
    ("FP16", "FP32", "FP32"),
    ("BF16", "BF16", "FP32"),
    ("FP16", "FP16", "FP32"),
    ("FP16", "FP32", "FP16"),  # narrowing
    ("FP32", "FP16", "BF16"),  # narrowing
    ("FP32", "FP32", "FP16"),  # narrowing
]

# Wide context used only to hold input values exactly (never rounds anything relevant)
WIDE = context(precision=64, emin=-100_000, emax=100_000, round=RoundToNearest)


def ieee_ctx(exp_w, sig_w):
    """MPFR context that emulates IEEE binary(exp_w, sig_w) exactly."""
    p = sig_w + 1
    bias = (1 << (exp_w - 1)) - 1
    emax_ieee = bias  # max unbiased exponent of normals
    emin_ieee = 1 - bias  # min unbiased exponent of normals
    # MPFR convention: significand in [0.5, 1)
    return context(
        precision=p,
        emin=emin_ieee - p + 2,
        emax=emax_ieee + 1,
        subnormalize=True,
        round=RoundToNearest,
    )


def decode(bits, exp_w, sig_w):
    """Bit pattern -> exact double (all FP16/BF16 values are exact in binary64)."""
    bias = (1 << (exp_w - 1)) - 1
    s = -1.0 if (bits >> (exp_w + sig_w)) & 1 else 1.0
    e = (bits >> sig_w) & ((1 << exp_w) - 1)
    m = bits & ((1 << sig_w) - 1)
    if e == (1 << exp_w) - 1:
        return s * math.inf if m == 0 else math.nan
    if e == 0:
        return s * math.ldexp(m, 1 - bias - sig_w)
    return s * math.ldexp((1 << sig_w) | m, e - bias - sig_w)


def encode(v, exp_w, sig_w):
    """Exact double -> bit pattern. Asserts v is exactly representable (no rounding here)."""
    bias = (1 << (exp_w - 1)) - 1
    if math.isnan(v):  # canonical NaN
        return ((1 << exp_w) - 1) << sig_w | (1 << (sig_w - 1))
    sign = 1 if math.copysign(1.0, v) < 0 else 0
    sbit = sign << (exp_w + sig_w)
    if v == 0.0:
        return sbit
    if math.isinf(v):
        return sbit | ((1 << exp_w) - 1) << sig_w
    a = abs(v)
    e = math.frexp(a)[1] - 1  # a in [2^e, 2^(e+1))
    if e < 1 - bias:  # subnormal
        m = a / math.ldexp(1.0, 1 - bias - sig_w)
        assert m.is_integer() and 0 < int(m) < (1 << sig_w), f"not exact: {v}"
        return sbit | int(m)
    m = a / math.ldexp(1.0, e - sig_w)
    assert m.is_integer(), f"not exact: {v}"
    m = int(m) - (1 << sig_w)
    assert 0 <= m < (1 << sig_w) and 0 < e + bias < (1 << exp_w) - 1, f"not exact: {v}"
    return sbit | (e + bias) << sig_w | m


def boundary_patterns(exp_w, sig_w):
    """TestFloat-style boundary encodings: zeros, subnormal/normal/overflow edges, ties, specials."""
    bias = (1 << (exp_w - 1)) - 1
    emax = (1 << exp_w) - 1
    man1 = (1 << sig_w) - 1  # all-ones mantissa
    half = 1 << (sig_w - 1)  # tie bit
    mags = {
        0,  # zero
        1,
        2,
        half,
        man1,  # subnormals: min, min+1, mid, max
        1 << sig_w,
        (1 << sig_w) | 1,  # min normal, +1ulp
        (bias - 1) << sig_w | man1,  # just below 1.0
        bias << sig_w,
        bias << sig_w | 1,  # 1.0, 1.0+1ulp
        bias << sig_w | half,
        bias << sig_w | half | 1,  # 1.5, 1.5+1ulp
        (bias + sig_w + 1) << sig_w,  # ulp = 2 (integer territory)
        (bias + sig_w + 1) << sig_w | 1,
        2 << sig_w | man1,  # small normal, all-ones mantissa
        (emax - 1) << sig_w,
        (emax - 1) << sig_w | 1,  # near-overflow
        (emax - 1) << sig_w | man1,  # max normal
        emax << sig_w,  # inf
    }
    pats = sorted(mags)
    signed = [s << (exp_w + sig_w) | m for s in (0, 1) for m in pats]
    signed.append(emax << sig_w | half)  # one NaN
    return signed


def gen_fma(tA, tB, tC, rng):
    (eA, sA), (eB, sB), (eC, sC) = FORMATS[tA], FORMATS[tB], FORMATS[tC]
    ctx = ieee_ctx(eC, sC)
    wA, wB, wC = eA + sA + 1, eB + sB + 1, eC + sC + 1

    def mp(bits, e, s):
        with WIDE:
            return mpfr(decode(bits, e, s))

    def result_bits(a_bits, b_bits, c_bits):
        a, b, c = mp(a_bits, eA, sA), mp(b_bits, eB, sB), mp(c_bits, eC, sC)
        with ctx:
            r = gmpy2.fma(a, b, c)
        return encode(float(r), eC, sC)

    vectors = []

    # 1) full cross product of boundary patterns
    bndA, bndB, bndC = (boundary_patterns(*FORMATS[t]) for t in (tA, tB, tC))
    for a in bndA:
        for b in bndB:
            for c in bndC:
                vectors.append((a, b, c))

    # 2) uniform random bit patterns
    for _ in range(N_RANDOM):
        vectors.append((rng.getrandbits(wA), rng.getrandbits(wB), rng.getrandbits(wC)))

    # 3) near-cancellation: c ~= -(a*b), half of them off by 1 ulp
    for i in range(N_CANCEL):
        a_bits, b_bits = rng.getrandbits(wA), rng.getrandbits(wB)
        with ctx:
            p = gmpy2.mul(mp(a_bits, eA, sA), mp(b_bits, eB, sB))
        pf = float(p)
        if math.isnan(pf) or math.isinf(pf):
            c_bits = rng.getrandbits(wC)
        else:
            c_bits = encode(-pf, eC, sC)
            if i % 2 == 1:
                c_bits ^= 1  # +-1 ulp off exact cancellation
        vectors.append((a_bits, b_bits, c_bits))

    # 4) magnitude skew: tiny addend vs large product and vice versa (sticky-bit stress)
    tinyC = [1, 2, 3, (1 << sC) - 1, (1 << sC), (1 << (eC + sC)) | 1]
    tinyA = [1, 2, 3, (1 << sA) - 1, (1 << sA), (1 << (eA + sA)) | 1]
    for i in range(N_SKEW):
        if i % 2 == 0:
            vectors.append((rng.getrandbits(wA), rng.getrandbits(wB), rng.choice(tinyC)))
        else:
            vectors.append((rng.choice(tinyA), rng.getrandbits(wB), rng.getrandbits(wC)))

    hA, hB, hC = ((w + 3) // 4 for w in (wA, wB, wC))
    lines = [f"{a:0{hA}x} {b:0{hB}x} {c:0{hC}x} {result_bits(a, b, c):0{hC}x}" for a, b, c in vectors]
    return lines


def gen_mul(tA, tB, tC, rng):
    (eA, sA), (eB, sB), (eC, sC) = FORMATS[tA], FORMATS[tB], FORMATS[tC]
    ctx = ieee_ctx(eC, sC)
    wA, wB = eA + sA + 1, eB + sB + 1

    def result_bits(a_bits, b_bits):
        with WIDE:
            a, b = mpfr(decode(a_bits, eA, sA)), mpfr(decode(b_bits, eB, sB))
        with ctx:
            r = gmpy2.mul(a, b)
        return encode(float(r), eC, sC)

    vectors = []
    bndA, bndB = boundary_patterns(eA, sA), boundary_patterns(eB, sB)
    for a in bndA:
        for b in bndB:
            vectors.append((a, b))
    for _ in range(N_RANDOM):
        vectors.append((rng.getrandbits(wA), rng.getrandbits(wB)))

    hA, hB, hC = ((w + 3) // 4 for w in (wA, wB, eC + sC + 1))
    lines = [f"{a:0{hA}x} {b:0{hB}x} {result_bits(a, b):0{hC}x}" for a, b in vectors]
    return lines


def gen_add(tA, tB, tC, rng):
    (eA, sA), (eB, sB), (eC, sC) = FORMATS[tA], FORMATS[tB], FORMATS[tC]
    ctx = ieee_ctx(eC, sC)
    ctxB = ieee_ctx(eB, sB)
    wA, wB = eA + sA + 1, eB + sB + 1

    def result_bits(a_bits, b_bits):
        with WIDE:
            a, b = mpfr(decode(a_bits, eA, sA)), mpfr(decode(b_bits, eB, sB))
        with ctx:
            r = gmpy2.add(a, b)
        return encode(float(r), eC, sC)

    vectors = []

    # 1) full cross product of boundary patterns
    bndA, bndB = boundary_patterns(eA, sA), boundary_patterns(eB, sB)
    for a in bndA:
        for b in bndB:
            vectors.append((a, b))

    # 2) uniform random bit patterns
    for _ in range(N_RANDOM):
        vectors.append((rng.getrandbits(wA), rng.getrandbits(wB)))

    # 3) near-cancellation: b ~= -a rounded to B's format, half of them off by 1-3 ulp
    for i in range(N_CANCEL):
        a_bits = rng.getrandbits(wA)
        with ctxB:
            nb = mpfr(-decode(a_bits, eA, sA))
        bf = float(nb)
        if math.isnan(bf):
            b_bits = rng.getrandbits(wB)
        else:
            b_bits = encode(bf, eB, sB)
            if i % 2 == 1:
                b_bits ^= rng.choice([1, 2, 3])
        vectors.append((a_bits, b_bits))

    # 4) magnitude skew: tiny vs large operand, stressing the full-sticky alignment regime
    tinyA = [1, 2, 3, (1 << sA) - 1, (1 << sA), (1 << (eA + sA)) | 1]
    tinyB = [1, 2, 3, (1 << sB) - 1, (1 << sB), (1 << (eB + sB)) | 1]
    for i in range(N_SKEW):
        if i % 2 == 0:
            vectors.append((rng.getrandbits(wA), rng.choice(tinyB) | (rng.getrandbits(1) << (wB - 1))))
        else:
            vectors.append((rng.choice(tinyA) | (rng.getrandbits(1) << (wA - 1)), rng.getrandbits(wB)))

    hA, hB, hC = ((w + 3) // 4 for w in (wA, wB, eC + sC + 1))
    lines = [f"{a:0{hA}x} {b:0{hB}x} {result_bits(a, b):0{hC}x}" for a, b in vectors]
    return lines


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for tA, tB, tC in FMA_COMBOS:
        rng = random.Random(SEED)
        lines = gen_fma(tA, tB, tC, rng)
        path = OUT_DIR / f"fma_{tA}_{tB}_{tC}.txt.gz"
        header = (
            f"# fmaExact golden vectors: {tA} * {tB} + {tC} -> {tC}, oracle: MPFR {gmpy2.mpfr_version()}, seed {SEED}\n"
        )
        with gzip.open(path, "wt") as f:
            f.write(header)
            f.write("\n".join(lines) + "\n")
        print(f"{path.name}: {len(lines)} vectors")
    for tA, tB, tC in MUL_COMBOS:
        rng = random.Random(SEED + 1)
        lines = gen_mul(tA, tB, tC, rng)
        path = OUT_DIR / f"mul_{tA}_{tB}_{tC}.txt.gz"
        header = (
            f"# mulExact golden vectors: {tA} * {tB} -> {tC}, oracle: MPFR {gmpy2.mpfr_version()}, seed {SEED + 1}\n"
        )
        with gzip.open(path, "wt") as f:
            f.write(header)
            f.write("\n".join(lines) + "\n")
        print(f"{path.name}: {len(lines)} vectors")
    for tA, tB, tC in ADD_COMBOS:
        rng = random.Random(SEED + 2)
        lines = gen_add(tA, tB, tC, rng)
        path = OUT_DIR / f"add_{tA}_{tB}_{tC}.txt.gz"
        header = (
            f"# ideal-add golden vectors: {tA} + {tB} -> {tC}, oracle: MPFR {gmpy2.mpfr_version()}, seed {SEED + 2}\n"
        )
        with gzip.open(path, "wt") as f:
            f.write(header)
            f.write("\n".join(lines) + "\n")
        print(f"{path.name}: {len(lines)} vectors")


if __name__ == "__main__":
    main()
