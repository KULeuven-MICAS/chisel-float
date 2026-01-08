// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._
import chisel3.experimental.RawParam
import chisel3.util._

class FpConvertBlackBox(typeA: FpType, typeC: FpType)
    extends BlackBox(
      Map(
        "FpFormat_in"  -> RawParam(typeA.fpnewFormatEnum),
        "FpFormat_out" -> RawParam(typeC.fpnewFormatEnum)
      )
    )
    with HasBlackBoxResource {

  require(typeA.isIEEE754 && typeC.isIEEE754, "only supports IEEE 754 compliant floating point conversions")

  val io = IO(new Bundle {
    val operand_a_i = Input(UInt(typeA.W))
    val result_o    = Output(UInt(typeC.W))
  })
  override def desiredName: String = "fp_convert"

  addResource("common_block/fpnew_pkg_snax.sv")
  addResource("common_block/fpnew_classifier.sv")
  addResource("common_block/fpnew_rounding.sv")
  addResource("common_block/lzc.sv")
  addResource("fp_convert.sv")

}

class FpConvertBlackBoxToAlt(typeA: FpType, typeC: FpType)
    extends BlackBox(
      Map(
        "FpFormat_in"  -> RawParam(typeA.fpnewFormatEnum),
        "FpFormat_out" -> RawParam(typeC.fpnewFormatEnum)
      )
    )
    with HasBlackBoxResource {

  require(
    typeA.isIEEE754 && !typeC.isIEEE754,
    "only supports IEEE 754 to non-IEEE 754 compliant floating point conversions"
  )

  val io = IO(new Bundle {
    val operand_a_i = Input(UInt(typeA.W))
    val result_o    = Output(UInt(typeC.W))
  })
  override def desiredName: String = "fp_convert_to_alt"

  addResource("common_block/fpnew_pkg_snax.sv")
  addResource("common_block/fpnew_classifier.sv")
  addResource("common_block/fpnew_rounding.sv")
  addResource("common_block/lzc.sv")
  addResource("fp_convert_to_alt.sv")
}

class FpConvertBlackBoxFromAlt(typeA: FpType, typeC: FpType)
    extends BlackBox(
      Map(
        "FpFormat_in"  -> RawParam(typeA.fpnewFormatEnum),
        "FpFormat_out" -> RawParam(typeC.fpnewFormatEnum)
      )
    )
    with HasBlackBoxResource {

  require(
    !typeA.isIEEE754 && typeC.isIEEE754,
    "only supports IEEE 754 to non-IEEE 754 compliant floating point conversions"
  )

  val io = IO(new Bundle {
    val operand_a_i = Input(UInt(typeA.W))
    val result_o    = Output(UInt(typeC.W))
  })
  override def desiredName: String = "fp_convert_from_alt"

  addResource("common_block/fpnew_pkg_snax.sv")
  addResource("common_block/fpnew_classifier.sv")
  addResource("common_block/fpnew_rounding.sv")
  addResource("common_block/lzc.sv")
  addResource("fp_convert_from_alt.sv")
}

class FpConvert(val typeA: FpType, val typeC: FpType) extends Module with RequireAsyncReset {

  val io = IO(new Bundle {
    val in_a = Input(UInt(typeA.W))
    val out  = Output(UInt(typeC.W))
  })

  if (typeA == typeC) {
    io.out := io.in_a
  } else if (typeA.isIEEE754 && typeC.isIEEE754) {
    val sv_module = Module(new FpConvertBlackBox(typeA, typeC))
    sv_module.io.operand_a_i := io.in_a
    io.out                   := sv_module.io.result_o
  } else if (typeA.isIEEE754 && !typeC.isIEEE754) {
    val sv_module = Module(new FpConvertBlackBoxToAlt(typeA, typeC))
    sv_module.io.operand_a_i := io.in_a
    io.out                   := sv_module.io.result_o
  } else if (!typeA.isIEEE754 && typeC.isIEEE754) {
    val sv_module = Module(new FpConvertBlackBoxFromAlt(typeA, typeC))
    sv_module.io.operand_a_i := io.in_a
    io.out                   := sv_module.io.result_o
  } else {
    throw new IllegalArgumentException(s"Unsupported conversion from ${typeA} to ${typeC}")
  }

}

object FpConvertEmitter extends App {
  emitVerilog(
    new FpConvert(typeA = FP16, typeC = FP8),
    Array("--target-dir", "generated/fp_unit")
  )
}
