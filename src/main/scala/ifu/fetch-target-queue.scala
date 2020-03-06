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
  with HasBoomFrontendParameters
{
  // // TODO compress out high-order bits
  // val fetch_pc  = UInt(vaddrBitsExtended.W)
  // IDX of instruction that was predicted taken, if any
  val cfi_idx   = Valid(UInt(log2Ceil(fetchWidth).W))
  // Was the CFI in this bundle found to be taken? or not
  val cfi_taken = Bool()
  // Was this CFI mispredicted by the branch prediction pipeline?
  val cfi_mispredicted = Bool()
  // What type of CFI was taken out of this bundle
  val cfi_type = Bool()
  // mask of branches which were visible in this fetch bundle
  val br_mask   = UInt(fetchWidth.W)
  // This CFI is likely a CALL
  val cfi_is_call   = Bool()
  // This CFI is likely a RET
  val cfi_is_ret    = Bool()
  // Is the NPC after the CFI +4 or +2
  val cfi_npc_plus4 = Bool()
  // What was the top of the RAS that this bundle saw?
  val ras_top = UInt(vaddrBitsExtended.W)

  // What global history should be used to query this fetch bundle
  val ghist = new GlobalHistory

  // Which bank did this start from?
  val start_bank = UInt(1.W)

  // // Metadata for the branch predictor
  // val bpd_meta = Vec(nBanks, UInt(bpdMaxMetaLength.W))
}

/**
 * IO to provide a port for a FunctionalUnit to get the PC of an instruction.
 * And for JALRs, the PC of the next instruction.
 */
class GetPCFromFtqIO(implicit p: Parameters) extends BoomBundle
{
  val ftq_idx   = Input(UInt(log2Ceil(ftqSz).W))

  val entry     = Output(new FTQBundle)

  val pc        = Output(UInt(vaddrBitsExtended.W))
  val com_pc    = Output(UInt(vaddrBitsExtended.W))
  // the next_pc may not be valid (stalled or still being fetched)
  val next_val  = Output(Bool())
  val next_pc   = Output(UInt(vaddrBitsExtended.W))

  // Used to regenerate PC for trace port stuff in FireSim
  // Don't tape this out, this blows up the FTQ
  val debug_ftq_idx  = Input(Vec(coreWidth, UInt(log2Ceil(ftqSz).W)))
  val debug_fetch_pc = Output(Vec(coreWidth, UInt(vaddrBitsExtended.W)))
}

/**
 * Queue to store the fetch PC and other relevant branch predictor signals that are inflight in the
 * processor.
 *
 * @param num_entries # of entries in the FTQ
 */
