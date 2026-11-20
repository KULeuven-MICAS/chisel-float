// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51
//
// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import scala.collection.mutable.ArrayBuffer

import chisel3._

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import FpStimulus._

class FpDivSqrtTest extends AnyFlatSpec with ChiselScalatestTester with FpTestHarness {
  behavior of "FpDivSqrt"

  val maxLatencyCycles   = 100
  val maxReadyWaitCycles = 20

  /** Cycles from accepting the operands to `out.valid`, over a sweep. */
  private class Latency {
    var min = Int.MaxValue
    var max = 0
    def add(cycles: Int): Unit = { min = math.min(min, cycles); max = math.max(max, cycles) }
    override def toString: String = if (min == max) s"$min cycles" else s"$min to $max cycles"
  }

  private def waitFor(dut: FpDivSqrt, maxCycles: Int, what: String)(cond: => Boolean): Int = {
    var cycles = 0
    while (!cond) {
      assert(cycles < maxCycles, s"timeout waiting for $what")
      dut.clock.step()
      cycles += 1
    }
    cycles
  }

  /** Issues one operation and consumes its result. */
  private def run(dut: FpDivSqrt, latency: Latency, sqrt: Boolean, a: BigInt, b: BigInt): BigInt = {
    dut.io.mode.poke(sqrt.B)
    dut.io.out.ready.poke(true.B)
    dut.io.in_a.valid.poke(true.B)
    dut.io.in_a.bits.poke(a.U)
    dut.io.in_b.valid.poke((!sqrt).B)
    dut.io.in_b.bits.poke(b.U)
    waitFor(dut, maxReadyWaitCycles, "in_a.ready")(dut.io.in_a.ready.peekBoolean())
    dut.io.in_b.ready.expect((!sqrt).B, "in_b must transfer together with in_a for a division, never for a sqrt")
    dut.clock.step()
    dut.io.in_a.valid.poke(false.B)
    dut.io.in_b.valid.poke(false.B)
    latency.add(1 + waitFor(dut, maxLatencyCycles, "out.valid")(dut.io.out.valid.peekBoolean()))
    val result = dut.io.out.bits.peek().litValue
    dut.clock.step()
    result
  }

  private def divStimulus(t: FpType, n: Int): Iterator[Seq[BigInt]] = {
    val r        = rng(s"div-${name(t)}")
    val crit     = criticalExponents(t)
    val (lo, hi) = exponentRange(t)
    // quotient exponent ea - eb around a critical exponent
    val near     = Iterator.fill(n) {
      val e  = crit(r.nextInt(crit.length))
      val eb = lo + r.nextInt(hi - lo + 1)
      val ea = math.max(lo, math.min(hi, e + eb))
      Seq(nearExponent(r, t, ea), nearExponent(r, t, ea - e))
    }
    cross(boundary(t), boundary(t)) ++ Iterator.fill(n)(Seq(bits(r, t.width), bits(r, t.width))) ++ near
  }

  private def sqrtStimulus(t: FpType, n: Int): Iterator[Seq[BigInt]] = {
    val r = rng(s"sqrt-${name(t)}")
    boundary(t).iterator.map(Seq(_)) ++ Iterator.fill(n)(Seq(bits(r, t.width)))
  }

  private def checkDiv(dut: FpDivSqrt, label: String, vectors: Iterator[Seq[BigInt]]): Unit = {
    val t       = dut.fpType
    val latency = new Latency
    sweep(label, Seq(t, t), t, vectors)(v => run(dut, latency, sqrt = false, v(0), v(1)))(v =>
      FpReference.div(t, v(0), v(1))
    )
    println(s"$label: latency $latency")
  }

  private def checkSqrt(dut: FpDivSqrt, label: String, vectors: Iterator[Seq[BigInt]]): Unit = {
    val t       = dut.fpType
    val latency = new Latency
    sweep(label, Seq(t), t, vectors)(v => run(dut, latency, sqrt = true, v(0), 0))(v => FpReference.sqrt(t, v(0)))
    println(s"$label: latency $latency")
  }

  for (t <- Seq(BF16, FP16)) {
    it should s"compute ${name(t)} square roots bit-exactly (exhaustive)" in {
      test(new FpDivSqrt(t)).withAnnotations(FpTestConfig.annotations) { dut =>
        dut.clock.setTimeout(0)
        checkSqrt(dut, s"FpDivSqrt sqrt(${name(t)})", exhaustive(16))
      }
    }
  }

  for (t <- Seq(BF16, FP16)) {
    it should s"compute ${name(t)} divisions bit-exactly" in {
      test(new FpDivSqrt(t)).withAnnotations(FpTestConfig.annotations) { dut =>
        dut.clock.setTimeout(0)
        checkDiv(dut, s"FpDivSqrt ${name(t)} / ${name(t)}", divStimulus(t, FpTestConfig.scaled(20000)))
      }
    }
  }

  it should "compute FP32 divisions and square roots bit-exactly" in {
    test(new FpDivSqrt(FP32)).withAnnotations(FpTestConfig.annotations) { dut =>
      dut.clock.setTimeout(0)
      checkDiv(dut, "FpDivSqrt FP32 / FP32", divStimulus(FP32, FpTestConfig.scaled(20000)))
      checkSqrt(dut, "FpDivSqrt sqrt(FP32)", sqrtStimulus(FP32, FpTestConfig.scaled(20000)))
    }
  }

