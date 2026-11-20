// Copyright 2019 ETH Zurich and University of Bologna.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51
// Author: Stefan Mach <smach@iis.ee.ethz.ch>

// Copyright 2025 KU Leuven
// Modified by: Robin Geens <robin.geens@kuleuven.be>

// Converts between IEEE 754 formats, rounding to nearest, ties to even. NaNs become the canonical quiet NaN.
module fp_convert #(
    parameter fpnew_pkg_snax::fp_format_e FpFormat_in  = fpnew_pkg_snax::fp_format_e'(2),  //FP16 
    parameter fpnew_pkg_snax::fp_format_e FpFormat_out = fpnew_pkg_snax::fp_format_e'(0),  //FP32

    // Do not change
    parameter int unsigned WIDTH_in  = fpnew_pkg_snax::fp_width(FpFormat_in),
    parameter int unsigned WIDTH_out = fpnew_pkg_snax::fp_width(FpFormat_out)
) (
    // Input signals
    input  logic [ WIDTH_in-1:0] operand_a_i,
    // Output signals
    output logic [WIDTH_out-1:0] result_o

);

  // ----------
  // Constants
  // ----------
  // for operand A
  localparam int unsigned EXP_BITS_A = fpnew_pkg_snax::exp_bits(FpFormat_in);
  localparam int unsigned MAN_BITS_A = fpnew_pkg_snax::man_bits(FpFormat_in);
  localparam int unsigned BIAS_A = fpnew_pkg_snax::bias(FpFormat_in);

  // for operand C and result
  localparam int unsigned EXP_BITS_C = fpnew_pkg_snax::exp_bits(FpFormat_out);
  localparam int unsigned MAN_BITS_C = fpnew_pkg_snax::man_bits(FpFormat_out);
  localparam int unsigned BIAS_C = fpnew_pkg_snax::bias(FpFormat_out);

  localparam int unsigned PRECISION_BITS_A = MAN_BITS_A + 1;
  localparam int unsigned PRECISION_BITS_C = MAN_BITS_C + 1;

  // Holds every biased output exponent, including those of subnormal inputs and of results that overflow
  localparam int unsigned EXP_WIDTH = fpnew_pkg_snax::maximum(EXP_BITS_C, EXP_BITS_A) + 2;

  // ----------------
  // Type definition
  // ----------------
  typedef struct packed {
    logic                  sign;
    logic [EXP_BITS_A-1:0] exponent;
    logic [MAN_BITS_A-1:0] mantissa;
  } fp_a_t;


  typedef struct packed {
    logic                  sign;
    logic [EXP_BITS_C-1:0] exponent;
    logic [MAN_BITS_C-1:0] mantissa;
  } fp_t_out;


  // -----------------
  // Input processing
  // -----------------
  fpnew_pkg_snax::fp_info_t [1:0] info_q;
  fp_a_t                          operand_a;
  fpnew_pkg_snax::fp_info_t       info_a;


  // Classify input
  fpnew_classifier_snax #(
      .FpFormat   (FpFormat_in),
      .NumOperands(1)
  ) i_class_inputs_a (
      .operands_i(operand_a_i),
      .is_boxed_i(1'b1),
      .info_o    (info_q[0])
  );


  // Default assignments - packing-order-agnostic
  assign operand_a = operand_a_i;
  assign info_a    = info_q[0];


  // Input classification
  // ---------------------
  logic result_sign;
  assign result_sign = operand_a.sign;

  // ----------------------
  // Special case handling
  // ----------------------
  fp_t_out special_result;
  logic    result_is_special;

  always_comb begin : special_cases
    // Default assignments
    special_result = '{sign: 1'b0, exponent: '1, mantissa: 2 ** (MAN_BITS_C - 1)};  // canonical qNaN
    result_is_special = 1'b0;

    if (info_a.is_nan) begin
      // Any NaN becomes the canonical quiet NaN
      result_is_special = 1'b1;
    end else if (info_a.is_inf) begin
      // Infinity keeps its sign
      result_is_special = 1'b1;
      special_result = '{sign: operand_a.sign, exponent: '1, mantissa: '0};
    end
  end


  // -----------------------------------------------
  // Input mantissa normalization (subnormal inputs)
  // -----------------------------------------------
  // Shift the leading one of a subnormal mantissa up to the hidden-bit position
  logic [        PRECISION_BITS_A-1:0] mantissa_a_raw;
  logic [        PRECISION_BITS_A-1:0] mantissa_a;
  logic [$clog2(PRECISION_BITS_A)-1:0] lzc_a;

  assign mantissa_a_raw = {info_a.is_normal, operand_a.mantissa};

  lzc_snax #(
      .WIDTH(PRECISION_BITS_A),
      .MODE (1)                  // MODE = 1 counts leading zeroes
  ) i_lzc_mantissa_a (
      .in_i   (mantissa_a_raw),
      .cnt_o  (lzc_a),
      .empty_o()
  );

  assign mantissa_a = mantissa_a_raw << lzc_a;

  // ---------------------------
  // Initial exponent data path
  // ---------------------------
  logic signed [ EXP_BITS_A:0] exponent_a;
  logic signed [EXP_WIDTH-1:0] exponent_product;  // biased in the output format, of the mantissa_a MSB

  assign exponent_a = signed'({1'b0, operand_a.exponent});

  assign exponent_product = (info_a.is_zero) ? 2 - signed'(BIAS_C)
      : signed'(exponent_a + info_a.is_subnormal - signed'({1'b0, lzc_a}) - signed'(BIAS_A) + signed'(BIAS_C));

  // --------------
  // Normalization
  // --------------
  // The window holds the full input mantissa plus the output mantissa+round positions, with >= 2 sticky bits
  localparam int unsigned NORM_WIDTH =
      ((PRECISION_BITS_A > PRECISION_BITS_C + 1) ? PRECISION_BITS_A : (PRECISION_BITS_C + 1)) + 2;
  localparam int unsigned STICKY_BIT_WIDTH = NORM_WIDTH - (PRECISION_BITS_C + 1);  // >= 2 by construction
  localparam int unsigned DENORM_SHAMT_WIDTH = $clog2(NORM_WIDTH + 1);

  logic signed [       EXP_WIDTH-1:0] normalized_exponent;
  logic signed [       EXP_WIDTH-1:0] final_exponent;
  logic        [      NORM_WIDTH-1:0] mantissa_aligned;  // mantissa_a MSB at the window top
  logic        [      NORM_WIDTH-1:0] mantissa_shifted;
  logic        [  PRECISION_BITS_C:0] final_mantissa;
  logic        [STICKY_BIT_WIDTH-1:0] sum_sticky_bits;
  logic                               sticky_after_norm;
  logic                               denorm_sticky;

  assign mantissa_aligned = {mantissa_a, {(NORM_WIDTH - PRECISION_BITS_A) {1'b0}}};

  // Subnormal results: shift right by 1 - exponent_product (clamped); dropped bits join the sticky bit
  logic signed [         EXP_WIDTH-1:0] denorm_shift;
  logic        [DENORM_SHAMT_WIDTH-1:0] denorm_shamt;
  logic        [        NORM_WIDTH-1:0] denorm_kept;
  logic        [        NORM_WIDTH-1:0] denorm_dropped;

  assign denorm_shift = 1 - exponent_product;  // >= 1 whenever the subnormal branch is taken
  assign denorm_shamt = (denorm_shift <= 0) ? '0
      : (denorm_shift >= signed'(NORM_WIDTH)) ? DENORM_SHAMT_WIDTH'(NORM_WIDTH)
      : denorm_shift[DENORM_SHAMT_WIDTH-1:0];
  assign {denorm_kept, denorm_dropped} = {mantissa_aligned, {NORM_WIDTH{1'b0}}} >> denorm_shamt;

  always_comb begin : norm_shift_amount
    if ((exponent_product > 0) && !info_a.is_zero) begin
      // Normal result: the hidden bit is already at the window MSB
      normalized_exponent = exponent_product;
      mantissa_shifted = mantissa_aligned;
      denorm_sticky = 1'b0;
    end else begin
      // Subnormal (or zero) result. Exponent is 0
      normalized_exponent = 0;
      mantissa_shifted = denorm_kept;
      denorm_sticky = |denorm_dropped;
    end
  end

  always_comb begin : small_norm
    {final_mantissa, sum_sticky_bits} = mantissa_shifted;
    final_exponent = normalized_exponent;
  end

  // Update the sticky bit with the shifted-out bits
  assign sticky_after_norm = (|sum_sticky_bits) | denorm_sticky;

  // ----------------------------
  // Rounding and classification
  // ----------------------------
  logic                             pre_round_sign;
  logic [           EXP_BITS_C-1:0] pre_round_exponent;
  logic [           MAN_BITS_C-1:0] pre_round_mantissa;
  logic [EXP_BITS_C+MAN_BITS_C-1:0] pre_round_abs;  // absolute value of result before rounding
  logic [                      1:0] round_sticky_bits;

  logic of_before_round, of_after_round;  // overflow
  logic uf_before_round, uf_after_round;  // underflow
  logic                             result_zero;

  logic                             rounded_sign;
  logic [EXP_BITS_C+MAN_BITS_C-1:0] rounded_abs;  // absolute value of result after rounding

  // Classification before round. RISC-V mandates checking underflow AFTER rounding!
  assign of_before_round = final_exponent >= 2 ** (EXP_BITS_C) - 1;  // infinity exponent is all ones
  assign uf_before_round = final_exponent == 0;  // exponent for subnormals capped to 0

  // Assemble result before rounding. In case of overflow, the largest normal value is set.
  assign pre_round_sign = result_sign;
  assign pre_round_exponent = (of_before_round) ? 2 ** EXP_BITS_C - 2 : unsigned'(final_exponent[EXP_BITS_C-1:0]);
  // Discard implicit leading bit. Bit 0 is R bit
  assign pre_round_mantissa = (of_before_round) ? '1 : final_mantissa[MAN_BITS_C:1];
  assign pre_round_abs = {pre_round_exponent, pre_round_mantissa};

  // In case of overflow, the round and sticky bits are set for proper rounding
  assign round_sticky_bits = (of_before_round) ? 2'b11 : {final_mantissa[0], sticky_after_norm};

  // Perform rounding
  fpnew_rounding_snax #(
      .AbsWidth(EXP_BITS_C + MAN_BITS_C)
  ) i_fpnew_rounding_snax (
      .abs_value_i            (pre_round_abs),
      .sign_i                 (pre_round_sign),
      .round_sticky_bits_i    (round_sticky_bits),
      .rnd_mode_i             (fpnew_pkg_snax::RNE),
      .effective_subtraction_i(1'b0),                 // no subtraction
      .abs_rounded_o          (rounded_abs),
      .sign_o                 (rounded_sign),
      .exact_zero_o           (result_zero)
  );

  // -----------------
  // Result selection
  // -----------------
  logic [WIDTH_out-1:0] regular_result;

  assign regular_result    = {rounded_sign, rounded_abs};
  assign result_o = result_is_special ? special_result : regular_result;


endmodule
