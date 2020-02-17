//******************************************************************************
// Copyright (c) 2012 - 2020, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Ring Microarchitecture Register Read
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.Parameters

import boom.common._
import boom.util._

class RingRegisterReadIO
  (implicit p: Parameters) extends BoomBundle
{
  // issued micro-ops
  val iss_uops   = Input(Vec(coreWidth, new MicroOp))
  val iss_valids = Input(Vec(coreWidth, Bool()))

  // interface with register file's read ports
  val rf_read_ports = Flipped(Vec(coreWidth, new BankReadPort(log2Ceil(numIntPhysRegs/coreWidth), xLen)))

  val bypass = Input(new BypassData(coreWidth, xLen))

  // send micro-ops to the execution pipelines
  val exe_reqs = Vec(coreWidth, new DecoupledIO(new FuncUnitReq(xLen)))

  val kill     = Input(Bool())
  val brupdate= Input(new BrUpdateInfo)
}

/**
 * Handle the register read and bypass network for the OoO backend
 * interfaces with the issue window on the enqueue side, and the execution
 * pipelines on the dequeue side.
 *
 * @param supportedUnitsArray seq of SupportedFuncUnits classes indicating what the functional units do
 */
class RingRegisterRead(implicit p: Parameters) extends BoomModule
{
  val io = IO(new RingRegisterReadIO)

  val rrd_valids       = Wire(Vec(coreWidth, Bool()))
  val rrd_uops         = Wire(Vec(coreWidth, new MicroOp))

  //-------------------------------------------------------------
  // hook up inputs

  // TODO wouldn't it be better to put rrdd after the registers?
  for (w <- 0 until coreWidth) {
    val supportedUnits = new SupportedFuncUnits(
                           alu  = true ,
                           jmp  = true ,
                           mem  = true ,
                           muld = true ,
                           fpu  = false,
                           csr  = true ,
                           fdiv = false,
                           ifpu = false)

    val rrd_decode_unit = Module(new RegisterReadDecode(supportedUnits))
    rrd_decode_unit.io.iss_valid := io.iss_valids(w)
    rrd_decode_unit.io.iss_uop   := io.iss_uops(w)

    rrd_valids(w) := RegNext(rrd_decode_unit.io.rrd_valid &&
                     !IsKilledByBranch(io.brupdate, rrd_decode_unit.io.rrd_uop) && !io.kill)
    rrd_uops(w)   := RegNext(GetNewUopAndBrMask(rrd_decode_unit.io.rrd_uop, io.brupdate))
  }

  //-------------------------------------------------------------
  // read ports

  val prs1_addr_cols = Transpose(VecInit(io.iss_uops zip io.iss_valids map { case (u,v) =>
                         u.prs1_col & Fill(coreWidth, v && u.prs1_reads_irf) } ))
  val prs2_addr_cols = Transpose(VecInit(io.iss_uops zip io.iss_valids map { case (u,v) =>
                         u.prs2_col & Fill(coreWidth, v && u.prs2_reads_irf) } ))

  // Col -> Bank Address Crossbar
  for (w <- 0 until coreWidth) {
    io.rf_read_ports(w).prs1_addr := Mux1H(prs1_addr_cols(w), io.iss_uops.map(_.prs1_spec))
    io.rf_read_ports(w).prs2_addr := Mux1H(prs2_addr_cols(w), io.iss_uops.map(_.prs2_spec))

    assert (PopCount(prs1_addr_cols(w)) <= 1.U, "[rrd] prs1_addr xbar collision on port " + w)
    assert (PopCount(prs2_addr_cols(w)) <= 1.U, "[rrd] prs2_addr xbar collision on port " + w)
  }

  val rrd_rs1_data = Wire(Vec(coreWidth, Bits(xLen.W)))
  val rrd_rs2_data = Wire(Vec(coreWidth, Bits(xLen.W)))
  rrd_rs1_data := DontCare
  rrd_rs2_data := DontCare

  val prs1_data_banks = RegNext(Transpose(prs1_addr_cols))
  val prs2_data_banks = RegNext(Transpose(prs2_addr_cols))

  // Bank -> Col Data Crossbar
  for (w <- 0 until coreWidth) {
    rrd_rs1_data(w) := Mux(rrd_uops(w).prs1 === 0.U,
                         0.U,
                         Mux1H(prs1_data_banks(w), io.rf_read_ports.map(_.prs1_data)))
    rrd_rs2_data(w) := Mux(rrd_uops(w).prs2 === 0.U,
                         0.U,
                         Mux1H(prs2_data_banks(w), io.rf_read_ports.map(_.prs2_data)))

    assert (PopCount(prs1_data_banks(w)) <= 1.U, "[rrd] prs1_data xbar collision on port " + w)
    assert (PopCount(prs2_data_banks(w)) <= 1.U, "[rrd] prs2_data xbar collision on port " + w)
  }

  //-------------------------------------------------------------
  // set outputs to execute pipelines

  for (w <- 0 until coreWidth) {
    io.exe_reqs(w).valid    := rrd_valids(w)
    io.exe_reqs(w).bits.uop := rrd_uops(w)
    io.exe_reqs(w).bits.rs1_data := rrd_rs1_data(w)
    io.exe_reqs(w).bits.rs2_data := rrd_rs2_data(w)
  }
}
