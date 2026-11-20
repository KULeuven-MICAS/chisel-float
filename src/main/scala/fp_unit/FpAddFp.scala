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

  require(
    typeA.isIEEE754 && typeB.isIEEE754 && typeC.isIEEE754,
    s"FpAddFp supports IEEE 754 formats only, got $typeA + $typeB -> $typeC"
  )

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

/** Combinational adder: out = in_a + in_b, rounded once into typeC. */
class FpAddFp(val typeA: FpType, val typeB: FpType, val typeC: FpType, modulename: String = "fp_add") extends Module {

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

/** [[FpAddFp]] between input and output register stages, for synthesis experiments. */
class FpAddFpSequential(
  typeA:      FpType,
  typeB:      FpType,
  typeC:      FpType,
  nPipesPre:  Int = 1,
  nPipesPost: Int = 1,
  modulename: String
) extends Module {
  override def desiredName: String = modulename

  val io = IO(new Bundle {
    val in_a = Input(UInt(typeA.W))
    val in_b = Input(UInt(typeB.W))
    val out  = Output(UInt(typeC.W))
  })

  val fpAddFp = Module(new FpAddFp(typeA, typeB, typeC))

  fpAddFp.io.in_a := ShiftRegister(io.in_a, nPipesPre)
  fpAddFp.io.in_b := ShiftRegister(io.in_b, nPipesPre)
  io.out          := ShiftRegister(fpAddFp.io.out, nPipesPost)

}

object FpAddFpEmitter extends App {
  FpEmitter(new FpAddFp(typeA = FP16, typeB = FP16, typeC = FP16), "FpAddFp")
}

object FpAddFpSequentialEmitter extends App {
  for {
    nPipesPre  <- 1 to 3
    nPipesPost <- 1 to 3
  } {
    val name = s"fp_add_${nPipesPre}_${nPipesPost}"
    FpEmitter(
      new FpAddFpSequential(typeA = FP16, typeB = FP16, typeC = FP16, nPipesPre, nPipesPost, modulename = name),
      name
    )
  }
}
