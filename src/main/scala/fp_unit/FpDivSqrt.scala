// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51
//
// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._
import chisel3.experimental.RawParam
import chisel3.util._

class FpDivSqrtBlackBox(fpType: FpType)
    extends BlackBox(Map("FpFormat" -> RawParam(fpType.fpnewFormatEnum)))
    with HasBlackBoxResource {

  FpDivSqrt.checkType(fpType)

  val io = IO(new Bundle {
    val clk_i       = Input(Clock())
    val rst_ni      = Input(Bool())
    val operand_a_i = Input(UInt(fpType.W))
    val operand_b_i = Input(UInt(fpType.W))
    val is_sqrt_i   = Input(Bool())
    val in_valid_i  = Input(Bool())
    val in_ready_o  = Output(Bool())
    val result_o    = Output(UInt(fpType.W))
    val out_valid_o = Output(Bool())
    val out_ready_i = Input(Bool())
  })

  override def desiredName: String = "fp_div_sqrt"

  addResource("common_block/fpnew_pkg_snax.sv")
  Seq(
    "gated_clk_cell",
    "ct_vfdsu_ctrl",
    "ct_vfdsu_double",
    "ct_vfdsu_ff1",
    "ct_vfdsu_pack",
    "ct_vfdsu_prepare",
    "ct_vfdsu_round",
    "ct_vfdsu_scalar_dp",
    "ct_vfdsu_srt",
    "ct_vfdsu_srt_radix16_bound_table",
    "ct_vfdsu_srt_radix16_with_sqrt",
    "ct_vfdsu_top"
  ).foreach(name => addResource(s"openc910/$name.v"))
  addResource("fpnew_divsqrt_th_64_multi.sv")
  addResource("fp_div_sqrt.sv")
}

object FpDivSqrt {
  val supportedTypes: Seq[FpType] = Seq(BF16, FP16, FP32)

  def checkType(fpType: FpType): Unit =
    require(supportedTypes.contains(fpType), s"FpDivSqrt supports BF16, FP16 and FP32 only, got $fpType")
}

/** Division and square root on CVFPU's THMULTI unit; see the README for the handshake and latency. */
class FpDivSqrt(val fpType: FpType = BF16) extends Module with RequireAsyncReset {

  FpDivSqrt.checkType(fpType)

  val io = IO(new Bundle {
    val in_a = Flipped(Decoupled(UInt(fpType.W)))
    val in_b = Flipped(Decoupled(UInt(fpType.W)))
    val mode = Input(Bool()) // 0: div, 1: sqrt
    val out  = Decoupled(UInt(fpType.W))
  })

  val sv_module = Module(new FpDivSqrtBlackBox(fpType))
  sv_module.io.clk_i  := clock
  sv_module.io.rst_ni := !reset.asBool

  val isSqrt = io.mode

  // The operands of a division transfer in the same cycle
  val allValid = io.in_a.valid && (io.in_b.valid || isSqrt)
  io.in_a.ready := sv_module.io.in_ready_o && (!io.in_a.valid || allValid)
  io.in_b.ready := sv_module.io.in_ready_o && (!io.in_b.valid || allValid) && !isSqrt

  sv_module.io.in_valid_i  := allValid
  sv_module.io.operand_a_i := io.in_a.bits
  sv_module.io.operand_b_i := io.in_b.bits
  sv_module.io.is_sqrt_i   := isSqrt

  io.out.valid             := sv_module.io.out_valid_o
  io.out.bits              := sv_module.io.result_o
  sv_module.io.out_ready_i := io.out.ready
}

object FpDivSqrtEmitter extends App {
  FpEmitter(new FpDivSqrt(), "FpDivSqrt")
}
