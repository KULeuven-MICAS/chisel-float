// Copyright 2019 ETH Zurich and University of Bologna.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51
// Author: Stefan Mach <smach@iis.ee.ethz.ch>

// Copyright 2025 KU Leuven
// Modified by: Robin Geens <robin.geens@kuleuven.be>
// Converts non-IEEE 754 compliant floating point to an IEEE 754 compliant floating point.


// For now, this is implemented as a multiplication with constant 1 (exp=BIAS_IN, mantissa=1).
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

  localparam int unsigned MUL_WIDTH = 2 * PRECISION_BITS_A;
  localparam int unsigned LZC_RESULT_WIDTH = $clog2(MUL_WIDTH);
  localparam int unsigned EXP_WIDTH = unsigned'(fpnew_pkg_snax::maximum(
      fpnew_pkg_snax::maximum(EXP_BITS_C, EXP_BITS_A) + 2, LZC_RESULT_WIDTH
  ));

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
  fp_a_t operand_a;
  assign operand_a = operand_a_i;


  // Input classification
  // ---------------------
  logic is_zero_a;
  assign is_zero_a = (operand_a.exponent == '0) && (operand_a.mantissa == '0);
  logic effective_subtraction;
  logic tentative_sign;
  logic result_sign;
  assign result_sign = operand_a.sign;



  // ---------------------------
  // Initial exponent data path
  // ---------------------------
  logic signed [ EXP_BITS_A:0] exponent_a;
  logic signed [EXP_WIDTH-1:0] exponent_product;


  assign exponent_a = signed'({1'b0, operand_a.exponent});

  assign exponent_product = (is_zero_a) ? 2 - signed'(BIAS_C) : signed'(exponent_a - signed'(BIAS_A) + signed'(BIAS_C));

  // ------------------
  // Product data path
  // ------------------
  logic [PRECISION_BITS_A-1:0] mantissa_a;
  logic [MUL_WIDTH-1:0] product;

  // Add implicit bits to mantissa
  assign mantissa_a = {1'b1, operand_a.mantissa};

  // Mantissa multiplier (a*b)
  assign product = mantissa_a << MAN_BITS_A;

  // --------------
  // Normalization
  // --------------
  localparam int unsigned PRODUCT_SHIFTED_WIDTH = MUL_WIDTH + 1;  // Must have 1 bit more than product
  localparam int STICKY_BIT_WIDTH = PRODUCT_SHIFTED_WIDTH - (PRECISION_BITS_C + 1);
  localparam int unsigned PADDING_WIDTH = (STICKY_BIT_WIDTH <= 0) ? -STICKY_BIT_WIDTH : 0;

  logic                                    leading_zero_count;
  logic signed [                      1:0] leading_zero_count_sgn;  // signed leading-zero count

  logic signed [            EXP_WIDTH-1:0] normalized_exponent;
  logic signed [            EXP_WIDTH-1:0] final_exponent;
  logic        [PRODUCT_SHIFTED_WIDTH-1:0] product_shifted;
  logic        [       PRECISION_BITS_C:0] final_mantissa;
  /* verilator lint_off ASCRANGE */
  // When STICKY_BIT_WIDTH < 0, the range will be inverted, triggering an error, but the vector will not used
  logic        [     STICKY_BIT_WIDTH-1:0] sum_sticky_bits;
  /* verilator lint_on ASCRANGE */
  logic                                    sticky_after_norm;



  // There is always one leading zero in a *1 mantissa product
  assign leading_zero_count = 1;
  assign leading_zero_count_sgn = signed'({2'b01});


  // TODO clean up
  always_comb begin : norm_shift_amount
    if (exponent_product - leading_zero_count_sgn + 1 > 0) begin
      normalized_exponent = exponent_product - leading_zero_count_sgn + 1;  // Account for LZC shift
      // Account for 1 bit wider result. Cancel out leading zeros. Mantissa's hidden bit will now be at MSB
      product_shifted = product << (leading_zero_count + 1);
    end else begin
      // Subnormal result. Exponent is 0
      normalized_exponent = 0;
      // Align mantissa with minimum exponent. Mantissa MSB must not be discarded later: subnormals have no hidden bit
      product_shifted = product << 1 >> unsigned'(-exponent_product);
    end
  end

  always_comb begin : small_norm
    // If src is wider than dst: save LSBs in sticky bits
    if (STICKY_BIT_WIDTH > 0) {final_mantissa, sum_sticky_bits} = product_shifted;
    // If src is narrower than dst: append 0 at LSBs
    else begin
      final_mantissa  = {product_shifted, {(PADDING_WIDTH) {1'b0}}};
      sum_sticky_bits = 1'b0;
    end

    final_exponent = normalized_exponent;
  end

  // Update the sticky bit with the shifted-out bits
  assign sticky_after_norm = (|sum_sticky_bits);

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
      .effective_subtraction_i(1'b0),                 // pure mul, no subtraction
      .abs_rounded_o          (rounded_abs),
      .sign_o                 (rounded_sign),
      .exact_zero_o           (result_zero)
  );

  // -----------------
  // Result selection
  // -----------------
  assign result_o = {rounded_sign, rounded_abs};



endmodule
