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
