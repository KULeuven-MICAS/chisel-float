// Copyright 2019 ETH Zurich and University of Bologna.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51
// Author: Stefan Mach <smach@iis.ee.ethz.ch>

// Copyright 2025 KU Leuven
// Modified by: Man Shi <man.shi@kuleuven.be>
// Changes: allow for different FP*INT data types; remove adder.

// FP16 x signed integer of 1 to 11 bits (WIDTH_B) -> FP32. The product always fits in FP32, so it is exact.
// A 1-bit integer is bipolar: 1 encodes +1 and 0 encodes -1.
module fp_mul_int #(
    parameter fpnew_pkg_snax::fp_format_e  FpFormat_a   = fpnew_pkg_snax::FP16,
    parameter fpnew_pkg_snax::int_format_e IntFormat_b  = fpnew_pkg_snax::INT4,
    parameter fpnew_pkg_snax::fp_format_e  FpFormat_b   = fpnew_pkg_snax::FP16,  // the integer, converted to FP16
    parameter fpnew_pkg_snax::fp_format_e  FpFormat_out = fpnew_pkg_snax::FP32,
    parameter fpnew_pkg_snax::roundmode_e  RndMode      = fpnew_pkg_snax::RNE,   // unused: the product is exact

    parameter int unsigned WIDTH_a = fpnew_pkg_snax::fp_width(FpFormat_a),  // do not change
    parameter int unsigned WIDTH_b = fpnew_pkg_snax::fp_width(FpFormat_b),  // do not change
    // Integer width: the width of IntFormat_b by default, or set directly to any width from 1 to 11
    parameter int unsigned WIDTH_B = fpnew_pkg_snax::int_width(IntFormat_b),
    parameter int unsigned WIDTH_out = fpnew_pkg_snax::fp_width(FpFormat_out),  // do not change
    parameter int unsigned rnd_mode = int'(RndMode)  // unused
) (
    input  logic [  WIDTH_a-1:0] operand_a_i,
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
  // for the result
  localparam int unsigned EXP_BITS_C = fpnew_pkg_snax::exp_bits(FpFormat_out);
  localparam int unsigned MAN_BITS_C = fpnew_pkg_snax::man_bits(FpFormat_out);
  localparam int unsigned BIAS_C = fpnew_pkg_snax::bias(FpFormat_out);

  localparam int unsigned PRECISION_BITS_A = MAN_BITS_A + 1;
  localparam int unsigned PRECISION_BITS_B = MAN_BITS_B + 1;
  localparam int unsigned MUL_WIDTH = PRECISION_BITS_A + PRECISION_BITS_B;
  localparam int unsigned LZC_WIDTH = $clog2(MUL_WIDTH);
  localparam int unsigned EXP_WIDTH = EXP_BITS_C + 2;

  // pragma translate_off
  initial begin
    if (FpFormat_a != fpnew_pkg_snax::FP16 || FpFormat_b != fpnew_pkg_snax::FP16 ||
        FpFormat_out != fpnew_pkg_snax::FP32)
      $fatal(1, "fp_mul_int supports FP16 x integer -> FP32 only");
    if (WIDTH_B < 1 || WIDTH_B > 11) $fatal(1, "fp_mul_int: WIDTH_B must be 1 to 11, got %0d", WIDTH_B);
  end
  // pragma translate_on

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
  logic [WIDTH_b-1:0] operand_b_i_fp;  // the integer as an FP16 value (exact)

  intN_to_fp16 #(
      .INT_WIDTH(WIDTH_B)
  ) Int2fp (
      .intN_in (operand_b_i),
      .fp16_out(operand_b_i_fp)
  );

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
      .operands_i(operand_b_i_fp),
      .is_boxed_i(1'b1),
      .info_o    (info_q[1])
  );

  // Default assignments - packing-order-agnostic
  assign operand_a = operand_a_i;
  assign operand_b = operand_b_i_fp;
  assign info_a    = info_q[0];
  assign info_b    = info_q[1];

  logic result_sign;
  assign result_sign = operand_a.sign ^ operand_b.sign;

  // ----------------------
  // Special case handling
  // ----------------------
  fp_t_out special_result;
  logic    result_is_special;

  always_comb begin : special_cases
    // Default assignments
    special_result = '{sign: 1'b0, exponent: '1, mantissa: 2 ** (MAN_BITS_C - 1)};  // canonical qNaN
    result_is_special = 1'b0;

    if ((info_a.is_inf && info_b.is_zero) || (info_a.is_zero && info_b.is_inf)) begin
      // inf x 0 is invalid: canonical qNaN
      result_is_special = 1'b1;
    end else if (info_a.is_nan || info_b.is_nan) begin
      // Any NaN becomes the canonical quiet NaN
      result_is_special = 1'b1;
    end else if (info_a.is_inf || info_b.is_inf) begin
      result_is_special = 1'b1;
      special_result = '{sign: result_sign, exponent: '1, mantissa: '0};
    end
  end

  // ---------------------------
  // Initial exponent data path
  // ---------------------------
  logic signed [ EXP_BITS_A:0] exponent_a;
  logic signed [ EXP_BITS_B:0] exponent_b;
  logic signed [EXP_WIDTH-1:0] exponent_product;  // biased in the output format, of the product bit of weight 2^0

  assign exponent_a = signed'({1'b0, operand_a.exponent});
  assign exponent_b = signed'({1'b0, operand_b.exponent});

  // Subnormals have the exponent of the smallest normal numbers
  assign exponent_product = EXP_WIDTH'(int'(exponent_a) + int'(info_a.is_subnormal) - int'(BIAS_A)
                                     + int'(exponent_b) + int'(info_b.is_subnormal) - int'(BIAS_B)
                                     + int'(BIAS_C));

  // ------------------
  // Product data path
  // ------------------
  logic        [PRECISION_BITS_A-1:0] mantissa_a;
  logic        [PRECISION_BITS_B-1:0] mantissa_b;
  logic        [       MUL_WIDTH-1:0] product;  // two integer bits: bit MUL_WIDTH-2 has weight 2^0
  logic        [       LZC_WIDTH-1:0] leading_zero_count;
  logic                               product_is_zero;
  logic        [       MUL_WIDTH-1:0] product_normalized;  // leading one at the MSB
  logic signed [       EXP_WIDTH-1:0] final_exponent;

  // Add implicit bits to mantissae
  assign mantissa_a = {info_a.is_normal, operand_a.mantissa};
  assign mantissa_b = {info_b.is_normal, operand_b.mantissa};

  // Mantissa multiplier (a*b)
  assign product = mantissa_a * mantissa_b;

  lzc_snax #(
      .WIDTH(MUL_WIDTH),
      .MODE (1)           // MODE = 1 counts leading zeroes
  ) i_lzc (
      .in_i   (product),
      .cnt_o  (leading_zero_count),
      .empty_o(product_is_zero)
  );

  // The result is exact and normal, so normalizing the product is all that is needed
  assign product_normalized = product << leading_zero_count;
  assign final_exponent = exponent_product + 1 - signed'(EXP_WIDTH'(leading_zero_count));

  // -----------------
  // Result selection
  // -----------------
  fp_t_out regular_result;

  always_comb begin : assemble_result
    // A zero product (an operand is zero) is a zero with the sign of the product
    regular_result = '{sign: result_sign, exponent: '0, mantissa: '0};
    if (!product_is_zero) begin
      regular_result.exponent = final_exponent[EXP_BITS_C-1:0];
      // Drop the leading one and pad the product to the output mantissa width
      regular_result.mantissa = {product_normalized[MUL_WIDTH-2:0], {(MAN_BITS_C - MUL_WIDTH + 1) {1'b0}}};
    end
  end

  assign result_o = result_is_special ? special_result : regular_result;

endmodule