  it should "return every result, in order, under random input gaps and output stalls" in {
    test(new FpDivSqrt()).withAnnotations(FpTestConfig.annotations) { dut =>
      dut.clock.setTimeout(0)
      val r        = rng("divsqrt-stream")
      val ops      = Seq.fill(FpTestConfig.scaled(2000))((r.nextBoolean(), bits(r, 16), bits(r, 16)))
      val expected = ops.map { case (sqrt, a, b) =>
        if (sqrt) FpReference.sqrt(BF16, a) else FpReference.div(BF16, a, b)
      }
      val results  = ArrayBuffer.empty[BigInt]
      var issued   = 0
      var offering = false
      var cycles   = 0
      while (results.size < ops.size) {
        assert(
          cycles < ops.size * maxLatencyCycles,
          s"only ${results.size} of ${ops.size} results after $cycles cycles"
        )
        // Once offered, an operation stays valid until it is accepted
        offering = issued < ops.size && (offering || r.nextInt(3) != 0)
        if (offering) {
          val (sqrt, a, b) = ops(issued)
          dut.io.mode.poke(sqrt.B)
          dut.io.in_a.bits.poke(a.U)
          dut.io.in_b.bits.poke(b.U)
          dut.io.in_b.valid.poke((!sqrt).B)
        } else {
          dut.io.in_b.valid.poke(false.B)
        }
        dut.io.in_a.valid.poke(offering.B)
        val outReady = r.nextInt(3) != 0
        dut.io.out.ready.poke(outReady.B)
        val accepted = offering && dut.io.in_a.ready.peekBoolean()
        if (dut.io.out.valid.peekBoolean() && outReady) results += dut.io.out.bits.peek().litValue
        dut.clock.step()
        cycles += 1
        if (accepted) {
          issued += 1
          offering = false
        }
      }
      val bad      = results.indices.filter(i => results(i) != expected(i))
      assert(bad.isEmpty, s"${bad.size} wrong results, first at operation ${bad.headOption.getOrElse(-1)}")
      println(s"FpDivSqrt streaming: ${ops.size} operations in $cycles cycles, 0 wrong")
    }
  }

  it should "hold its result under back-pressure and accept no new operation meanwhile" in {
    test(new FpDivSqrt()).withAnnotations(FpTestConfig.annotations) { dut =>
      val one      = BigInt(0x3f80) // 1.0
      val three    = BigInt(0x4040) // 3.0
      dut.io.mode.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.in_a.valid.poke(true.B)
      dut.io.in_b.valid.poke(true.B)
      dut.io.in_a.bits.poke(one.U)
      dut.io.in_b.bits.poke(three.U)
      waitFor(dut, maxReadyWaitCycles, "in_a.ready")(dut.io.in_a.ready.peekBoolean())
      dut.clock.step()
      waitFor(dut, maxLatencyCycles, "out.valid")(dut.io.out.valid.peekBoolean())
      val expected = FpReference.div(BF16, one, three)
      for (_ <- 0 until 10) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.expect(expected.U)
        dut.io.in_a.ready.expect(false.B)
        dut.io.in_b.ready.expect(false.B)
        dut.clock.step()
      }
      dut.io.in_a.valid.poke(false.B)
      dut.io.in_b.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "accept a new operation in the cycle its previous result is consumed" in {
    test(new FpDivSqrt()).withAnnotations(FpTestConfig.annotations) { dut =>
      val four = BigInt(0x4080) // 4.0
      val nine = BigInt(0x4110) // 9.0
      dut.io.mode.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.in_a.valid.poke(true.B)
      dut.io.in_a.bits.poke(four.U)
      waitFor(dut, maxReadyWaitCycles, "in_a.ready")(dut.io.in_a.ready.peekBoolean())
      dut.clock.step()
      dut.io.in_a.bits.poke(nine.U)
      waitFor(dut, maxLatencyCycles, "out.valid")(dut.io.out.valid.peekBoolean())
      dut.io.out.bits.expect(0x4000.U) // sqrt(4) = 2
      dut.io.in_a.ready.expect(true.B) // the result is consumed in this cycle, so the next operation is accepted
      dut.clock.step()
      dut.io.in_a.valid.poke(false.B)
      dut.io.out.valid.expect(false.B)
      waitFor(dut, maxLatencyCycles, "out.valid")(dut.io.out.valid.peekBoolean())
      dut.io.out.bits.expect(0x4040.U) // sqrt(9) = 3
    }
  }

  it should "only transfer the operands of a division together" in {
    test(new FpDivSqrt()).withAnnotations(FpTestConfig.annotations) { dut =>
      dut.io.mode.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.in_a.valid.poke(true.B)
      dut.io.in_b.valid.poke(false.B)
      for (_ <- 0 until 5) {
        dut.io.in_a.ready.expect(false.B)
        dut.clock.step()
      }
      dut.io.in_a.valid.poke(false.B)
      dut.io.in_b.valid.poke(true.B)
      for (_ <- 0 until 5) {
        dut.io.in_b.ready.expect(false.B)
        dut.clock.step()
      }
    }
  }

  it should "reject formats the THMULTI unit does not support" in {
    assertElaborationFails(new FpDivSqrt(FP8), "BF16, FP16 and FP32 only")
    assertElaborationFails(new FpDivSqrt(FP8_ALT), "BF16, FP16 and FP32 only")
  }
}
