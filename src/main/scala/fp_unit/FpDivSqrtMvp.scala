// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>
// Wrapper for div_sqrt_mvp_wrapper.sv (ETH Zurich div/sqrt unit).

package fp_unit

import chisel3._
import chisel3.util._

/** BlackBox for div_sqrt_mvp_wrapper.sv; hardwired to BF16 (16-bit) operands and result. */
class DivSqrtMvpBlackBox extends BlackBox with HasBlackBoxResource {

  val fpType = BF16

  val io = IO(new Bundle {
    val Clk_CI        = Input(Clock())
    val Rst_RBI       = Input(Bool())
    val Div_start_SI  = Input(Bool())
    val Sqrt_start_SI = Input(Bool())
    val Operand_a_DI  = Input(UInt(fpType.W))
    val Operand_b_DI  = Input(UInt(fpType.W))
    val Result_DO     = Output(UInt(fpType.W))
    val Ready_SO      = Output(Bool())
    val Done_SO       = Output(Bool())
  })

  override def desiredName: String = "div_sqrt_mvp_wrapper"

  addResource("sqrt_div/defs_div_sqrt_mvp.sv")
  addResource("sqrt_div/control_mvp.sv")
  addResource("sqrt_div/preprocess_mvp.sv")
  addResource("sqrt_div/norm_div_sqrt_mvp.sv")
  addResource("sqrt_div/nrbd_nrsc_mvp.sv")
  addResource("sqrt_div/iteration_div_sqrt_mvp.sv")
  addResource("sqrt_div/div_sqrt_top_mvp.sv")
  addResource("sqrt_div/div_sqrt_mvp_wrapper.sv")
  addResource("common_block/lzc.sv")
}

/** Wrapper for the div_sqrt_mvp_wrapper BlackBox with decoupled IO and a simple FSM.
  *   - Accepts one request via io.in (operands + div_start or sqrt_start); when in.fire, starts the unit.
  *   - Presents result on io.out when done; consumer uses out.ready to back-pressure.
  */
class FpDivSqrtMvp extends Module with RequireAsyncReset {

  val fpType = BF16

  val io = IO(new Bundle {
    val in_a = Flipped(Decoupled(UInt(fpType.W)))
    val in_b = Flipped(Decoupled(UInt(fpType.W)))
    val mode = Input(Bool()) // 0: div, 1: sqrt
    val out  = Decoupled(UInt(fpType.W))
  })

  val sv_module = Module(new DivSqrtMvpBlackBox())
  sv_module.io.Clk_CI  := clock
  sv_module.io.Rst_RBI := !reset.asBool

  // Accept only when SV unit is ready and we can accept a new request (no unconsumed result).
  // Assert ready only when the other operand is valid so both transfer in the same cycle
  val canAccept = sv_module.io.Ready_SO && (!io.out.valid || io.out.ready)
  io.in_a.ready := canAccept && io.in_b.valid
  io.in_b.ready := canAccept && io.in_a.valid
  val fire = io.in_a.fire && io.in_b.fire

  sv_module.io.Operand_a_DI  := io.in_a.bits
  sv_module.io.Operand_b_DI  := io.in_b.bits
  sv_module.io.Div_start_SI  := fire && io.mode === 0.B
  sv_module.io.Sqrt_start_SI := fire && io.mode === 1.B

  val out_reg      = RegEnable(sv_module.io.Result_DO, sv_module.io.Done_SO)
  val outValid_reg = RegInit(false.B)
  when(sv_module.io.Done_SO) {
    outValid_reg := true.B
  }.elsewhen(io.out.fire) {
    outValid_reg := false.B
  }

  io.out.valid := outValid_reg
  io.out.bits  := out_reg

}

object FpDivSqrtMvpEmitter extends App {
  chisel3.emitVerilog(
    new FpDivSqrtMvp(),
    Array("--target-dir", "generated/fp_unit")
  )
}
