//******************************************************************************
// Copyright (c) 2015 - 2020, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Execution Unit Generator + Container Module for the Ring Microarchitecture
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import scala.collection.mutable.{ArrayBuffer}

import chisel3._

import freechips.rocketchip.config.{Parameters}

import boom.common._
import boom.util.{BoomCoreStringPrefix}

class RingExecutionUnits(implicit val p: Parameters) extends BoomModule
{
  // I/O which is used by all units
  // Unit-specific I/O (e.g. rocc) can still be hooked up with the unit getter functions
  val io = IO(new BoomBundle {
    val rrd_uops  = Input(Vec(coreWidth, Valid(new MicroOp)))
    val exe_resps = Output(Vec(coreWidth, Valid(new ExeUnitResp(xLen))))

    val brinfo    = Input(new BrResolutionInfo)
    val kill      = Input(Bool())
  })

  //*******************************
  // Instantiate the ExecutionUnits

  private val column_exe_units = ArrayBuffer[ExecutionUnit]()
  private val shared_exe_units = ArrayBuffer[ExecutionUnit]()

  def exe_units = column_exe_units ++ shared_exe_units

  //*******************************
  // Act like a collection

  def length = exe_units.length

  def apply(n: Int) = exe_units(n)

  def map[T](f: ExecutionUnit => T) = {
    exe_units.map(f)
  }

  def withFilter(f: ExecutionUnit => Boolean) = {
    exe_units.withFilter(f)
  }

  def foreach[U](f: ExecutionUnit => U) = {
    exe_units.foreach(f)
  }

  def zipWithIndex = {
    exe_units.zipWithIndex
  }

  def indexWhere(f: ExecutionUnit => Boolean) = {
    exe_units.indexWhere(f)
  }

  def count(f: ExecutionUnit => Boolean) = {
    exe_units.count(f)
  }

  lazy val memory_units = {
    shared_exe_units.filter(_.hasMem)
  }

  lazy val br_unit = {
    require (shared_exe_units.count(_.hasBrUnit) == 1)
    shared_exe_units.find(_.hasBrUnit).get
  }

  lazy val csr_unit = {
    require (exe_units.count(_.hasCSR) == 1)
    shared_exe_units.find(_.hasCSR).get
  }

  lazy val br_unit_io = {
    require (shared_exe_units.count(_.hasBrUnit) == 1)
    (shared_exe_units.find(_.hasBrUnit).get).io.br_unit
  }

  lazy val br_unit_idx = {
    shared_exe_units.indexWhere(_.hasBrUnit)
  }

  lazy val rocc_unit = {
    require (usingRoCC)
    require (exe_units.count(_.hasRocc) == 1)
    shared_exe_units.find(_.hasRocc).get
  }

  lazy val idiv_busy = {
    !shared_exe_units.find(_.hasDiv).get.io.fu_types(4)
  }

  // Generate column ALUs
  for (w <- 0 until coreWidth) {
    column_exe_units += Module(new ALUExeUnit)
  }

  // Generate memory access units. Only 1 supported for now
  for (w <- 0 until memWidth) {
    val memExeUnit = Module(new ALUExeUnit(
      hasAlu = false,
      hasMem = true))

    memExeUnit.io.ll_iresp.ready := DontCare

    shared_exe_units += memExeUnit
  }

  // Branch unit
  shared_exe_units += Module(new ALUExeUnit(hasAlu = false, hasBrUnit = true))

  // Put remaining functional units in a shared execution unit
  shared_exe_units += Module(new ALUExeUnit(hasAlu  = false,
                                            hasMul  = true,
                                            hasDiv  = true,
                                            hasCSR  = true,
                                            hasRocc = true))

  val exeUnitsStr = new StringBuilder
  for (exe_unit <- column_exe_units ++ shared_exe_units) {
    exeUnitsStr.append(exe_unit.toString)
  }

  override def toString: String =
    (BoomCoreStringPrefix("===ExecutionUnits===") + "\n"
    + (BoomCoreStringPrefix(
         "==" + coreWidth + "-wide Machine==",
         "==" + coreWidth + " Issue==")
    ) + "\n"
    + exeUnitsStr.toString)
}
