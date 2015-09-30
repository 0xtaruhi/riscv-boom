//**************************************************************************
// RISCV Processor Branch Prediction Pipeline
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2014 Apr 23
//
// Access branch predictor and redirect the pipeline as necessary. Also in
// charge of JALs (direction and target are known).
//
// These stages are effectively in parallel with instruction fetch and decode.
// BHT look-up (bp1) is in parallel with I$ access, and Branch Decode (bp2)
// occurs before fetch buffer insertion.
//
// Currently, I ignore JALRs (either the BTB took care of it or it'll get
// mispredicted and kill everything behind it anyways).

package BOOM
{

import Chisel._
import Node._

import rocket.Str

class RedirectRequest (fetch_width: Int) extends BOOMCoreBundle
{
   val target  = UInt(width = vaddrBits+1)
   val br_pc   = UInt(width = vaddrBits+1) // PC of the instruction changing control flow (to update the BTB with jumps)
   val idx     = UInt(width = log2Up(fetch_width)) // idx of br in fetch bundle (to mask out the appropriate fetch instructions)
   val is_jump = Bool() // (only valid if redirect request is valid)
  override def cloneType: this.type = new RedirectRequest(fetch_width).asInstanceOf[this.type]
}

// this information is shared across the entire fetch packet, stored in the ROB
// (conceptually anyways), and not given to the uop
class BranchPredictionResp extends BOOMCoreBundle
{
   val bpd_history     = Bits(width = GHIST_LENGTH)
   val btb_resp_valid  = Bool()
   val btb_resp        = new rocket.BTBResp
}

// give this to each instruction/uop and pass this down the pipeline to the branch-unit
class BranchPrediction extends BOOMCoreBundle
{
   // only update bpd_history on a pipeline misprediction
   // need to update the p-table on a bpd_misprediction
   val bpd_predict_taken= Bool() // did the bpd predict taken for this instruction? (if this differs from the true direction we need to train the p-table)

   val btb_hit          = Bool() // this instruction was the br/jmp predicted by the BTB
   val btb_predicted    = Bool() // BTB gets credit for the prediction otherwise check the BPD

   def wasBTB = btb_predicted
}

class BranchPredictionStage (fetch_width: Int) extends Module with BOOMCoreParameters
{
   val io = new BOOMCoreBundle
   {
      val imem       = new rocket.CPUFrontendIO
      val req        = Decoupled(new RedirectRequest(fetch_width))
      val pred_resp  = new BranchPredictionResp().asOutput
      val predictions= Vec.fill(fetch_width) {new BranchPrediction().asOutput}
      val ras_update = Valid(new rocket.RASUpdate)
      val br_unit    = new BranchUnitResp().asInput
      val kill       = Bool(INPUT) // e.g., pipeline flush
   }

   //-------------------------------------------------------------
   // Branch Prediction (BP1 Stage)

   val bp2_br_seen = Wire(Bool())  // did we see a branch to make a prediction?
                                   // (and not overridden by an earlier jal)
   val bp2_br_taken = Wire(Bool()) // was there a taken branch in the bp2 stage
                                   // we use this to update the bpd's history register speculatively
   val (bpd_valid, bpd_bits) =
      if (ENABLE_BRANCH_PREDICTOR)
      {
         val br_predictor = Module(new GshareBrPredictor(fetch_width = fetch_width
                                                   , num_entries = BPD_NUM_ENTRIES
                                                   , history_length = GHIST_LENGTH))
         br_predictor.io.req_pc := io.imem.npc
         br_predictor.io.update <> io.br_unit.bpd_update
         br_predictor.io.ghist.valid := bp2_br_seen && io.req.ready
         br_predictor.io.ghist.bits.taken := bp2_br_taken
         br_predictor.io.resp.ready := io.req.ready
         (br_predictor.io.resp.valid, br_predictor.io.resp.bits)
      }
      else
      {
         (Bool(false), new BpdResp().fromBits(Bits(0)))
      }

   //-------------------------------------------------------------
   // Branch Decode (BP2 Stage)
   //
   // 1) Which branch to take?
   // 2) Is there a jal earlier to take?
   // 3) Does the BTB override our prediction?
   //    - 3b) if no, verify BTB is correct?
   // 4) Update RAS

   // round off to nearest fetch boundary
   val aligned_pc = io.imem.resp.bits.pc & SInt(-(fetch_width*coreInstBytes))

   val is_br     = Wire(Vec(fetch_width, Bool()))
   val is_jal    = Wire(Vec(fetch_width, Bool()))
   val is_jr     = Wire(Vec(fetch_width, Bool()))
   val br_targs  = Wire(Vec(fetch_width, UInt(width=vaddrBits+1)))
   val jal_targs = Wire(Vec(fetch_width, UInt(width=vaddrBits+1)))

   for (i <- 0 until fetch_width)
   {
      val inst = io.imem.resp.bits.data(i)
      val bpd_decoder = Module(new BranchDecode)
      bpd_decoder.io.inst := inst

      is_br(i)  := bpd_decoder.io.is_br   && io.imem.resp.bits.mask(i)
      is_jal(i) := bpd_decoder.io.is_jal  && io.imem.resp.bits.mask(i)
      is_jr(i)  := bpd_decoder.io.is_jalr && io.imem.resp.bits.mask(i)

      val pc = aligned_pc + UInt(i << 2)
      br_targs(i)  := ComputeBranchTarget(pc, inst, xLen, coreInstBytes)
      jal_targs(i) := ComputeJALTarget(pc, inst, xLen, coreInstBytes)
   }


   //-------------------------------------------------------------
   // Output

   val predictions = is_br.toBits & bpd_bits.takens
   val br_val  = predictions.orR && bpd_valid
   val br_idx  = PriorityEncoder(predictions)
   val jal_val = is_jal.reduce(_|_)
   val jal_idx = PriorityEncoder(is_jal.toBits)
   val br_wins = br_val && (!jal_val || (br_idx < jal_idx))
   // TODO make this dynamic (use a meta predictor) explore different options here...
   // if bpd can decide "not-taken", then we need to change the btb_update logic in dpath

   // TODO can we assert that a jump is taken
   val jal_overrides = jal_val && (jal_idx < io.imem.btb_resp.bits.bridx || !io.imem.btb_resp.bits.taken)
   val btb_overrides = io.imem.btb_resp.valid && !jal_overrides //&& // btb predicted on this fetch packet
   // TODO debug the below stuff
//                       io.imem.btb_resp.bits.taken &&
//                       (io.imem.btb_resp.bits.bridx <= io.req.bits.idx)

   io.req.valid        := io.imem.resp.valid && (br_val || jal_val) && !btb_overrides
   io.req.bits.target  := Mux(br_wins, br_targs(br_idx), jal_targs(jal_idx))
   io.req.bits.idx     := Mux(br_wins, br_idx, jal_idx)
   io.req.bits.br_pc   := aligned_pc + (io.req.bits.idx << UInt(2))
   io.req.bits.is_jump := !br_wins

   io.pred_resp.bpd_history    := bpd_bits.history
   io.pred_resp.btb_resp_valid := io.imem.btb_resp.valid
   io.pred_resp.btb_resp       := io.imem.btb_resp.bits

   for (w <- 0 until FETCH_WIDTH)
   {
      io.predictions(w).bpd_predict_taken := predictions(w) && bpd_valid
      io.predictions(w).btb_predicted := btb_overrides
      io.predictions(w).btb_hit := Mux(io.imem.btb_resp.bits.bridx === UInt(w),
                                          io.imem.btb_resp.valid, Bool(false))
   }

   bp2_br_seen := io.imem.resp.valid && is_br.reduce(_|_) && (!jal_val || (PriorityEncoder(is_br.toBits) < PriorityEncoder(is_jal.toBits)))
   bp2_br_taken := (br_val && br_wins) || (io.imem.btb_resp.valid && io.imem.btb_resp.bits.taken)

   //-------------------------------------------------------------
   // Look for CALL and RETURN for RAS shenanigans.
   // TODO flush_take_pc should probably be given to the branch unit, instead of resetting it here?
   // NOTE: what about branch taken earlier?

   val jumps    = is_jal.toBits | is_jr.toBits
   val jmp_idx  = PriorityEncoder(jumps)
   val jmp_inst = io.imem.resp.bits.data(jmp_idx)
   val is_call  = IsCall(jmp_inst)
   val is_ret   = IsReturn(jmp_inst)
   io.imem.ras_update.valid           := io.imem.resp.valid &&
                                         jumps.orR &&
                                         !br_wins &&
                                         io.req.ready &&
                                         !io.kill
   io.imem.ras_update.bits.isCall     := is_call
   io.imem.ras_update.bits.isReturn   := is_ret
   io.imem.ras_update.bits.returnAddr := aligned_pc + (jmp_idx << UInt(2)) + UInt(4)
   io.imem.ras_update.bits.prediction := io.imem.btb_resp

   //-------------------------------------------------------------

   if (DEBUG_PRINTF)
   {
      printf("bp2_aligned_pc: 0x%x BHT:(%s 0x%x, %d) p:%x (%d) b:%x j:%x (%d) %s %s\n"
         , aligned_pc, Mux(io.req.valid, Str("TAKE"), Str(" -- ")), io.req.bits.target, io.req.bits.idx
         , predictions.toBits, br_idx, is_br.toBits, is_jal.toBits, jal_idx, Mux(br_wins, Str("BR"), Str("JA")), Mux(btb_overrides, Str("BO"), Str("--"))
         )
   }

   //-------------------------------------------------------------

   when (io.imem.resp.valid && io.imem.btb_resp.valid && io.imem.btb_resp.bits.taken)
   {
      val msk = io.imem.btb_resp.bits.mask
      val idx = io.imem.btb_resp.bits.bridx
      val targ = Mux(is_br(idx), br_targs(idx), jal_targs(idx))
      when (!is_jr(idx))
      {
         assert (io.imem.btb_resp.bits.target === targ(vaddrBits-1,0), "BTB is jumping to an invalid target.")
      }
   }
}

//-------------------------------------------------------------
//-------------------------------------------------------------

}
