// Copyright 2019 ETH Zurich and University of Bologna.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51
// Author: Stefan Mach <smach@iis.ee.ethz.ch>

// Copyright 2025 KU Leuven
// Modified by: Robin Geens <robin.geens@kuleuven.be>
// Changes: allow for different a, b, c, and out data types.


module fp_fma #(
    parameter fpnew_pkg_snax::fp_format_e FpFormat_a = fpnew_pkg_snax::fp_format_e'(2),
    parameter fpnew_pkg_snax::fp_format_e FpFormat_b = fpnew_pkg_snax::fp_format_e'(2),
    // Operand c and output
    parameter fpnew_pkg_snax::fp_format_e FpFormat_c = fpnew_pkg_snax::fp_format_e'(0),
    // 0:FP32; 1:FP64; 2:FP16
    // don't change
    parameter int unsigned WIDTH_A = fpnew_pkg_snax::fp_width(FpFormat_a),
    parameter int unsigned WIDTH_B = fpnew_pkg_snax::fp_width(FpFormat_b),
    parameter int unsigned WIDTH_C = fpnew_pkg_snax::fp_width(FpFormat_c)
) (
    input  logic [WIDTH_A-1:0] operand_a_i,
    input  logic [WIDTH_B-1:0] operand_b_i,
    input  logic [WIDTH_C-1:0] operand_c_i,
    output logic [WIDTH_C-1:0] result_o
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
  localparam int unsigned EXP_BITS_C = fpnew_pkg_snax::exp_bits(FpFormat_c);
  localparam int unsigned MAN_BITS_C = fpnew_pkg_snax::man_bits(FpFormat_c);
  localparam int unsigned BIAS_C = fpnew_pkg_snax::bias(FpFormat_c);


  localparam int unsigned PRECISION_BITS_A = MAN_BITS_A + 1;
  localparam int unsigned PRECISION_BITS_B = MAN_BITS_B + 1;
  localparam int unsigned PRECISION_BITS_C = MAN_BITS_C + 1;

  localparam int unsigned MUL_WIDTH = fpnew_pkg_snax::maximum(PRECISION_BITS_A + PRECISION_BITS_B, PRECISION_BITS_C);
  localparam int unsigned PRODUCT_SHIFT = MUL_WIDTH - (PRECISION_BITS_A + PRECISION_BITS_B);
  localparam int unsigned LOWER_SUM_WIDTH = MUL_WIDTH + 3;
  localparam int unsigned LZC_RESULT_WIDTH = $clog2(LOWER_SUM_WIDTH);

  localparam int unsigned EXP_WIDTH = unsigned'(fpnew_pkg_snax::maximum(
      fpnew_pkg_snax::maximum(EXP_BITS_C, fpnew_pkg_snax::maximum(EXP_BITS_A, EXP_BITS_B)) + 2, LZC_RESULT_WIDTH
  ));
  localparam int unsigned INTERMEDIATE_WIDTH = LOWER_SUM_WIDTH + PRECISION_BITS_C;
  localparam int unsigned SHIFT_AMOUNT_WIDTH = $clog2(INTERMEDIATE_WIDTH + 2);

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
  } fp_c_t;


  // -----------------
  // Input processing
  // -----------------
  fpnew_pkg_snax::fp_info_t [2:0] info_q;

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
  fpnew_classifier_snax #(
      .FpFormat   (FpFormat_c),
      .NumOperands(1)
  ) i_class_inputs_c (
      .operands_i(operand_c_i),
      .is_boxed_i(1'b1),
      .info_o    (info_q[2])
  );

  fp_a_t operand_a;
  fp_b_t operand_b;
  fp_c_t operand_c;
  fpnew_pkg_snax::fp_info_t info_a, info_b, info_c;

  assign operand_a = operand_a_i;
  assign operand_b = operand_b_i;
  assign operand_c = operand_c_i;
  assign info_a = info_q[0];
  assign info_b = info_q[1];
  assign info_c = info_q[2];

  // ---------------------
  // Input classification
  // ---------------------
  logic any_operand_inf;
  logic any_operand_nan;
  logic signalling_nan;
  logic effective_subtraction;
  logic tentative_sign;

  // Reduction for special case handling
  assign any_operand_inf = (|{info_a.is_inf, info_b.is_inf, info_c.is_inf});
  assign any_operand_nan = (|{info_a.is_nan, info_b.is_nan, info_c.is_nan});
  assign signalling_nan = (|{info_a.is_signalling, info_b.is_signalling, info_c.is_signalling});
  // Effective subtraction in FMA occurs when product and addend signs differ
  assign effective_subtraction = operand_a.sign ^ operand_b.sign ^ operand_c.sign;
  // The tentative sign of the FMA shall be the sign of the product
  assign tentative_sign = operand_a.sign ^ operand_b.sign;

  // ----------------------
  // Special case handling
  // ----------------------
  fp_c_t special_result;
  logic  result_is_special;

  always_comb begin : special_cases
    // Default assignments
    special_result = '{sign: 1'b0, exponent: '1, mantissa: 2 ** (MAN_BITS_C - 1)};  // canonical qNaN
    result_is_special = 1'b0;

    if ((info_a.is_inf && info_b.is_zero) || (info_a.is_zero && info_b.is_inf)) begin
      result_is_special = 1'b1;
      special_result = '{
          sign: operand_a.sign ^ operand_b.sign,
          exponent: '1,
          mantissa: 2 ** (MAN_BITS_C - 1)  // canonical qNaN
      };
    end else if (any_operand_nan) begin
      result_is_special = 1'b1;
    end else if (any_operand_inf) begin
      result_is_special = 1'b1;
      if ((info_a.is_inf || info_b.is_inf) && info_c.is_inf && effective_subtraction)
        special_result = '{sign: 1'b0, exponent: '1, mantissa: 2 ** (MAN_BITS_C - 1)};
      else if (info_a.is_inf || info_b.is_inf) begin
        special_result = '{sign: operand_a.sign ^ operand_b.sign, exponent: '1, mantissa: '0};
      end else if (info_c.is_inf) begin
        special_result = '{sign: operand_c.sign, exponent: '1, mantissa: '0};
      end
    end
  end

  // ---------------------------
  // Initial exponent data path
  // ---------------------------
  logic signed [EXP_BITS_A:0] exponent_a;
  logic signed [EXP_BITS_B:0] exponent_b;
  logic signed [EXP_BITS_C:0] exponent_c;

  logic signed [EXP_WIDTH-1:0] exponent_addend, exponent_product, exponent_difference;
  logic signed [EXP_WIDTH-1:0] tentative_exponent;

  assign exponent_a = signed'({1'b0, operand_a.exponent});
  assign exponent_b = signed'({1'b0, operand_b.exponent});
  assign exponent_c = signed'({1'b0, operand_c.exponent});

  assign exponent_addend = signed'(exponent_c + $signed({1'b0, ~info_c.is_normal}));  // 0 as subnorm
  assign exponent_product = (info_a.is_zero || info_b.is_zero)
                                ? 2 - signed'(BIAS_A) - signed'(BIAS_B) + signed'(BIAS_C)
                                : signed'(exponent_a + info_a.is_subnormal
                                        + exponent_b + info_b.is_subnormal
                                        - signed'(BIAS_A) - signed'(BIAS_B) + signed'(BIAS_C));
  assign exponent_difference = exponent_addend - exponent_product;
  assign tentative_exponent = (exponent_difference > 0) ? exponent_addend : exponent_product;

  logic [SHIFT_AMOUNT_WIDTH-1:0] addend_shamt;

  always_comb begin : addend_shift_amount
    // Product-anchored case, saturated shift (addend is only in the sticky bit)
    if (exponent_difference <= signed'(-MUL_WIDTH - 1))  // 
      addend_shamt = INTERMEDIATE_WIDTH + 1;
    // Addend and product will have mutual bits to add
    else if (exponent_difference <= signed'(PRECISION_BITS_C + 2))
      addend_shamt = unsigned'(signed'(PRECISION_BITS_C) + 3 - exponent_difference);
    // Addend-anchored case, saturated shift (product is only in the sticky bit)
    else begin  //
      addend_shamt = 0;
    end
  end

  // ------------------
  // Product data path
  // ------------------
  logic [PRECISION_BITS_A-1:0] mantissa_a;
  logic [PRECISION_BITS_B-1:0] mantissa_b;
  logic [PRECISION_BITS_C-1:0] mantissa_c;
  logic [       MUL_WIDTH-1:0] product;
  logic [INTERMEDIATE_WIDTH:0] product_shifted;

  // Add implicit bits to mantissa
  assign mantissa_a = {info_a.is_normal, operand_a.mantissa};
  assign mantissa_b = {info_b.is_normal, operand_b.mantissa};
  assign mantissa_c = {info_c.is_normal, operand_c.mantissa};

  // Mantissa multiplier (a*b)
  assign product = mantissa_a * mantissa_b;

  // Product is placed into a 3p+4 bit wide vector, padded with 2 bits for round and sticky:
  // | 000...000 | product | RS |
  //  <-  p+2  -> <-  2p -> < 2>
  assign product_shifted = product << PRODUCT_SHIFT + 2;

  // -----------------
  // Addend data path
  // -----------------
  logic [INTERMEDIATE_WIDTH:0] addend_after_shift;
  logic [PRECISION_BITS_C-1:0] addend_sticky_bits;
  logic                        sticky_before_add;
  logic [INTERMEDIATE_WIDTH:0] addend_shifted;
  logic                        inject_carry_in;

  assign {addend_after_shift, addend_sticky_bits} = (mantissa_c << (INTERMEDIATE_WIDTH + 1)) >> addend_shamt;
  assign sticky_before_add = (|addend_sticky_bits);

  // In case of a subtraction, the addend is inverted
  assign addend_shifted = (effective_subtraction) ? ~addend_after_shift : addend_after_shift;
  assign inject_carry_in = effective_subtraction & ~sticky_before_add;

  // ------
  // Adder
  // ------
  logic [INTERMEDIATE_WIDTH+1:0] sum_raw;
  logic                          sum_carry;
  logic [  INTERMEDIATE_WIDTH:0] sum;
  logic                          final_sign;

  //Mantissa adder (ab+c). In normal addition, it cannot overflow.
  assign sum_raw = product_shifted + addend_shifted + inject_carry_in;
  assign sum_carry = sum_raw[INTERMEDIATE_WIDTH+1];

  // Complement negative sum (can only happen in subtraction -> overflows for positive results)
  assign sum = (effective_subtraction && ~sum_carry) ? -sum_raw : sum_raw;

  // In case of a mispredicted subtraction result, do a sign flip
  // TODO this is different from reference
  assign final_sign = (effective_subtraction && (sum_carry == tentative_sign))
                        ? 1'b1
                        : (effective_subtraction ? 1'b0 : tentative_sign);


  // --------------
  // Normalization
  // --------------
  logic        [   LOWER_SUM_WIDTH-1:0] sum_lower;
  logic        [  LZC_RESULT_WIDTH-1:0] leading_zero_count;
  logic signed [    LZC_RESULT_WIDTH:0] leading_zero_count_sgn;
  logic                                 lzc_zeroes;

  logic        [SHIFT_AMOUNT_WIDTH-1:0] norm_shamt;
  logic signed [         EXP_WIDTH-1:0] normalized_exponent;

  logic        [INTERMEDIATE_WIDTH+1:0] sum_shifted;
  logic        [    PRECISION_BITS_C:0] final_mantissa;
  logic        [   LOWER_SUM_WIDTH-1:0] sum_sticky_bits;
  logic                                 sticky_after_norm;

  // TODO EXP_WIDTH_C? 
  logic signed [         EXP_WIDTH-1:0] final_exponent;

  assign sum_lower = sum[LOWER_SUM_WIDTH-1:0];

  // Leading zero counter for cancellations
  lzc_snax #(
      .WIDTH(LOWER_SUM_WIDTH),
      .MODE (1)
  ) i_lzc (
      .in_i   (sum_lower),
      .cnt_o  (leading_zero_count),
      .empty_o(lzc_zeroes)
  );

  assign leading_zero_count_sgn = signed'({1'b0, leading_zero_count});

  always_comb begin : norm_shift_amount
    if ((exponent_difference <= 0) || (effective_subtraction && (exponent_difference <= 2))) begin
      if ((exponent_product - leading_zero_count_sgn + 1 >= 0) && !lzc_zeroes) begin
        norm_shamt          = PRECISION_BITS_C + 2 + leading_zero_count;
        normalized_exponent = exponent_product - leading_zero_count_sgn + 1;  // account for shift
        // Subnormal result
      end else begin
        norm_shamt          = unsigned'(signed'(PRECISION_BITS_C) + 2 + exponent_product);
        normalized_exponent = 0;  // subnormals encoded as 0
      end
      // Addend-anchored case
    end else begin
      norm_shamt          = addend_shamt;  // Undo the initial shift
      normalized_exponent = tentative_exponent;
    end
  end

  // Do the large normalization shift
  assign sum_shifted = sum << norm_shamt;

  always_comb begin : small_norm
    {final_mantissa, sum_sticky_bits} = sum_shifted;
    final_exponent                    = normalized_exponent;

    // The normalized sum has overflown, align right and fix exponent
    if (sum_shifted[INTERMEDIATE_WIDTH+1]) begin  // check the carry bit
      {final_mantissa, sum_sticky_bits} = sum_shifted >> 1;
      final_exponent                    = normalized_exponent + 1;
      // The normalized sum is normal, nothing to do
    end else if (sum_shifted[INTERMEDIATE_WIDTH]) begin  // check the sum MSB
      // do nothing
      // The normalized sum is still denormal, align left - unless the result is not already subnormal
    end else if (normalized_exponent > 1) begin
      {final_mantissa, sum_sticky_bits} = sum_shifted << 1;
      final_exponent                    = normalized_exponent - 1;
      // Otherwise we're denormal
    end else begin
      final_exponent = '0;
    end
  end

  assign sticky_after_norm = (|{sum_sticky_bits}) | sticky_before_add;

  // ----------------------------
  // Rounding and classification
  // ----------------------------
  logic                             pre_round_sign;
  logic [           EXP_BITS_C-1:0] pre_round_exponent;
  logic [           MAN_BITS_C-1:0] pre_round_mantissa;
  logic [EXP_BITS_C+MAN_BITS_C-1:0] pre_round_abs;
  logic [                      1:0] round_sticky_bits;

  logic of_before_round, of_after_round;  // overflow
  logic uf_before_round, uf_after_round;  // underflow
  logic                             result_zero;

  logic                             rounded_sign;
  logic [EXP_BITS_C+MAN_BITS_C-1:0] rounded_abs;

  assign of_before_round = final_exponent >= 2 ** (EXP_BITS_C) - 1;
  assign uf_before_round = final_exponent == 0;

  assign pre_round_sign = final_sign;
  assign pre_round_exponent = (of_before_round) ? 2 ** EXP_BITS_C - 2 : unsigned'(final_exponent[EXP_BITS_C-1:0]);
  assign pre_round_mantissa = (of_before_round) ? '1 : final_mantissa[MAN_BITS_C:1];  // bit 0 is R bit
  assign pre_round_abs = {pre_round_exponent, pre_round_mantissa};

  assign round_sticky_bits = (of_before_round) ? 2'b11 : {final_mantissa[0], sticky_after_norm};

  // Perform the rounding
  fpnew_rounding_snax #(
      .AbsWidth(EXP_BITS_C + MAN_BITS_C)
  ) i_fpnew_rounding_snax (
      .abs_value_i            (pre_round_abs),
      .sign_i                 (pre_round_sign),
      .round_sticky_bits_i    (round_sticky_bits),
      .rnd_mode_i             (fpnew_pkg_snax::RNE),
      .effective_subtraction_i(effective_subtraction),
      .abs_rounded_o          (rounded_abs),
      .sign_o                 (rounded_sign),
      .exact_zero_o           (result_zero)
  );

  // -----------------
  // Result selection
  // -----------------
  logic [WIDTH_C-1:0] regular_result;

  assign regular_result    = {rounded_sign, rounded_abs};
  assign result_o = result_is_special ? special_result : regular_result;
endmodule
