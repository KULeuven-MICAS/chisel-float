// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import scala.sys.process._
import scala.util.Try

import chisel3._
import chisel3.util._

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

object FpIntegrationTest {

  /** a0 * b0 + a1 * b1 in FP32 from BF16 inputs. */
  class DotProduct2 extends Module {
    val io = IO(new Bundle {
      val a   = Input(Vec(2, UInt(BF16.W)))
      val b   = Input(Vec(2, UInt(BF16.W)))
      val out = Output(UInt(FP32.W))
    })

    val mul = Module(new FpMulFp(BF16, BF16, FP32))
    mul.io.in_a := io.a(0)
    mul.io.in_b := io.b(0)

    val fma = Module(new FpFmaFp(BF16, BF16, FP32))
    fma.io.in_a := io.a(1)
    fma.io.in_b := io.b(1)
    fma.io.in_c := mul.io.out
    io.out      := fma.io.out
  }

  /** FpDivSqrt, which needs an asynchronous reset, in a module with the default (synchronous) reset. */
  class DivSqrtInSyncDesign extends Module {
    val io = IO(new Bundle {
      val in_a = Flipped(Decoupled(UInt(BF16.W)))
      val in_b = Flipped(Decoupled(UInt(BF16.W)))
      val mode = Input(Bool())
      val out  = Decoupled(UInt(BF16.W))
    })

    val divSqrt = withReset(reset.asAsyncReset)(Module(new FpDivSqrt(BF16)))
    divSqrt.io.in_a <> io.in_a
    divSqrt.io.in_b <> io.in_b
    divSqrt.io.mode := io.mode
    io.out          <> divSqrt.io.out
  }
}

/** The units used as a library, instantiated in a user's module. */
class FpIntegrationTest extends AnyFlatSpec with ChiselScalatestTester {
  import FpIntegrationTest._

  behavior of "The units inside a user design"

  it should "compute a dot product with a single rounding" in {
    test(new DotProduct2).withAnnotations(FpTestConfig.annotations) { dut =>
      val r = new scala.util.Random(FpTestConfig.seed)
      for (_ <- 0 until FpTestConfig.scaled(2000)) {
        val v  = Seq.fill(4)(BigInt(16, r))
        dut.io.a(0).poke(v(0).U)
        dut.io.b(0).poke(v(1).U)
        dut.io.a(1).poke(v(2).U)
        dut.io.b(1).poke(v(3).U)
        // BF16 x BF16 -> FP32 is exact, so this is the exact dot product rounded once
        val p0 = FpReference.mul(BF16, BF16, FP32, v(0), v(1))
        dut.io.out.expect(FpReference.fma(BF16, BF16, FP32, v(2), v(3), p0).U)
      }
    }
  }

  it should "emit a design as one SystemVerilog file that compiles on its own" in {
    assume(Try(Seq("verilator", "--version").!!).isSuccess, "Verilator is not on the PATH")
    val designs = Seq[(String, () => RawModule)](
      "DotProduct2" -> (() => new DotProduct2),
      "FpDivSqrt"   -> (() => new FpDivSqrt(BF16))
    )
    for ((name, gen) <- designs) {
      val file   = FpEmitter(gen(), name, "test_run_dir/FpEmitter")
      val output = new StringBuilder
      val log    = ProcessLogger(line => output.append(line).append('\n'))
      val exit   = Seq("verilator", "--lint-only", "-Wno-fatal", "--top-module", name, file.toString) ! log
      assert(exit == 0, s"$file does not compile on its own:\n$output")
    }
  }

  it should "run FpDivSqrt in a module with a synchronous reset" in {
    test(new DivSqrtInSyncDesign).withAnnotations(FpTestConfig.annotations) { dut =>
      val one    = BigInt(0x3f80) // 1.0
      val three  = BigInt(0x4040) // 3.0
      dut.io.mode.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.in_a.valid.poke(true.B)
      dut.io.in_a.bits.poke(one.U)
      dut.io.in_b.valid.poke(true.B)
      dut.io.in_b.bits.poke(three.U)
      dut.io.in_a.ready.expect(true.B)
      dut.clock.step()
      dut.io.in_a.valid.poke(false.B)
      dut.io.in_b.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.out.valid.peekBoolean()) {
        assert(cycles < 100, "timeout waiting for out.valid")
        dut.clock.step()
        cycles += 1
      }
      dut.io.out.bits.expect(FpReference.div(BF16, one, three).U)
    }
  }
}
