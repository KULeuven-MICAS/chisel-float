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
  - All are implemented in a purely combinatorial way
- Mixed precision
  - Independent type selection per input/output
  - Supported sofar: FP8, FP16, BF16, FP32
  - To add new (arbitrary FP type):
    1. Create object that inherits from `FpType`
    2. Add type to `fp_format_e` and `fp_encoding_t` in `src/main/resources/common_block/fpnew_pkg_snax.sv`
    3. Add tests cases (and pray they work)
- Testing
  - Randomized tests
  - Mixed‑precision coverage


## Repository layout

- `src/main/scala/fp_unit/` — Chisel wrappers and type definitions
- `src/test/scala/fp_unit/` — Test suites and reference utilities
- `src/main/resources/` — Verilog source code


