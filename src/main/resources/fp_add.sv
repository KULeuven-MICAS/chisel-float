// Copyright 2019 ETH Zurich and University of Bologna.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51
// Author: Stefan Mach <smach@iis.ee.ethz.ch>

// Copyright 2025 KU Leuven
// Modified by: Man Shi <man.shi@kuleuven.be>
//              Robin Geens <robin.geens@kuleuven.be>
// Changes: allow for different a, b, and out data types; remove multiplier.

module fp_add #(
    parameter fpnew_pkg_snax::fp_format_e FpFormat_a   = fpnew_pkg_snax::fp_format_e'(0),
    parameter fpnew_pkg_snax::fp_format_e FpFormat_b   = fpnew_pkg_snax::fp_format_e'(0),
    parameter fpnew_pkg_snax::fp_format_e FpFormat_out = fpnew_pkg_snax::fp_format_e'(0),

    parameter int unsigned WIDTH_A   = fpnew_pkg_snax::fp_width(FpFormat_a),
    parameter int unsigned WIDTH_B   = fpnew_pkg_snax::fp_width(FpFormat_b),
    parameter int unsigned WIDTH_out = fpnew_pkg_snax::fp_width(FpFormat_out)
) (
    input  logic [  WIDTH_A-1:0] operand_a_i,
    input  logic [  WIDTH_B-1:0] operand_b_i,
    output logic [WIDTH_out-1:0] result_o
);

  // ----------
  // Constants
  // ----------
  // for operand A
  localparam int unsigned EXP_BITS_A = fpnew_pkg_snax::exp_bits(FpFormat_a);
  localparam int unsigned MAN_BITS_A = fpnew_pkg_snax::man_bits(FpFormat_a);
  localparam int unsigned BIAS_A = fpnew_pkg_snax::bias(FpFormat_a);
  // for operand B
  localparam int unsigned EXP_BITS_B = fpnew_pkg_snax::exp_bits(FpFormat_b);
  localparam int unsigned MAN_BITS_B = fpnew_pkg_snax::man_bits(FpFormat_b);
  localparam int unsigned BIAS_B = fpnew_pkg_snax::bias(FpFormat_b);
  // for operand C and result
  localparam int unsigned EXP_BITS_C = fpnew_pkg_snax::exp_bits(FpFormat_out);
  localparam int unsigned MAN_BITS_C = fpnew_pkg_snax::man_bits(FpFormat_out);
  localparam int unsigned BIAS_C = fpnew_pkg_snax::bias(FpFormat_out);

  localparam int unsigned PRECISION_BITS_A = MAN_BITS_A + 1;
  localparam int unsigned PRECISION_BITS_B = MAN_BITS_B + 1;
  localparam int unsigned PRECISION_BITS_C = MAN_BITS_C + 1;
  localparam int unsigned EXP_BITS_MAX = fpnew_pkg_snax::maximum(
      EXP_BITS_A, fpnew_pkg_snax::maximum(EXP_BITS_B, EXP_BITS_C)
  );

  // Internal precision covers the widest operand so no input bits are dropped
  localparam int unsigned PRECISION_BITS_MAX = fpnew_pkg_snax::maximum(
      PRECISION_BITS_A, fpnew_pkg_snax::maximum(PRECISION_BITS_B, PRECISION_BITS_C)
  );

  // Datapath width of the addends; also the alignment-shift cap, beyond which the shifted
  // operand is entirely collected into the sticky bit
  localparam int unsigned DP_WIDTH = 2 * PRECISION_BITS_MAX + 2;
  localparam int unsigned SHIFT_AMOUNT_WIDTH = $clog2(DP_WIDTH + 1);


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
    logic [EXP_BITS_B-1:0] exponent;
    logic [MAN_BITS_B-1:0] mantissa;
  } fp_b_t;

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
  fp_b_t                          operand_b;
  fpnew_pkg_snax::fp_info_t info_a, info_b;


  // Classify input
  fpnew_classifier_snax #(
      .FpFormat   (FpFormat_a),
      .NumOperands(1)
  ) i_class_inputs_a (
      .operands_i(operand_a_i),
      .is_boxed_i(1'b1),
      .info_o    (info_q[0])
  );

  fpnew_classifier_snax #(
      .FpFormat   (FpFormat_b),
      .NumOperands(1)
  ) i_class_inputs_b (
      .operands_i(operand_b_i),
      .is_boxed_i(1'b1),
      .info_o    (info_q[1])
  );

  // Default assignments - packing-order-agnostic
  assign operand_a = operand_a_i;
  assign operand_b = operand_b_i;
  assign info_a    = info_q[0];
  assign info_b    = info_q[1];


  // Input classification
  // ---------------------
  logic any_operand_inf;
  logic any_operand_nan;
  logic signalling_nan;
  logic effective_subtraction;
  logic tentative_sign;

  // Reduction for special case handling
  assign any_operand_inf       = (|{info_a.is_inf, info_b.is_inf});
  assign any_operand_nan       = (|{info_a.is_nan, info_b.is_nan});
  assign signalling_nan        = (|{info_a.is_signalling, info_b.is_signalling});
  // Effective subtraction in FMA occurs when product and addend signs differ

  assign effective_subtraction = operand_a.sign ^ operand_b.sign;
  assign tentative_sign        = operand_a.sign;

  // ----------------------
  // Special case handling
  // ----------------------
  fp_t_out special_result;
  logic    result_is_special;

  always_comb begin : special_cases
    // Default assignments
    special_result = '{  // canonical qNaN
        sign: 1'b0,
        exponent: '1,
        mantissa: 2 ** (MAN_BITS_C - 1)
    };
    result_is_special = 1'b0;
    if (info_a.is_nan || info_b.is_nan) result_is_special = 1'b1;
    else if (info_a.is_zero && info_b.is_zero) begin
      // The case where one of the operands is zero is handled as regular case.
      // The result is -0 only if both operands are -0
      result_is_special = 1'b1;
      special_result = '{sign: operand_a.sign & operand_b.sign, exponent: '0, mantissa: '0};
    end else if (info_a.is_inf && info_b.is_inf && effective_subtraction) begin
      result_is_special = 1'b1;
    end else if (info_a.is_inf) begin
      result_is_special = 1'b1;
      special_result = '{  //
          sign: operand_a.sign,
          exponent: '{default: 1'b1},
          mantissa: 0
      };
    end else if (info_b.is_inf) begin
      result_is_special = 1'b1;
      special_result = '{  // 
          sign: operand_b.sign,
          exponent: '{default: 1'b1},
          mantissa: 0
      };
    end
  end


  // ---------------------------
  // Initial exponent data path
  // ---------------------------
  logic signed [  EXP_BITS_A:0] exponent_a;
  logic signed [  EXP_BITS_B:0] exponent_b;
  logic signed [EXP_BITS_MAX:0] exponent_difference;
  logic signed [EXP_BITS_MAX:0] tentative_exponent;

  assign exponent_a = signed'({1'b0, operand_a.exponent});
  assign exponent_b = signed'({1'b0, operand_b.exponent});
  // exponent a - b；
  assign exponent_difference = (info_a.is_zero || info_b.is_zero) ? 0 : (exponent_a -BIAS_A + BIAS_C + info_a.is_subnormal) - (exponent_b -BIAS_B + BIAS_C + info_b.is_subnormal);
  // Inf, NaN, and 0+0 cases are handled by special case, but single zero must be handled here
  assign tentative_exponent = (exponent_difference < 0 || info_a.is_zero) ? (exponent_b - BIAS_B + BIAS_C + info_b.is_subnormal) : (exponent_a - BIAS_A + BIAS_C + info_a.is_subnormal);

  logic [SHIFT_AMOUNT_WIDTH-1:0] shamt_a, shamt_b;

  always_comb begin
    if (exponent_difference <= signed'(-DP_WIDTH)) begin  //b >> a, a fully shifted out
      shamt_a = DP_WIDTH;
      shamt_b = 0;
    end else if (exponent_difference < 0) begin  //b > a, shift a to align b
      shamt_a = unsigned'(-exponent_difference);
      shamt_b = 0;
    end else if (exponent_difference < signed'(DP_WIDTH)) begin  //b<a, , shift b to align a
      shamt_a = 0;
      shamt_b = unsigned'(exponent_difference);
    end else begin  //b<<a, b fully shifted out
      shamt_a = 0;
      shamt_b = DP_WIDTH;
    end
  end

  // ------------------
  // mantissa data path
  // ------------------
  logic [PRECISION_BITS_A-1:0] mantissa_a;
  logic [PRECISION_BITS_B-1:0] mantissa_b;

  assign mantissa_a = {info_a.is_normal, operand_a.mantissa};
  assign mantissa_b = {info_b.is_normal, operand_b.mantissa};

  // -----------------
  // Add data path
  // -----------------
  logic [DP_WIDTH-1:0] addend_a, addend_b;
  logic [DP_WIDTH-1:0] addend_big, addend_small;
  logic [DP_WIDTH-1:0] shifted_small;
  logic [DP_WIDTH-1:0] sum_raw;
  logic [DP_WIDTH-1:0] sum;
  logic sticky_shift;
  logic swap_operands;
  logic inject_carry_in;
  logic result_negative;
  logic operand_a_larger;
  logic final_sign;

  // Static format alignment: implicit bit at DP_WIDTH-2 for every format, no bits dropped
  localparam int unsigned LEFT_SHIFT_A = DP_WIDTH - 1 - PRECISION_BITS_A;
  localparam int unsigned LEFT_SHIFT_B = DP_WIDTH - 1 - PRECISION_BITS_B;

  logic [DP_WIDTH-1:0] mant_dp_a, mant_dp_b;
  assign mant_dp_a = mantissa_a << LEFT_SHIFT_A;
  assign mant_dp_b = mantissa_b << LEFT_SHIFT_B;

  // Dynamic alignment: bits shifted below the datapath are collected as sticky instead of dropped
  logic [2*DP_WIDTH-1:0] shift_ext_a, shift_ext_b;
  logic sticky_a_dyn, sticky_b_dyn;
  assign shift_ext_a = {mant_dp_a, {DP_WIDTH{1'b0}}} >> shamt_a;
  assign shift_ext_b = {mant_dp_b, {DP_WIDTH{1'b0}}} >> shamt_b;
  assign addend_a = shift_ext_a[2*DP_WIDTH-1:DP_WIDTH];
  assign addend_b = shift_ext_b[2*DP_WIDTH-1:DP_WIDTH];
  assign sticky_a_dyn = |shift_ext_a[DP_WIDTH-1:0];
  assign sticky_b_dyn = |shift_ext_b[DP_WIDTH-1:0];
  assign sticky_shift = sticky_a_dyn | sticky_b_dyn;

  // Exact magnitude comparison on the aligned addends. Comparing exponent fields is insufficient for
  // mixed input formats
  assign operand_a_larger = (addend_a > addend_b) || ((addend_a == addend_b) && sticky_a_dyn);

  // The larger operand is the minuend, so the shifted-out sticky bits (which always belong to a
  // strictly smaller operand) stay representable as a positive fraction below the datapath
  assign swap_operands = !operand_a_larger;
  assign addend_big = swap_operands ? addend_b : addend_a;
  assign addend_small = swap_operands ? addend_a : addend_b;

  assign shifted_small = (effective_subtraction) ? ~addend_small : addend_small;
  // In an effective subtraction, sticky bits make the subtrahend slightly larger than its aligned
  // value: omit the +1 of the two's complement so the datapath holds (result - 1) with sticky set
  assign inject_carry_in = effective_subtraction & ~sticky_shift;

  assign sum_raw = addend_big + shifted_small + inject_carry_in;
  assign result_negative = (effective_subtraction && sum_raw[DP_WIDTH-1]);
  assign sum = result_negative ? (~(sum_raw - 1)) : sum_raw;

  assign final_sign = (effective_subtraction) ? (operand_a_larger ? operand_a.sign : operand_b.sign) : tentative_sign;

  // --------------
  // Normalization
  // --------------
  localparam int unsigned LZC_WIDTH = $clog2(DP_WIDTH);
  logic [LZC_WIDTH-1:0] leading_zero_count;
  logic lzc_zeroes;

  lzc_snax #(
      .WIDTH(DP_WIDTH),
      .MODE (1)
  ) u_lzc (
      .in_i(sum),
      .cnt_o(leading_zero_count),
      .empty_o(lzc_zeroes)
  );

  logic signed [LZC_WIDTH:0] leading_zero_count_sgn;
  logic signed [EXP_BITS_MAX+1:0] final_exponent;
  logic signed [EXP_BITS_MAX+1:0] norm_shift_sgn;  // >= 0: left shift; < 0: right shift (underflow)

  assign leading_zero_count_sgn = signed'({1'b0, leading_zero_count});

  always_comb begin
    if (tentative_exponent - leading_zero_count_sgn + 1 > 0 && !lzc_zeroes) begin
      final_exponent = tentative_exponent - leading_zero_count_sgn + 1;
      norm_shift_sgn = leading_zero_count_sgn;
    end else begin
      // Denormalize into a subnormal result; very negative exponents shift fully into the sticky
      final_exponent = 0;
      norm_shift_sgn = (tentative_exponent < -signed'(DP_WIDTH)) ? -signed'(DP_WIDTH) : tentative_exponent;
    end
  end

  // Normalization shift over an extended vector so that right shifts (subnormal / underflowing
  // results) collect their shifted-out bits into the sticky instead of dropping them
  localparam int unsigned NORM_SHIFT_W = $clog2(2 * DP_WIDTH) + 1;
  logic [NORM_SHIFT_W-1:0] norm_shamt_ext;
  logic [2*DP_WIDTH-1:0] norm_ext;
  logic [PRECISION_BITS_C:0] final_mantissa;
  logic sticky_after_norm;

  assign norm_shamt_ext = NORM_SHIFT_W'(unsigned'(norm_shift_sgn + signed'(DP_WIDTH)));
  assign norm_ext = {{DP_WIDTH{1'b0}}, sum} << norm_shamt_ext;
  assign final_mantissa = norm_ext[2*DP_WIDTH-1 : 2*DP_WIDTH-PRECISION_BITS_C-1];
  assign sticky_after_norm = |norm_ext[2*DP_WIDTH-PRECISION_BITS_C-2 : 0];

  // ----------------------------
  // Rounding and classification
  // ----------------------------
  logic pre_round_sign;
  logic [EXP_BITS_C-1:0] pre_round_exponent;
  logic [MAN_BITS_C-1:0] pre_round_mantissa;
  logic [EXP_BITS_C+MAN_BITS_C-1:0] pre_round_abs;

  logic of_before_round;  // overflow
  logic uf_before_round;  // underflow

  assign of_before_round = (final_exponent >= (2 ** EXP_BITS_C - 1));
  assign uf_before_round = (final_exponent == 0);

  assign pre_round_sign = final_sign;
  assign pre_round_exponent = (of_before_round) ? 2 ** EXP_BITS_C - 2 : unsigned'(final_exponent[EXP_BITS_C-1:0]);
  assign pre_round_mantissa = (of_before_round) ? '1 : final_mantissa[MAN_BITS_C:1];
  assign pre_round_abs = {pre_round_exponent, pre_round_mantissa};

  logic rounded_sign;
  logic [EXP_BITS_C+MAN_BITS_C-1:0] rounded_abs;
  logic result_zero;
  logic [1:0] round_sticky_bits;

  assign round_sticky_bits = (of_before_round) ? 2'b11 : {final_mantissa[0], sticky_after_norm | sticky_shift};

  // Perform the rounding
  fpnew_rounding_snax #(
      .AbsWidth(EXP_BITS_C + MAN_BITS_C)
  ) u_fpnew_rounding_snax (
      .abs_value_i(pre_round_abs),
      .sign_i(pre_round_sign),
      .round_sticky_bits_i(round_sticky_bits),
      .rnd_mode_i(fpnew_pkg_snax::RNE),
      // Makes an exact cancellation yield +0 under RNE, per IEEE 754
      .effective_subtraction_i(effective_subtraction),
      .abs_rounded_o(rounded_abs),
      .sign_o(rounded_sign),
      .exact_zero_o(result_zero)
  );

  // -----------------
  // Result selection
  // -----------------
  logic [WIDTH_out-1:0] regular_result;

  assign regular_result = {rounded_sign, rounded_abs};
  assign result_o = result_is_special ? special_result : regular_result;
endmodule
