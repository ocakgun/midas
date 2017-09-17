package midas

import freechips.rocketchip.config.Parameters
import chisel3.{Data, Bundle}
import firrtl.ir.Circuit
import firrtl.CompilerUtils.getLoweringTransforms
import firrtl.passes.memlib._
import barstools.macros._
import java.io.{File, FileWriter, Writer}

// Compiler for Midas Transforms
private class MidasCompiler(dir: File, io: Data)(implicit param: Parameters) extends firrtl.Compiler {
  def emitter = new firrtl.LowFirrtlEmitter
  def transforms = getLoweringTransforms(firrtl.ChirrtlForm, firrtl.MidForm) ++ Seq(
    new InferReadWrite,
    new ReplSeqMem) ++
    getLoweringTransforms(firrtl.MidForm, firrtl.LowForm) ++ Seq(
    new passes.MidasTransforms(dir, io))
}

// Compilers to emit proper verilog
private class VerilogCompiler extends firrtl.Compiler {
  def emitter = new firrtl.VerilogEmitter
  def transforms = getLoweringTransforms(firrtl.HighForm, firrtl.LowForm) :+ (
    new firrtl.LowFirrtlOptimization)
}

object MidasCompiler {
  def apply(
      chirrtl: Circuit,
      io: Data,
      dir: File,
      lib: Option[File])
     (implicit p: Parameters): Circuit = {
    val conf = new File(dir, s"${chirrtl.main}.conf")
    val json = new File(dir, s"${chirrtl.main}.macros.json")
    val annotations = new firrtl.AnnotationMap(Seq(
      InferReadWriteAnnotation(chirrtl.main),
      ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$conf"),
      passes.MidasAnnotation(chirrtl.main, conf, json, lib),
      MacroCompilerAnnotation(chirrtl.main, MacroCompilerAnnotation.Params(
        json.toString, lib map (_.toString), CostMetric.default, true))))
    // val writer = new FileWriter(new File("debug.ir"))
    val writer = new java.io.StringWriter
    val midas = new MidasCompiler(dir, io) compile (
      firrtl.CircuitState(chirrtl, firrtl.ChirrtlForm, Some(annotations)), writer)
    // writer.close
    // firrtl.Parser.parse(writer.serialize)
    val verilog = new FileWriter(new File(dir, s"FPGATop.v"))
    val result = new VerilogCompiler compile (
      firrtl.CircuitState(midas.circuit, firrtl.HighForm), verilog)
    verilog.close
    result.circuit
  }

  def apply[T <: chisel3.experimental.RawModule](w: => T, dir: File, libFile: Option[File])
      (implicit p: Parameters): Circuit = {
    dir.mkdirs
    lazy val target = w
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(() => target))

    class RCBundle extends Bundle {
        val clock = target.getPorts(0).id.cloneType
        val reset = target.getPorts(1).id.cloneType
        // Skip Debug port
        val mem_axi4 = target.getPorts(3).id.cloneType
        val serial = target.getPorts(4).id.cloneType
        val uart = target.getPorts(5).id.cloneType
        val net = target.getPorts(6).id.cloneType
        val bdev = target.getPorts(7).id.cloneType

        override def cloneType = new RCBundle().asInstanceOf[this.type]
    }

    val rcbundle = new RCBundle
    apply(chirrtl, rcbundle, dir, libFile)
  }
}
