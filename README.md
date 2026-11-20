# chisel-float

<p align="left">
  <a href="https://github.com/KULeuven-MICAS/chisel-float/actions/workflows/scala-unit-test.yml"><img alt="Tests" src="https://github.com/KULeuven-MICAS/chisel-float/actions/workflows/scala-unit-test.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/License-SHL--0.51-blue.svg"></a>
  <img alt="Chisel" src="https://img.shields.io/badge/Chisel-6.4.0-2E8B57">
  <img alt="Simulation" src="https://img.shields.io/badge/Sim-Verilator%205-informational">
</p>

Mixed-precision floating-point units for [Chisel](https://www.chisel-lang.org/): addition, multiplication, fused
multiply-add, format conversion, FP × integer multiplication, division and square root. Operands and results can mix
formats: FP32, FP16, BF16, FP8 and a non-IEEE FP8 variant. The datapaths are SystemVerilog, derived from
[CVFPU (fpnew)](https://github.com/pulp-platform/cvfpu), and are wrapped as ordinary Chisel modules.

Every unit rounds the exact result once, and the tests check every unit bit for bit against an exact reference model.

- Organization: [MICAS, KU Leuven](https://www.esat.kuleuven.be/micas/)
- Maintainer: [Robin Geens](mailto:robin.geens@kuleuven.be)

## Units

All units are in package `fp_unit`. Operands and results are raw bit patterns (`UInt`) on the ports `in_a`, `in_b`,
`in_c` and `out`.

| Module | Computes | Formats | Timing |
| --- | --- | --- | --- |
| `FpAddFp(typeA, typeB, typeC)` | `in_a + in_b` | IEEE 754, all three independent | combinational |
| `FpMulFp(typeA, typeB, typeC)` | `in_a * in_b` | IEEE × IEEE → IEEE, all three independent; FP8\_ALT × FP8\_ALT → IEEE or FP8\_ALT | combinational |
| `FpFmaFp(typeA, typeB, typeC)` | `in_a * in_b + in_c` | IEEE 754; `in_c` and `out` share `typeC` | combinational |
| `FpConvert(typeA, typeC)` | `in_a` in another format | every IEEE pair; FP8\_ALT to and from every IEEE format | combinational |
| `FpMulInt(FP16, typeB, FP32)` | `in_a * in_b`, exact | FP16 × signed integer of 1 to 11 bits → FP32 | combinational |
| `FpDivSqrt(fpType)` | `in_a / in_b` or `sqrt(in_a)` | BF16, FP16 or FP32 | iterative, 6 to 12 cycles |

`FpAddFpSequential` wraps `FpAddFp` between input and output registers for synthesis experiments.

### FpMulInt

The integer `in_b` (e.g. `Int4` or `new IntType(11)`) is two's complement, except that a 1-bit integer is bipolar:
`1` encodes +1 and `0` encodes −1.

### FpDivSqrt

`FpDivSqrt` is CVFPU's THMULTI unit, built around the radix-16 divider of T-Head's
[OpenC910](https://github.com/T-head-Semi/openc910). Its ports are `Decoupled`.

- The operands of a division transfer in the same cycle; a square root only uses `in_a`. `mode` (0: divide, 1: square
  root) is sampled with the operands.
- The result appears on `out` at most 8 (BF16), 9 (FP16) or 12 (FP32) cycles later, sooner for exact results and
  special cases, and stays there until it is consumed. One operation is in flight at a time, the next one can transfer
  in the cycle the result is consumed.
- The unit has an asynchronous reset. In a module with the default synchronous reset, instantiate it as
  `withReset(reset.asAsyncReset)(Module(new FpDivSqrt(BF16)))`.

## Formats

| Object | Sign / exponent / mantissa bits | Bias | IEEE 754 | Largest finite | Smallest positive |
| --- | --- | --- | --- | --- | --- |
| `FP32` | 1 / 8 / 23 | 127 | yes | 3.40 × 10<sup>38</sup> | 2<sup>−149</sup> (subnormal) |
| `FP16` | 1 / 5 / 10 | 15 | yes | 65504 | 2<sup>−24</sup> (subnormal) |
| `BF16` | 1 / 8 / 7 | 127 | yes | 3.39 × 10<sup>38</sup> | 2<sup>−133</sup> (subnormal) |
| `FP8` (E5M2) | 1 / 5 / 2 | 15 | yes | 57344 | 2<sup>−16</sup> (subnormal) |
| `FP8_ALT` | 1 / 5 / 2 | 15 | **no** | 114688 | 1.25 × 2<sup>−15</sup> |

Declare signals as `UInt(BF16.W)`. `Int1` to `Int4` and `Int8` are predefined integer types; `new IntType(w)` gives any
other width.

## IEEE 754 compliance

A unit whose formats are all IEEE 754 formats follows IEEE 754-2019 with roundTiesToEven:

- The exact result is rounded once, also in the FMA and across mixed formats (no double rounding).
- Subnormal inputs and outputs are supported; nothing is flushed to zero. Overflow gives ±infinity.
- Signed zeros follow the standard, e.g. `x + (−x) = +0` and `sqrt(−0) = −0`.
- Invalid operations and NaN inputs give the canonical quiet NaN, as in RISC-V: sign 0, exponent all ones, only the
  mantissa MSB set (FP32 `0x7FC00000`, FP16 `0x7E00`, BF16 `0x7FC0`, FP8 `0x7E`).

Not implemented: other rounding modes, exception flags and NaN payload propagation. `FpConvert` from a format to itself
is a wire.

FP8\_ALT is the only non-IEEE format, and only these data paths accept it:

| Data path | Semantics |
| --- | --- |
| `FpMulFp` FP8\_ALT × FP8\_ALT → IEEE, `FpConvert` FP8\_ALT → IEEE | non-IEEE inputs, but the result is rounded as IEEE 754 prescribes |
| `FpMulFp` FP8\_ALT × FP8\_ALT → FP8\_ALT, `FpConvert` IEEE → FP8\_ALT | **not IEEE 754**, see below |

### FP8\_ALT

FP8\_ALT has the bit layout of FP8 (E5M2), but every encoding is a finite number. Exponent and mantissa all zero
encode ±0; every other encoding is the normal number (−1)<sup>s</sup> × 2<sup>e−15</sup> × (1 + m/4), also for
exponents 0 and 31. There are no subnormals, infinities or NaNs. Results in FP8\_ALT are rounded to nearest, ties to
even, except that:

- results beyond ±114688 saturate to ±114688;
- magnitudes up to 1.125 × 2<sup>−15</sup> **flush** to a zero with the sign of the exact result;
- converting an IEEE NaN gives +0, and ±infinity gives ±114688.


## Verification

Each unit has a test suite (`src/test/scala/fp_unit/Fp*Test.scala`) that compares the hardware bit for bit, including
the sign of zero and the NaN encoding, with `FpReference`, an exact model built on arbitrary-precision integers. The
stimulus combines exhaustive sweeps where the input space allows it (such as all operand pairs of the 8-bit formats
and all conversions from 8- and 16-bit formats), TestFloat-style boundary values, results around the underflow and
overflow thresholds, cancellation and alignment cases, and random encodings.

`FpGoldenVectorTest` checks `FpReference` itself against about 6 million golden vectors computed with
[MPFR](https://www.mpfr.org/). `pixi run test` generates them first; `pixi run golden` generates them on their own.
`FpIntegrationTest` instantiates the units in a user design.

### Test settings

| Variable | Values | Effect |
| --- | --- | --- |
| `FP_TEST_LEVEL` | `quick`, `default`, `extended` | Scales the number of random vectors (×0.1 / ×1 / ×10). `extended` also tests every format combination and adds larger exhaustive sweeps. |
| `FP_TEST_SEED` | integer, default `2025` | Seed of all random stimulus, printed by every sweep. |
| `FP_SIM` | `verilator` (default), `vcs` | Simulator backend. |
| `FP_VCD` | `1` | Dumps VCD waveforms into `test_run_dir/`. |
| `FP_GOLDEN_REQUIRED` | `1` | Fails `FpGoldenVectorTest` instead of skipping it when the golden vectors are missing. |

```sh
FP_TEST_LEVEL=quick pixi run test                     # fast smoke test
pixi run sbt "testOnly fp_unit.FpDivSqrtTest"         # one suite
FP_TEST_LEVEL=extended FP_TEST_SEED=7 pixi run test   # thorough run with another seed
```

Format the code with `pixi run fmt`; CI checks it with `pixi run lint`.

## Adding a format

1. Add an `object` that extends `FpType` to `src/main/scala/fp_unit/DataType.scala`, with its `expWidth`,
   `sigWidth` (mantissa bits) and `fpnewFormatEnum`.
2. Add the format to `fp_format_e` and `FP_ENCODINGS` (and raise `NUM_FP_FORMATS`) in
   `src/main/resources/common_block/fpnew_pkg_snax.sv`.
3. Add it to the format lists of the tests and to `FORMATS` in `src/test/resources/gen_fp_golden_vectors.py`.

## Repository layout

| Path | Contents |
| --- | --- |
| `src/main/scala/fp_unit/` | Chisel wrappers and format definitions |
| `src/main/resources/` | SystemVerilog datapaths; `common_block/` holds the shared fpnew package and helpers |
| `src/test/scala/fp_unit/` | Test suites, the reference model, stimulus generators and test settings |
| `src/test/resources/gen_fp_golden_vectors.py` | MPFR golden-vector generator |

## License and citation

chisel-float is released under the [Solderpad Hardware License 0.51](LICENSE). The datapaths are derived from
[CVFPU](https://github.com/pulp-platform/cvfpu) by ETH Zurich and the University of Bologna, under the same license.
The divider core in `src/main/resources/openc910/` is T-Head's OpenC910 code as patched by CVFPU, under the
[Apache License 2.0](src/main/resources/openc910/LICENSE).

If you use chisel-float in your research, please cite:

```bibtex
@misc{chisel_float,
  title        = {chisel-float: Mixed-precision floating point units, wrapped in Chisel},
  author       = {Geens, Robin},
  howpublished = {\url{https://github.com/KULeuven-MICAS/chisel-float}},
  year         = {2025},
}
```
