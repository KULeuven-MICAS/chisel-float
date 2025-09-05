// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Xiaoling Yi <xiaoling.yi@kuleuven.be>
// Modified by: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._
import chisel3.experimental.RawParam
import chisel3.util._

class FpAddFpBlackBox(modulename: String, typeA: FpType, typeB: FpType, typeC: FpType)
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
  override def desiredName: String = modulename

  addResource("common_block/fpnew_pkg_snax.sv")
  addResource("common_block/fpnew_classifier.sv")
  addResource("common_block/fpnew_rounding.sv")
  addResource("common_block/lzc.sv")
  addResource("fp_add.sv")

}

/** TODO why don't we use the blackbox directly? */
class FpAddFp(val typeA: FpType, val typeB: FpType, val typeC: FpType, modulename: String = "fp_add")
    extends Module
    with RequireAsyncReset {

  val io = IO(new Bundle {
    val in_a = Input(UInt(typeA.W))
    val in_b = Input(UInt(typeB.W))
    val out  = Output(UInt(typeC.W))
  })

  val sv_module = Module(new FpAddFpBlackBox(modulename, typeA, typeB, typeC))

  io.out                   := sv_module.io.result_o
  sv_module.io.operand_a_i := io.in_a
  sv_module.io.operand_b_i := io.in_b

}

/** Pipeline a single FpAddFp module. Mainly used for separate synthesis experiments. */
class FpAddFpSequential(
  typeA:      FpType,
  typeB:      FpType,
  typeC:      FpType,
  nPipesPre:  Int = 1,
  nPipesPost: Int = 1,
  modulename: String
) extends Module
    with RequireAsyncReset {
  override def desiredName: String = modulename

  val io = IO(new Bundle {
    val in_a = Input(UInt(typeA.W))
    val in_b = Input(UInt(typeB.W))
    val out  = Output(UInt(typeC.W))
  })

  val fpAddFp = Module(new FpAddFp(typeA, typeB, typeC, s"${modulename}_comb"))

  fpAddFp.io.in_a := ShiftRegister(io.in_a, nPipesPre)
  fpAddFp.io.in_b := ShiftRegister(io.in_b, nPipesPre)
  io.out          := ShiftRegister(fpAddFp.io.out, nPipesPost)

}

object FpAddFpEmitter extends App {
  emitVerilog(
    new FpAddFp(typeA = BF16, typeB = BF16, typeC = BF16),
    Array("--target-dir", "generated/fp_unit")
  )
}

object FpAddFpSequentialEmitter extends App {
  for {
    nPipesPre  <- 1 to 3
    nPipesPost <- 1 to 3
  } {
    emitVerilog(
      new FpAddFpSequential(
        typeA      = FP16,
        typeB      = FP16,
        typeC      = FP16,
        nPipesPre,
        nPipesPost,
        modulename = s"fp_add_${nPipesPre}_${nPipesPost}"
      ),
      Array("--target-dir", "generated/fp_unit")
    )
  }
}
