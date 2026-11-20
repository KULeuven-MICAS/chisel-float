// Copyright 2025 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51

// Author: Robin Geens <robin.geens@kuleuven.be>

package fp_unit

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.Comparator

import scala.jdk.CollectionConverters._

import chisel3.RawModule
import circt.stage.ChiselStage

/** Emits a design and its BlackBox sources as one SystemVerilog file, packages first. */
object FpEmitter {

  val defaultTargetDir: String = "generated/fp_unit"

  def apply(gen: => RawModule, fileName: String, targetDir: String = defaultTargetDir): Path = {
    val outDir   = Files.createDirectories(Paths.get(targetDir))
    val tmpDir   = Files.createTempDirectory(outDir, s".$fileName-")
    // Chisel writes an empty <top>.sv to its target directory, so firtool must write to another one.
    val splitDir = tmpDir.resolve("split")
    try {
      ChiselStage.emitSystemVerilogFile(
        gen,
        Array("--target-dir", tmpDir.toString),
        Array("--split-verilog", s"-o=$splitDir")
      )
      val sources            = Files
        .readAllLines(splitDir.resolve("filelist.f"))
        .asScala
        .toSeq
        .map(_.trim.stripPrefix("./"))
        .filter(_.nonEmpty)
        .distinct
        .map(file => file -> new String(Files.readAllBytes(splitDir.resolve(file)), UTF_8))
        .filter { case (_, text) => text.linesIterator.map(_.trim).exists(l => l.nonEmpty && !l.startsWith("//")) }
      val packageDecl        = raw"(?m)^\s*package\s+\w+\s*;".r
      val (packages, others) = sources.partition { case (_, text) => packageDecl.findFirstIn(text).isDefined }
      val text               = (packages ++ others).map { case (file, text) => s"// ----- $file -----\n$text" }
      val out                = outDir.resolve(s"$fileName.sv")
      Files.write(out, text.mkString("\n").getBytes(UTF_8))
    } finally {
      val paths = Files.walk(tmpDir)
      try paths.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
      finally paths.close()
    }
  }
}
