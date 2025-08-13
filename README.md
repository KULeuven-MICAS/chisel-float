# Mixed-precision floating point units, wrapped in Chisel.


<p align="left">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/License-SHL--0.51-blue.svg"></a>
  <img alt="Chisel" src="https://img.shields.io/badge/Chisel-6.4.0-2E8B57">
  <img alt="Simulation" src="https://img.shields.io/badge/Sim-Verilator-informational">
</p>

Floating point units (add, mul, FMA) for transprecision computing in arbitrary FP formats, wrapped and tested in Chisel. The verilog implementation is based on ETH's [CVFPU](https://github.com/pulp-platform/fpnew).

- Organization: MICAS (KU Leuven)
- Maintainer: [Robin Geens](mailto:robin.geens@kuleuven.be)


## Features ✨

- Modules
  - FpAddFp — floating‑point addition
  - FpMulFp — floating‑point multiplication
  - FpFmaFp — fused multiply‑add
    - Currently implemented as Mul + Add composition; fused (accuracy-preserving) implementation is under way.
  - All are implemented in a purely combinatorial way
- Mixed precision
  - Independent type selection per input/output
  - Tested so far: FP16, BF16, FP32; but any FP format should work
- Testing
  - Randomized tests
  - Mixed‑precision coverage


## Repository layout

- `src/main/scala/fp_unit/` — Chisel wrappers and type definitions
- `src/test/scala/fp_unit/` — test suites and reference utilities
- `src/main/resources/` — Verilog source code


