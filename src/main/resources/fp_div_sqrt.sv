// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51
//
// Author: Robin Geens <robin.geens@kuleuven.be>

// One lane of CVFPU's THMULTI division and square root unit, rounding to nearest, ties to even.
// Connected as fpnew_opgroup_multifmt_slice connects a single scalar lane.
module fp_div_sqrt #(
  parameter fpnew_pkg_snax::fp_format_e FpFormat = fpnew_pkg_snax::FP16ALT,
  // Do not change
  localparam int unsigned WIDTH = fpnew_pkg_snax::fp_width(FpFormat)
) (
  input  logic             clk_i,
  input  logic             rst_ni,
  input  logic [WIDTH-1:0] operand_a_i,
  input  logic [WIDTH-1:0] operand_b_i,
  input  logic             is_sqrt_i,
  input  logic             in_valid_i,
  output logic             in_ready_o,
  output logic [WIDTH-1:0] result_o,
  output logic             out_valid_o,
  input  logic             out_ready_i
);

  function automatic fpnew_pkg_snax::fmt_logic_t only_format(fpnew_pkg_snax::fp_format_e fmt);
    only_format      = '0;
    only_format[fmt] = 1'b1;
  endfunction

  // pragma translate_off
  initial begin
    if (!(FpFormat inside {fpnew_pkg_snax::FP32, fpnew_pkg_snax::FP16, fpnew_pkg_snax::FP16ALT}))
      $fatal(1, "fp_div_sqrt supports FP32, FP16 and FP16ALT only");
  end
  // pragma translate_on

  logic divsqrt_done, divsqrt_ready;

  fpnew_divsqrt_th_64_multi_snax #(
    .FpFmtConfig ( only_format(FpFormat) ),
    .NumPipeRegs ( 0                     ),
    .PipeConfig  ( fpnew_pkg_snax::AFTER )
  ) i_divsqrt (
    .clk_i,
    .rst_ni,
    .operands_i        ( {operand_b_i, operand_a_i}                               ),
    .is_boxed_i        ( '1                                                       ),
    .rnd_mode_i        ( fpnew_pkg_snax::RNE                                      ),
    .op_i              ( is_sqrt_i ? fpnew_pkg_snax::SQRT : fpnew_pkg_snax::DIV  ),
    .dst_fmt_i         ( FpFormat                                                 ),
    .tag_i             ( 1'b0                                                     ),
    .mask_i            ( 1'b1                                                     ),
    .aux_i             ( 1'b0                                                     ),
    .vectorial_op_i    ( 1'b0                                                     ),
    .in_valid_i,
    .in_ready_o,
    .divsqrt_done_o    ( divsqrt_done                                             ),
    .simd_synch_done_i ( divsqrt_done                                             ),
    .divsqrt_ready_o   ( divsqrt_ready                                            ),
    .simd_synch_rdy_i  ( divsqrt_ready                                            ),
    .flush_i           ( 1'b0                                                     ),
    .result_o,
    .status_o          (                                                          ),
    .extension_bit_o   (                                                          ),
    .tag_o             (                                                          ),
    .mask_o            (                                                          ),
    .aux_o             (                                                          ),
    .out_valid_o,
    .out_ready_i,
    .busy_o            (                                                          ),
    .reg_ena_i         ( 1'b0                                                     ),
    .early_out_valid_o (                                                          )
  );

endmodule
