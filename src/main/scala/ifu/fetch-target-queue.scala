//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Fetch Target Queue (FTQ)
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Each entry in the FTQ holds the fetch address and branch prediction snapshot state.
//
// TODO:
// * reduce port counts.

package boom.ifu

import chisel3._
import chisel3.util._

import chisel3.experimental.{dontTouch}

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.util.{Str}

import boom.common._
import boom.exu._
import boom.util._

/**
 * FTQ Parameters used in configurations
 *
 * @param nEntries # of entries in the FTQ
 */
case class FtqParameters(
  nEntries: Int = 16
)

/**
 * Bundle to add to the FTQ RAM and to be used as the pass in IO
 */
class FTQBundle(implicit p: Parameters) extends BoomBundle
{
  val fetch_pc  = UInt(vaddrBitsExtended.W) // TODO compress out high-order bits
  val cfi_idx   = Valid(UInt(log2Ceil(fetchWidth).W)) // IDX of instruction that was predicted taken, if any
  val cfi_taken = Bool() // Was the CFI in this bundle found to be taken? or not
  val br_mask   = Vec(fetchWidth, Bool()) // mask of branches which were visible in this fetch bundle
  val jal_mask  = Vec(fetchWidth, Bool()) // mask of jumps which were visible in this fetch bundle
}

/**
 * IO to provide a port for a FunctionalUnit to get the PC of an instruction.
 * And for JALRs, the PC of the next instruction.
 */
class GetPCFromFtqIO(implicit p: Parameters) extends BoomBundle
{
  val ftq_idx   = Input(UInt(log2Ceil(ftqSz).W))

  val fetch_pc  = Output(UInt(vaddrBitsExtended.W))
  val fetch_cfi = Output(Valid(UInt(log2Ceil(fetchWidth).W)))
  val com_pc    = Output(UInt(vaddrBitsExtended.W))
  // the next_pc may not be valid (stalled or still being fetched)
  val next_val  = Output(Bool())
  val next_pc   = Output(UInt(vaddrBitsExtended.W))
}

/**
 * Queue to store the fetch PC and other relevant branch predictor signals that are inflight in the
 * processor.
 *
 * @param num_entries # of entries in the FTQ
 */
class FetchTargetQueue(num_entries: Int)(implicit p: Parameters) extends BoomModule
  with HasBoomCoreParameters
{
  private val idx_sz = log2Ceil(num_entries)

  val io = IO(new BoomBundle {
    // Enqueue one entry for every fetch cycle.
    val enq = Flipped(Decoupled(new FetchBundle()))
    // Pass to FetchBuffer (newly fetched instructions).
    val enq_idx = Output(UInt(idx_sz.W))
    // ROB tells us the youngest committed ftq_idx to remove from FTQ.
    val deq = Flipped(Valid(UInt(idx_sz.W)))

    // Give PC info to BranchUnit.
    val get_ftq_pc = new GetPCFromFtqIO()

    val redirect = Input(Valid(UInt(idx_sz.W)))

    val brupdate = Input(new BrUpdateInfo)

    val bpdupdate = Output(Valid(new BranchPredictionUpdate))

  })
  val bpd_ptr    = RegInit(0.U(idx_sz.W))
  val deq_ptr    = RegInit(0.U(idx_sz.W))
  val enq_ptr    = RegInit(1.U(idx_sz.W))

  val full = ((WrapInc(WrapInc(enq_ptr, num_entries), num_entries) === bpd_ptr) ||
              (WrapInc(enq_ptr, num_entries) === bpd_ptr))


  val ram = Reg(Vec(num_entries, new FTQBundle))

  val do_enq = io.enq.fire()

  when (do_enq) {
    ram(enq_ptr).fetch_pc  := io.enq.bits.pc
    ram(enq_ptr).cfi_idx   := io.enq.bits.cfi_idx
    // Initially, if we see a CFI, it is assumed to be taken.
    // Branch resolutions may change this
    ram(enq_ptr).cfi_taken := io.enq.bits.cfi_idx.valid
    ram(enq_ptr).br_mask   := io.enq.bits.br_mask
    ram(enq_ptr).jal_mask  := io.enq.bits.jal_mask

    enq_ptr := WrapInc(enq_ptr, num_entries)
  }

  io.enq.ready := !full
  io.enq_idx := enq_ptr

  io.bpdupdate.valid := false.B
  io.bpdupdate.bits  := DontCare

  // This register avoids a spurious bpd update on the first fetch packet

  val first_empty = RegInit(true.B)
  when (io.deq.valid) {
    deq_ptr := io.deq.bits
    first_empty := false.B
  }


  // We can update the branch predictors when we know the target of the
  // CFI in this fetch bundle

  when (bpd_ptr =/= deq_ptr && enq_ptr =/= WrapInc(bpd_ptr, num_entries) && !first_empty) {
    val cfi_idx = ram(bpd_ptr).cfi_idx.bits
    // TODO: We should try to commit branch prediction updates earlier
    io.bpdupdate.valid              := true.B
    io.bpdupdate.bits.pc            := ram(bpd_ptr).fetch_pc
    io.bpdupdate.bits.br_mask       := (0 until fetchWidth) map { i =>
      ram(bpd_ptr).br_mask(i) && ((i.U <= cfi_idx) || !ram(bpd_ptr).cfi_idx.valid)
    }
    io.bpdupdate.bits.cfi_idx.valid := ram(bpd_ptr).cfi_idx.valid
    io.bpdupdate.bits.cfi_idx.bits  := ram(bpd_ptr).cfi_idx.bits

    io.bpdupdate.bits.target        := ram(WrapInc(bpd_ptr, num_entries)).fetch_pc
    io.bpdupdate.bits.cfi_is_br     := ram(bpd_ptr).br_mask(cfi_idx)
    io.bpdupdate.bits.cfi_is_jal    := ram(bpd_ptr).jal_mask(cfi_idx)
    bpd_ptr := WrapInc(bpd_ptr, num_entries)
  }


  when (io.redirect.valid) {
    enq_ptr    := WrapInc(io.redirect.bits, num_entries)
  }

  when (io.brupdate.b2.mispredict) {
    val ftq_idx = io.brupdate.b2.uop.ftq_idx
    ram(ftq_idx).cfi_idx.valid := true.B
    ram(ftq_idx).cfi_idx.bits  := io.brupdate.b2.uop.pc_lob >> 1
    ram(ftq_idx).cfi_taken     := io.brupdate.b2.taken
  }

  //-------------------------------------------------------------
  // **** Core Read PCs ****
  //-------------------------------------------------------------

  io.get_ftq_pc.fetch_pc  := RegNext(ram(io.get_ftq_pc.ftq_idx).fetch_pc)
  io.get_ftq_pc.fetch_cfi := RegNext(ram(io.get_ftq_pc.ftq_idx).cfi_idx)
  io.get_ftq_pc.next_pc   := RegNext(ram(WrapInc(io.get_ftq_pc.ftq_idx, num_entries)).fetch_pc)
  io.get_ftq_pc.next_val  := RegNext(WrapInc(io.get_ftq_pc.ftq_idx, num_entries) =/= enq_ptr)
  io.get_ftq_pc.com_pc    := RegNext(ram(Mux(io.deq.valid, io.deq.bits, deq_ptr)).fetch_pc)
}
