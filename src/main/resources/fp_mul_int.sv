// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

module fp_mul_int #(
    parameter fpnew_pkg_snax::fp_format_e  FpFormat_a   = fpnew_pkg_snax::fp_format_e'(2),   //FP16 DO NOT CHANGE
    parameter fpnew_pkg_snax::int_format_e IntFormat_b  = fpnew_pkg_snax::int_format_e'(4),  //int4
    parameter fpnew_pkg_snax::fp_format_e  FpFormat_out = fpnew_pkg_snax::fp_format_e'(0),   //FP32

    parameter int unsigned WIDTH_a = fpnew_pkg_snax::fp_width(FpFormat_a),  // do not change
    parameter int unsigned WIDTH_B = fpnew_pkg_snax::fp_width(IntFormat_b),  // do not change
    parameter int unsigned WIDTH_out = fpnew_pkg_snax::fp_width(FpFormat_out)  // do not change
) (
    // I/O signals
    input  logic [  WIDTH_a-1:0] operand_a_i,  
    input  logic [  WIDTH_B-1:0] operand_b_i,  
    output logic [WIDTH_out-1:0] result_o
);


  logic [WIDTH_a-1:0] operand_b_i_fp;

  intN_to_fp16 #(
      .INT_WIDTH(WIDTH_B)
  ) Int2fp (
      .intN_in (operand_b_i),
      .fp16_out(operand_b_i_fp)
  );

  fp_mul #(
      .FpFormat_a (FpFormat_a),
      .FpFormat_b(FpFormat_a),
      .FpFormat_out(FpFormat_out)
  ) fp_mul_fp (
      .operand_a_i(operand_a_i),
      .operand_b_i(operand_b_i_fp),
      .result_o   (result_o)
  );

endmodule
