// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._
import chisel3.experimental.RawParam
import chisel3.util._

/** TODO separate output format is not supported yet
  */
class FpFmaBlackBox(modulename: String, typeA: FpType, typeB: FpType, typeC: FpType)
    extends BlackBox(
      Map(
        "FpFormat_a" -> RawParam(typeA.fpnewFormatEnum),
        "FpFormat_b" -> RawParam(typeB.fpnewFormatEnum),
        "FpFormat_c" -> RawParam(typeC.fpnewFormatEnum)
      )
    )
    with HasBlackBoxResource {

  val io = IO(new Bundle {
    val operand_a_i = Input(UInt(typeA.W))
    val operand_b_i = Input(UInt(typeB.W))
    val operand_c_i = Input(UInt(typeC.W))
    val result_o    = Output(UInt(typeC.W))
  })
  override def desiredName: String = modulename

  addResource("common_block/fpnew_pkg_snax.sv")
  addResource("common_block/fpnew_classifier.sv")
  addResource("common_block/fpnew_rounding.sv")
  addResource("common_block/lzc.sv")
  addResource("fp_fma.sv")

}

class FpFmaFp(val typeA: FpType, val typeB: FpType, val typeC: FpType, modulename: String = "fp_fma")
    extends Module
    with RequireAsyncReset {

  val outType = typeC

  val io = IO(new Bundle {
    val in_a = Input(UInt(typeA.W))
    val in_b = Input(UInt(typeB.W))
    val in_c = Input(UInt(typeC.W))
    val out  = Output(UInt(outType.W))
  })

  val sv_module = Module(new FpFmaBlackBox(modulename, typeA, typeB, typeC))
  sv_module.io.operand_a_i := io.in_a
  sv_module.io.operand_b_i := io.in_b
  sv_module.io.operand_c_i := io.in_c
  io.out                   := sv_module.io.result_o

}

object FpFmaFpEmitter extends App {
  emitVerilog(
    new FpFmaFp(typeA = BF16, typeB = BF16, typeC = BF16),
    Array("--target-dir", "generated/fp_unit")
  )
}
