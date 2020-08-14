//******************************************************************************
// Copyright (c) 2015 - 2020, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Ring BusyTable
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import chisel3._
import chisel3.util._
import boom.common._
import boom.util._
import freechips.rocketchip.config.Parameters

class RingBusyResp extends Bundle
{
  val prs1_busy = Bool()
  val prs2_busy = Bool()

  val prs1_load = Bool()
  val prs2_load = Bool()
}

class RingBusyTable(
  val plWidth: Int,
  val numPregs: Int,
  val numWbPorts: Int)
  (implicit p: Parameters) extends BoomModule
{
  def DecodePreg(preg: UInt) = {
    val tmp = UIntToOH(preg)
    var ret = 0.U(1.W)
    val k = 1 << log2Ceil(numPregs/coreWidth)
    val n = numPregs / coreWidth

    for (w <- 0 until coreWidth) {
      ret = Cat(tmp(k*w+n-1, k*w), ret)
    }

    ret(numPregs,1)
  }

  val io = IO(new BoomBundle()(p) {
    val ren_uops = Input(Vec(plWidth, new MicroOp))
    val busy_resps = Output(Vec(plWidth, new RingBusyResp))
    val rebusy_reqs = Input(Vec(plWidth, Bool()))

    val wb_pdsts = Input(Vec(numWbPorts, UInt(ipregSz.W)))
    val wb_valids = Input(Vec(numWbPorts, Bool()))

    val debug = new Bundle { val busytable = Output(Bits(numPregs.W)) }
  })

  val busy_table = RegInit(0.U(numPregs.W))

  val r_rebusy_reqs = RegNext(io.rebusy_reqs)
  val r_ren_uops    = RegNext(io.ren_uops)

  busy_table := ( busy_table
                & ~(io.wb_pdsts zip io.wb_valids) .map {case (pdst, valid) =>
                     DecodePreg(pdst) & Fill(numPregs, valid)}.reduce(_|_)
                |  (r_ren_uops zip r_rebusy_reqs) .map {case (uop, req)  =>
                     DecodePreg(uop.pdst) & Fill(numPregs, req)}.reduce(_|_)
                )

  val load_table = RegInit(0.U(numPregs.W))

  load_table:= ( load_table
                & ~(r_ren_uops zip r_rebusy_reqs) .map {case (uop, req) =>
                     DecodePreg(uop.pdst) & Fill(numPregs, req && !uop.uses_ldq)}.reduce(_|_)
                |  (r_ren_uops zip r_rebusy_reqs) .map {case (uop, req)  =>
                     DecodePreg(uop.pdst) & Fill(numPregs, req &&  uop.uses_ldq)}.reduce(_|_)
                )


  // Read the busy table.
  for (i <- 0 until plWidth) {
    val prs1_bypass_hits = r_ren_uops zip r_rebusy_reqs map { case (u,v) => io.ren_uops(i).lrs1 === u.ldst && v }
    val prs2_bypass_hits = r_ren_uops zip r_rebusy_reqs map { case (u,v) => io.ren_uops(i).lrs2 === u.ldst && v }

    val prs1_bypass_sel = PriorityEncoderOH(prs1_bypass_hits.reverse).reverse
    val prs2_bypass_sel = PriorityEncoderOH(prs2_bypass_hits.reverse).reverse

    val prs1_bypass_load = Mux1H(prs1_bypass_sel, r_ren_uops.map(_.uses_ldq))
    val prs2_bypass_load = Mux1H(prs2_bypass_sel, r_ren_uops.map(_.uses_ldq))

    val prs1_bypass = prs1_bypass_hits.reduce(_||_)
    val prs2_bypass = prs2_bypass_hits.reduce(_||_)

    io.busy_resps(i).prs1_busy := (busy_table & DecodePreg(io.ren_uops(i).prs1)).orR || prs1_bypass
    io.busy_resps(i).prs2_busy := (busy_table & DecodePreg(io.ren_uops(i).prs2)).orR || prs2_bypass

    io.busy_resps(i).prs1_load := Mux(prs1_bypass, prs1_bypass_load, (load_table & DecodePreg(io.ren_uops(i).prs1)).orR)
    io.busy_resps(i).prs2_load := Mux(prs2_bypass, prs2_bypass_load, (load_table & DecodePreg(io.ren_uops(i).prs2)).orR)
  }

  io.debug.busytable := busy_table
}