class FetchTargetQueue(num_entries: Int)(implicit p: Parameters) extends BoomModule
  with HasBoomCoreParameters
  with HasBoomFrontendParameters
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
    val get_ftq_pc = Vec(2, new GetPCFromFtqIO())

    val redirect = Input(Valid(UInt(idx_sz.W)))

    val brupdate = Input(new BrUpdateInfo)

    val bpdupdate = Output(Valid(new BranchPredictionUpdate))

    val ras_update = Output(Bool())
    val ras_update_idx = Output(UInt(log2Ceil(nRasEntries).W))
    val ras_update_pc  = Output(UInt(vaddrBitsExtended.W))

  })
  val bpd_ptr    = RegInit(0.U(idx_sz.W))
  val deq_ptr    = RegInit(0.U(idx_sz.W))
  val enq_ptr    = RegInit(1.U(idx_sz.W))

  val full = ((WrapInc(WrapInc(enq_ptr, num_entries), num_entries) === bpd_ptr) ||
              (WrapInc(enq_ptr, num_entries) === bpd_ptr))


  val pcs      = Reg(Vec(num_entries, UInt(vaddrBitsExtended.W)))
  val bpd_meta = SyncReadMem(num_entries, Vec(nBanks, UInt(bpdMaxMetaLength.W)))
  val ram      = Reg(Vec(num_entries, new FTQBundle))

  val do_enq = io.enq.fire()


  // This register lets us initialize the ghist to 0
  val start_from_empty_ghist = RegInit(true.B)

  when (do_enq) {
    start_from_empty_ghist := false.B

    pcs(enq_ptr)           := io.enq.bits.pc

    ram(enq_ptr).cfi_idx   := io.enq.bits.cfi_idx
    // Initially, if we see a CFI, it is assumed to be taken.
    // Branch resolutions may change this
    ram(enq_ptr).cfi_taken   := io.enq.bits.cfi_idx.valid
    ram(enq_ptr).cfi_mispredicted := false.B
    ram(enq_ptr).cfi_type    := io.enq.bits.cfi_type
    ram(enq_ptr).cfi_is_call := io.enq.bits.cfi_is_call
    ram(enq_ptr).cfi_is_ret  := io.enq.bits.cfi_is_ret
    ram(enq_ptr).cfi_npc_plus4 := io.enq.bits.cfi_npc_plus4
    ram(enq_ptr).ras_top     := io.enq.bits.ras_top
    ram(enq_ptr).br_mask     := io.enq.bits.br_mask & io.enq.bits.mask
    ram(enq_ptr).start_bank  := bank(io.enq.bits.pc)
    val prev_idx = WrapDec(enq_ptr, num_entries)
    val prev_entry = ram(prev_idx)
    ram(enq_ptr).ghist := Mux(start_from_empty_ghist,
      (0.U).asTypeOf(new GlobalHistory),
      Mux(io.enq.bits.ghist.current_saw_branch_not_taken,
        io.enq.bits.ghist,
        prev_entry.ghist.update(
          prev_entry.br_mask,
          prev_entry.cfi_taken,
          prev_entry.br_mask(prev_entry.cfi_idx.bits),
          prev_entry.cfi_idx.bits,
          prev_entry.cfi_idx.valid,
          pcs(prev_idx),
          prev_entry.cfi_is_call,
          prev_entry.cfi_is_ret
        )
      )
    )

    bpd_meta.write(enq_ptr, io.enq.bits.bpd_meta)

    enq_ptr := WrapInc(enq_ptr, num_entries)
  }

  io.enq.ready := !full
  io.enq_idx := enq_ptr

  io.bpdupdate.valid := false.B
  io.bpdupdate.bits  := DontCare

  when (io.deq.valid) {
    deq_ptr := io.deq.bits
  }

  // This register avoids a spurious bpd update on the first fetch packet
  val first_empty = RegInit(true.B)

  // We can update the branch predictors when we know the target of the
  // CFI in this fetch bundle

  val bpdupdate = Wire(Valid(new BranchPredictionUpdate))
  bpdupdate.valid := false.B
  bpdupdate.bits  := DontCare
  val ras_update = WireInit(false.B)
  val ras_update_pc = WireInit(0.U(vaddrBitsExtended.W))
  val ras_update_idx = WireInit(0.U(log2Ceil(nRasEntries).W))
  io.ras_update     := RegNext(ras_update)
  io.ras_update_pc  := RegNext(ras_update_pc)
  io.ras_update_idx := RegNext(ras_update_idx)
  when (bpd_ptr =/= deq_ptr && enq_ptr =/= WrapInc(bpd_ptr, num_entries)) {

    val entry = ram(bpd_ptr)
    val cfi_idx = entry.cfi_idx.bits

    // TODO: We should try to commit branch prediction updates earlier
    bpdupdate.valid              := !first_empty && (entry.cfi_idx.valid || entry.br_mask =/= 0.U)
    bpdupdate.bits.pc            := pcs(bpd_ptr)

    bpdupdate.bits.br_mask       := Mux(entry.cfi_idx.valid,
      MaskLower(UIntToOH(cfi_idx)) & entry.br_mask, entry.br_mask)
    bpdupdate.bits.cfi_idx.valid    := entry.cfi_idx.valid
    bpdupdate.bits.cfi_idx.bits     := entry.cfi_idx.bits
    bpdupdate.bits.cfi_mispredicted := entry.cfi_mispredicted
    bpdupdate.bits.cfi_taken        := entry.cfi_taken
    bpdupdate.bits.target           := pcs(WrapInc(bpd_ptr, num_entries))
    bpdupdate.bits.cfi_is_br        := entry.br_mask(cfi_idx)
    bpdupdate.bits.cfi_is_jal       := entry.cfi_type === CFI_JAL || entry.cfi_type === CFI_JALR
    bpdupdate.bits.ghist            := entry.ghist

    // ras_update     := entry.cfi_is_call
    // ras_update_pc  := bankAlign(pcs(bpd_ptr)) + (entry.cfi_idx.bits << 1) + Mux(entry.cfi_npc_plus4, 4.U, 2.U)
    // ras_update_idx := WrapInc(entry.ghist.ras_idx, nRasEntries)

    bpd_ptr := WrapInc(bpd_ptr, num_entries)

    first_empty := false.B
  }
  io.bpdupdate           := RegNext(bpdupdate)
  io.bpdupdate.bits.meta := bpd_meta.read(bpd_ptr)


  when (io.redirect.valid) {
    enq_ptr    := WrapInc(io.redirect.bits, num_entries)
  }

  when (io.brupdate.b2.mispredict) {
    val ftq_idx = io.brupdate.b2.uop.ftq_idx
    val entry = ram(ftq_idx)
    val new_cfi_idx = (io.brupdate.b2.uop.pc_lob ^
      Mux(entry.start_bank === 1.U, 1.U << log2Ceil(bankBytes), 0.U))(log2Ceil(fetchWidth), 1)

    ram(ftq_idx).cfi_idx.valid    := true.B
    ram(ftq_idx).cfi_idx.bits     := new_cfi_idx
    ram(ftq_idx).cfi_mispredicted := true.B
    ram(ftq_idx).cfi_taken        := io.brupdate.b2.taken
    ram(ftq_idx).cfi_is_call      := entry.cfi_is_call && entry.cfi_idx.bits === new_cfi_idx
    ram(ftq_idx).cfi_is_ret       := entry.cfi_is_ret  && entry.cfi_idx.bits === new_cfi_idx

  }
  when (io.redirect.valid || io.brupdate.b2.mispredict) {
    val entry = ram(Mux(io.redirect.valid, io.redirect.bits, io.brupdate.b2.uop.ftq_idx))
    ras_update     := true.B
    ras_update_pc  := entry.ras_top
    ras_update_idx := entry.ghist.ras_idx
  }

  //-------------------------------------------------------------
  // **** Core Read PCs ****
  //-------------------------------------------------------------

  for (i <- 0 until 2) {
    val idx = io.get_ftq_pc(i).ftq_idx
    val next_idx = WrapInc(idx, num_entries)
    val next_is_enq = (next_idx === enq_ptr) && io.enq.fire()
    val next_pc = Mux(next_is_enq, io.enq.bits.pc, pcs(next_idx))
    val get_entry = ram(idx)
    val next_entry = ram(next_idx)
    io.get_ftq_pc(i).entry     := RegNext(get_entry)
    io.get_ftq_pc(i).pc        := RegNext(pcs(idx))
    io.get_ftq_pc(i).next_pc   := RegNext(next_pc)
    io.get_ftq_pc(i).next_val  := RegNext(next_idx =/= enq_ptr || next_is_enq)
    io.get_ftq_pc(i).com_pc    := RegNext(pcs(Mux(io.deq.valid, io.deq.bits, deq_ptr)))
  }

  //-------------------------------------------------------------
  // **** Trace Debug PCs ****
  //-------------------------------------------------------------

  for (w <- 0 until coreWidth) {
    io.get_ftq_pc.debug_fetch_pc(w) := ram(io.get_ftq_pc.debug_ftq_idx(w)).fetch_pc
  }
}
