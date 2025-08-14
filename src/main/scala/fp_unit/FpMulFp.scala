// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Xiaoling Yi <xiaoling.yi@kuleuven.be>
// Modified by: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._
import chisel3.experimental.RawParam
import chisel3.util._

class FpMulFpBlackBox(topmodule: String, typeA: FpType, typeB: FpType, typeC: FpType)
    extends BlackBox(
      Map(
        "FpFormat_a"   -> RawParam(typeA.fpnewFormatEnum),
        "FpFormat_b"   -> RawParam(typeB.fpnewFormatEnum),
        "FpFormat_out" -> RawParam(typeC.fpnewFormatEnum)
      )
    )
    with HasBlackBoxResource {

  val io = IO(new Bundle {
    val operand_a_i = Input(UInt(typeA.W))
    val operand_b_i = Input(UInt(typeB.W))
    val result_o    = Output(UInt(typeC.W))
  })
  override def desiredName: String = topmodule

  addResource("common_block/fpnew_pkg_snax.sv")
  addResource("common_block/fpnew_classifier.sv")
  addResource("common_block/fpnew_rounding.sv")
  addResource("common_block/lzc.sv")
  addResource("fp_mul.sv")

}

class FpMulFp(val typeA: FpType, val typeB: FpType, val typeC: FpType, modulename: String = "fp_mul")
    extends Module
    with RequireAsyncReset {

  val io = IO(new Bundle {
    val in_a = Input(UInt(typeA.W))
    val in_b = Input(UInt(typeB.W))
    val out  = Output(UInt(typeC.W))
  })

  val sv_module = Module(new FpMulFpBlackBox(modulename, typeA, typeB, typeC))

  io.out                   := sv_module.io.result_o
  sv_module.io.operand_a_i := io.in_a
  sv_module.io.operand_b_i := io.in_b

}

object FpMulFpEmitter extends App {
  emitVerilog(
    new FpMulFp(typeA = BF16, typeB = BF16, typeC = BF16),
    Array("--target-dir", "generated/fp_unit")
  )
}
