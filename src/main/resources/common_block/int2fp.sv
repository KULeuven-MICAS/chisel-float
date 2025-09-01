// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Man Shi <man.shi@kuleuven.be>
//         Xiaoling Yi <xiaoling.yi@kuleuven.be>
//         Robin Geens <robin.geens@kuleuven.be>

module intN_to_fp16 #(
    parameter INT_WIDTH = 4  // Set to 1, 2, 3, or 4
) (
    input  wire signed [INT_WIDTH-1:0] intN_in,
    output logic       [         15:0] fp16_out
);

  logic sign;
  logic [INT_WIDTH-1:0] abs_val;
  logic [4:0] exponent;
  logic [9:0] mantissa;

  logic unsigned [$clog2(INT_WIDTH):0] leading_zero_count;

  // Compute absolute value of int
  always_comb begin
    if (INT_WIDTH == 1) begin
      sign = ~intN_in[0];
      abs_val = 0;
    end else begin
      sign = intN_in[INT_WIDTH-1];
      abs_val = sign ? -intN_in : intN_in;
    end
  end

  if (INT_WIDTH > 1) begin
    lzc_snax #(
        .WIDTH(INT_WIDTH),
        .MODE (1)           // MODE = 1 counts leading zeroes
    ) i_lzc (
        .in_i   (abs_val),
        .cnt_o  (leading_zero_count),
        .empty_o()
    );
  end else begin
    assign leading_zero_count = 0;
  end



  always_comb begin
    // Special case: INT_WIDTH = 1
    if (INT_WIDTH == 1) begin
      // input: 1 => +1 → fp16 of +1.0 (0 01111 0000000000)
      // input: 0 => -1 → fp16 of -1.0 (1 01111 0000000000)
      exponent = 5'd15;  // exponent = 0 + bias (15)
      mantissa = 10'd0;
    end else begin
      // General case: signed int to fp16
      // If input is zero
      if (abs_val == 0) begin
        exponent = 5'd0;
        mantissa = 10'd0;
      end else begin
        // Exponent: bias + shift to align leading 1
        exponent = 5'd15 + (INT_WIDTH - 1) - leading_zero_count;
        // Align mantissa bits (remove leading 1). Drop overflow
        mantissa = abs_val << (10 - (INT_WIDTH - 1) + leading_zero_count);
      end
    end
    fp16_out = {sign, exponent, mantissa};
  end

endmodule
