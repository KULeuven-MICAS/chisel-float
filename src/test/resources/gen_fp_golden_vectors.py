# Copyright 2025 KU Leuven.
# Solderpad Hardware License, Version 0.51, see LICENSE for details.
# SPDX-License-Identifier: SHL-0.51
#
# Author: Robin Geens <robin.geens@kuleuven.be>
#
# Writes MPFR golden vectors to generated/fp_golden for FpGoldenVectorTest. Run with `pixi run golden`.


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
FORMATS = {"FP8": (5, 2), "FP16": (5, 10), "BF16": (8, 7), "FP32": (8, 23)}
# Keep these lists in sync with FpGoldenVectorTest
FMA_COMBOS = [
    ("BF16", "BF16", "BF16"),
    ("FP16", "FP16", "FP16"),
    ("FP32", "FP32", "FP32"),
    ("FP8", "FP8", "FP8"),
    ("BF16", "FP16", "BF16"),
    ("FP16", "BF16", "FP16"),
    ("FP16", "FP16", "FP32"),
    ("BF16", "BF16", "FP32"),
    ("FP8", "FP16", "FP16"),
    ("FP32", "FP32", "FP8"),
]
MUL_COMBOS = FMA_COMBOS
ADD_COMBOS = [
    ("BF16", "BF16", "BF16"),
    ("FP16", "FP16", "FP16"),
    ("FP32", "FP32", "FP32"),
    ("FP8", "FP8", "FP8"),
    ("FP16", "FP32", "FP32"),
    ("BF16", "BF16", "FP32"),
    ("FP16", "FP16", "FP32"),
    ("FP16", "FP32", "FP16"),  # narrowing
    ("FP32", "FP16", "BF16"),  # narrowing
    ("FP32", "FP32", "FP16"),  # narrowing
    ("FP16", "FP16", "FP8"),  # narrowing
]
CVT_COMBOS = [(a, c) for a in FORMATS for c in FORMATS if a != c]
DIV_SQRT_FORMATS = ["BF16", "FP16", "FP32"]

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
    if wA + wB <= 16:
        # small formats: every operand pair
        vectors = [(a, b) for a in range(1 << wA) for b in range(1 << wB)]
    else:
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


def gen_cvt(tA, tC, rng):
    (eA, sA), (eC, sC) = FORMATS[tA], FORMATS[tC]
    ctx = ieee_ctx(eC, sC)
    wA, wC = eA + sA + 1, eC + sC + 1

    def result_bits(a_bits):
        with WIDE:
            a = mpfr(decode(a_bits, eA, sA))
        with ctx:
            r = gmpy2.mul(a, 1)  # a full operation, so that MPFR rounds and subnormalizes into the target format
        return encode(float(r), eC, sC)

    if wA <= 16:
        vectors = list(range(1 << wA))  # every input
    else:
        vectors = boundary_patterns(eA, sA) + [rng.getrandbits(wA) for _ in range(N_RANDOM)]
        if sC < sA:
            # rounding ties and their neighbours: the dropped fraction bits are exactly half an ulp of the target
            tie = 1 << (sA - sC - 1)
            for _ in range(N_SKEW):
                vectors.append((rng.getrandbits(wA) & ~((tie << 1) - 1)) | (tie + rng.choice([-1, 0, 0, 1])))

    hA, hC = (wA + 3) // 4, (wC + 3) // 4
    return [f"{a:0{hA}x} {result_bits(a):0{hC}x}" for a in vectors]


def gen_div(t, rng):
    e, s = FORMATS[t]
    ctx = ieee_ctx(e, s)
    w = e + s + 1

    def result_bits(a_bits, b_bits):
        with WIDE:
            a, b = mpfr(decode(a_bits, e, s)), mpfr(decode(b_bits, e, s))
        with ctx:
            r = gmpy2.div(a, b)
        return encode(float(r), e, s)

    bnd = boundary_patterns(e, s)
    vectors = [(a, b) for a in bnd for b in bnd]
    vectors += [(rng.getrandbits(w), rng.getrandbits(w)) for _ in range(N_RANDOM)]
    h = (w + 3) // 4
    return [f"{a:0{h}x} {b:0{h}x} {result_bits(a, b):0{h}x}" for a, b in vectors]


def gen_sqrt(t, rng):
    e, s = FORMATS[t]
    ctx = ieee_ctx(e, s)
    w = e + s + 1

    def result_bits(a_bits):
        with WIDE:
            a = mpfr(decode(a_bits, e, s))
        with ctx:
            r = gmpy2.sqrt(a)
        return encode(float(r), e, s)

    if w <= 16:
        vectors = list(range(1 << w))  # every input
    else:
        vectors = boundary_patterns(e, s) + [rng.getrandbits(w) for _ in range(N_RANDOM)]
    h = (w + 3) // 4
    return [f"{a:0{h}x} {result_bits(a):0{h}x}" for a in vectors]


def write(name, description, seed, lines):
    path = OUT_DIR / f"{name}.txt.gz"
    with gzip.open(path, "wt") as f:
        f.write(f"# {description}, oracle: MPFR {gmpy2.mpfr_version()}, seed {seed}\n")
        f.write("\n".join(lines) + "\n")
    print(f"{path.name}: {len(lines)} vectors")


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for tA, tB, tC in FMA_COMBOS:
        lines = gen_fma(tA, tB, tC, random.Random(SEED))
        write(f"fma_{tA}_{tB}_{tC}", f"fma golden vectors: {tA} * {tB} + {tC} -> {tC}", SEED, lines)
    for tA, tB, tC in MUL_COMBOS:
        lines = gen_mul(tA, tB, tC, random.Random(SEED + 1))
        write(f"mul_{tA}_{tB}_{tC}", f"mul golden vectors: {tA} * {tB} -> {tC}", SEED + 1, lines)
    for tA, tB, tC in ADD_COMBOS:
        lines = gen_add(tA, tB, tC, random.Random(SEED + 2))
        write(f"add_{tA}_{tB}_{tC}", f"add golden vectors: {tA} + {tB} -> {tC}", SEED + 2, lines)
    for tA, tC in CVT_COMBOS:
        lines = gen_cvt(tA, tC, random.Random(SEED + 3))
        write(f"cvt_{tA}_{tC}", f"convert golden vectors: {tA} -> {tC}", SEED + 3, lines)
    for t in DIV_SQRT_FORMATS:
        write(f"div_{t}", f"div golden vectors: {t} / {t}", SEED + 4, gen_div(t, random.Random(SEED + 4)))
        write(f"sqrt_{t}", f"sqrt golden vectors: sqrt({t})", SEED + 5, gen_sqrt(t, random.Random(SEED + 5)))


if __name__ == "__main__":
    main()
