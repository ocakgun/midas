// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import Mappers._
import ir._
import annotations._
import collection.mutable.ArrayBuffer
import midas.targetutils.FirrtlMemModelAnnotation

class LabelSRAMModels extends Transform {
  def inputForm = HighForm
  def outputForm = HighForm

  val confwriter = new passes.memlib.ConfWriter("NONE")
  val memutil = new passes.memlib.ReplaceMemMacros(confwriter)

  def mem2Module(mem: DefMemory): Module = {
    val ports = passes.MemPortUtils.memType(mem).fields.map(f => Port(NoInfo, f.name, Input, f.tpe))
    val connects = ports.map(p => Connect(NoInfo, WSubField(WRef(mem.name), p.name), WRef(p.name)))
    Module(mem.info, mem.name, ports, Block(mem +: connects))
  }

  override def execute(state: CircuitState): CircuitState = {
    val circ = state.circuit
    val moduleNS = Namespace(circ)
    val memModelAnnotations = new ArrayBuffer[FAMEModelAnnotation]
    val memModules = new ArrayBuffer[Module]
    val annotatedMems = state.annotations.collect({
      case FirrtlMemModelAnnotation(rt) => rt
    }).toSet
    val transformedModules = circ.modules.map({
      case m: Module =>
        val mt = ModuleTarget(circ.main, m.name)
        def onStmt(stmt: Statement): Statement = stmt.map(onStmt) match {
          case mem: DefMemory if annotatedMems.contains(mt.ref(mem.name)) =>
            val wrapper = mem2Module(mem).copy(name = moduleNS.newName(mem.name))
            memModules += wrapper
            memModelAnnotations += FAMEModelAnnotation(mt.instOf(mem.name, wrapper.name))
            WDefInstance(mem.info, mem.name, wrapper.name, UnknownType)
          case s => s
        }
        m.copy(body = m.body.map(onStmt))
      case m => m
    })
    val transformedCircuit = circ.copy(modules = memModules ++ transformedModules)
    state.copy(circuit = transformedCircuit, annotations = state.annotations ++ memModelAnnotations)
  }
}
