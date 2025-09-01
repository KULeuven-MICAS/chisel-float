// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import chisel3._

abstract class DataType {
  def width: Int
  def W:     Width = width.W
}

abstract class FpType extends DataType {
  val expWidth: Int
  val sigWidth: Int
  def width = expWidth + sigWidth + 1
  // Corresponding enum name in fpnew_pkg_snax::fp_format_e
  val fpnewFormatEnum: String
}

class IntType(val width: Int) extends DataType

object FP16 extends FpType {
  val expWidth        = 5
  val sigWidth        = 10
  val fpnewFormatEnum = "fpnew_pkg_snax::FP16"
}

object FP32 extends FpType {
  val expWidth        = 8
  val sigWidth        = 23
  val fpnewFormatEnum = "fpnew_pkg_snax::FP32"

}
object BF16 extends FpType {
  val expWidth        = 8
  val sigWidth        = 7
  val fpnewFormatEnum = "fpnew_pkg_snax::FP16ALT"
}

object FP8 extends FpType {
  val expWidth        = 5
  val sigWidth        = 2
  val fpnewFormatEnum = "fpnew_pkg_snax::FP8"
}

object Int1  extends IntType(1)
object Int2  extends IntType(2)
object Int3  extends IntType(3)
object Int4  extends IntType(4)
object Int8  extends IntType(8)
object Int16 extends IntType(16)
object Int32 extends IntType(32)
