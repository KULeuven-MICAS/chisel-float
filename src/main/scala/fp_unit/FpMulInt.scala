// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Xiaoling Yi <xiaoling.yi@kuleuven.be>

package fp_unit

import chisel3._
import chisel3.util._
import chisel3.experimental.RawParam

/** @param typeA
  *   Must be FP16
  * @param typeB
  *   Configurable
  * @param typeC
  *   Must be FP32
  */
class FpMulIntBlackBox(topmodule: String, typeA: FpType = FP16, typeB: IntType, typeC: FpType = FP32)
    extends BlackBox(
      Map(
        "WIDTH_B"      -> typeB.width,
        "FpFormat_out" -> RawParam(typeC.fpnewFormatEnum)
      )
    )
    with HasBlackBoxResource {

  require(typeA == FP16, "FpMulInt only supports FP16 as FP input type")

  val io = IO(new Bundle {
    val operand_a_i = Input(UInt(typeA.width.W))
    val operand_b_i = Input(UInt(typeB.width.W))
    val result_o    = Output(UInt(typeC.width.W))
  })
  override def desiredName: String = topmodule

  addResource("common_block/fpnew_pkg_snax.sv")
  addResource("common_block/fpnew_classifier.sv")
  addResource("common_block/fpnew_rounding.sv")
  addResource("common_block/lzc.sv")
  addResource("common_block/int2fp.sv")
  addResource("fp_mul.sv")
  addResource("fp_mul_int.sv")
}

class FpMulInt(val typeA: FpType = FP16, val typeB: IntType, val typeC: FpType = FP32, topmodule: String = "fp_mul_int")
    extends Module
    with RequireAsyncReset {

  override def desiredName: String = "FpMulInt_" + topmodule

  val io = IO(new Bundle {
    val in_a = Input(UInt(typeA.width.W))
    val in_b = Input(UInt(typeB.width.W))
    val out  = Output(UInt(typeC.width.W))
  })

  val sv_module = Module(new FpMulIntBlackBox(topmodule, typeA, typeB, typeC))

  io.out                   := sv_module.io.result_o
  sv_module.io.operand_a_i := io.in_a
  sv_module.io.operand_b_i := io.in_b

}

object FpMulIntEmitter extends App {
  emitVerilog(
    new FpMulInt(topmodule = "fp_mul_int", typeB = Int16),
    Array("--target-dir", "generated/fp_unit")
  )
}
