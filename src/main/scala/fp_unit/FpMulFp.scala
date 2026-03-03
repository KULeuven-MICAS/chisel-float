// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Xiaoling Yi <xiaoling.yi@kuleuven.be>
// Modified by: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._
import chisel3.experimental.RawParam
import chisel3.util._

class FpMulFpBlackBox(typeA: FpType, typeB: FpType, typeC: FpType)
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
  override def desiredName: String = "fp_mul"

  addResource("common_block/fpnew_pkg_snax.sv")
  addResource("common_block/fpnew_classifier.sv")
  addResource("common_block/fpnew_rounding.sv")
  addResource("common_block/lzc.sv")
  addResource("fp_mul.sv")

}

/** For non-IEEE 754 compliant input multiplication with IEEE754-compliant output. */
class FpMulFpAltToIEEEBlackBox(typeA: FpType, typeB: FpType, typeC: FpType)
    extends BlackBox(
      Map(
        "FpFormat_a"   -> RawParam(typeA.fpnewFormatEnum),
        "FpFormat_b"   -> RawParam(typeB.fpnewFormatEnum),
        "FpFormat_out" -> RawParam(typeC.fpnewFormatEnum)
      )
    )
    with HasBlackBoxResource {
  require(!typeA.isIEEE754 && !typeB.isIEEE754 && typeC.isIEEE754)

  val io = IO(new Bundle {
    val operand_a_i = Input(UInt(typeA.W))
    val operand_b_i = Input(UInt(typeB.W))
    val result_o    = Output(UInt(typeC.W))
  })
  override def desiredName: String = "fp_mul_alt"

  addResource("common_block/fpnew_pkg_snax.sv")
  addResource("common_block/fpnew_classifier.sv")
  addResource("common_block/fpnew_rounding.sv")
  addResource("common_block/lzc.sv")
  addResource("fp_mul_alt.sv")

}

/** For non-IEEE 754 compliant input multiplication with non-IEEE754-compliant output. */
class FpMulFpAltToAltBlackBox(typeA: FpType, typeB: FpType, typeC: FpType)
    extends BlackBox(
      Map(
        "FpFormat_a"   -> RawParam(typeA.fpnewFormatEnum),
        "FpFormat_b"   -> RawParam(typeB.fpnewFormatEnum),
        "FpFormat_out" -> RawParam(typeC.fpnewFormatEnum)
      )
    )
    with HasBlackBoxResource {

  require(!typeA.isIEEE754 && !typeB.isIEEE754 && !typeC.isIEEE754)

  val io = IO(new Bundle {
    val operand_a_i = Input(UInt(typeA.W))
    val operand_b_i = Input(UInt(typeB.W))
    val result_o    = Output(UInt(typeC.W))
  })
  override def desiredName: String = "fp_mul_alt_to_alt"

  addResource("common_block/fpnew_pkg_snax.sv")
  addResource("common_block/fpnew_classifier.sv")
  addResource("common_block/fpnew_rounding.sv")
  addResource("common_block/lzc.sv")
  addResource("fp_mul_alt_to_alt.sv")

}

class FpMulFp(val typeA: FpType, val typeB: FpType, val typeC: FpType) extends Module with RequireAsyncReset {
  require(typeA.isIEEE754 == typeB.isIEEE754, "IEEE 754 compliance of both operands must be the same")

  val io = IO(new Bundle {
    val in_a = Input(UInt(typeA.W))
    val in_b = Input(UInt(typeB.W))
    val out  = Output(UInt(typeC.W))
  })

  if (typeA.isIEEE754) {
    val sv_module = Module(new FpMulFpBlackBox(typeA, typeB, typeC))
    io.out                   := sv_module.io.result_o
    sv_module.io.operand_a_i := io.in_a
    sv_module.io.operand_b_i := io.in_b

  } else if (typeC.isIEEE754) {
    val sv_module = Module(new FpMulFpAltToIEEEBlackBox(typeA, typeB, typeC))
    io.out                   := sv_module.io.result_o
    sv_module.io.operand_a_i := io.in_a
    sv_module.io.operand_b_i := io.in_b

  } else {
    val sv_module = Module(new FpMulFpAltToAltBlackBox(typeA, typeB, typeC))
    io.out                   := sv_module.io.result_o
    sv_module.io.operand_a_i := io.in_a
    sv_module.io.operand_b_i := io.in_b
  }
}

object FpMulFpEmitter extends App {
  emitVerilog(
    new FpMulFp(typeA = FP8_ALT, typeB = FP8_ALT, typeC = BF16),
    Array("--target-dir", "generated/fp_unit")
  )
}
