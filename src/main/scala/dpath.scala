//**************************************************************************
// RISCV Processor Datapath
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2012 Feb 14


package BOOM
{

import Chisel._
import Node._
import scala.collection.mutable.ArrayBuffer

import rocket.Instructions._
import rocket.ALU._
import FUCode._

import rocket._

/*

BOOM has the following (conceptual) stages:
  if1 - Instruction Fetch 1 (I$ access)
  if2 - Instruction Fetch 2 (instruction return)
  bp1 - Branch Predict      (in parallel with IF1)
  bp2 - Branch Decode       (in parallel with IF2)
  dec - Decode
  ren - Rename
  dis - Dispatch
  iss - Issue
  rrd - Register Read
  exe - Execute
  mem - Memory
  wb  - Writeback
  com - Commit

Terminology:
   jmp - refers to jal and jalr

Notes:
   Fence.i is handled by holding up pipeline, inserting fencei, then waiting
   for STQ to drain before fetching next instruction and clearing I$.

BUGS:
  scall isn't being counted as a retired instruction

Questions:

TODO LIST:

   better IW back pressure (requires worst case on store slots)
   add counters, for cache hits
   add branch counter in ROB (was predicted correctly)

   add a backing branch predictor that uses synchronous memory

   add (optional) register between issue select and register read

   have ROB issue mtpcr, etc.? poison bit in inst to roll back ROB
      - could give it its own issue_slot only it writes to at commit

   allow for under-provisioned regfile ports
   allow for load-use speculation

   add wait-bit memory disambiguation speculation to loads in the LSU

   allow queues to fill up completely (change full/head/tail logic)
      - difficult to do for store queue
      - kills only apply to partial sections (commit head), no easy way to track count

   how best to handle SRET, SYSCALL, etc.
      i think just have SRET set exception bit in ROB, don't even serialize pipeline?

   hit-under-miss icache

   stream fetchers, way-prediction

*/


//-------------------------------------------------------------
// TODO I can't promise these signals get killed/cleared on a mispredict,
// so I should listen to the corresponding valid bit
// For example, on a bypassing, we listen to rf_wen to see if bypass is valid,
// but we "could" be bypassing to a branch which kills us (false positive cobinational loop),
// so we have to keep the rf_wen enabled, and not dependent on a branch kill signal
class CtrlSignals extends Bundle()
{
   val br_type     = UInt(width = BR_N.getWidth)
   val op1_sel     = UInt(width = OP1_X.getWidth)
   val op2_sel     = UInt(width = OP2_X.getWidth)
   val imm_sel     = UInt(width = IS_X.getWidth)
   val op_fcn      = Bits(width = SZ_ALU_FN)
   val fcn_dw      = Bool()
   val rf_wen      = Bool()
   val pcr_fcn     = Bits(width = rocket.CSR.SZ)
   val is_load     = Bool()
   val is_sta      = Bool()
   val is_std      = Bool()
}


// TODO Chisel ability to union this Bundle for different types of Uops?
class MicroOp extends BOOMCoreBundle
{
   val valid            = Bool()                   // is this uop valid? or has it been masked out, used by fetch buffer and Decode stage

   val uopc             = Bits(width = UOPC_SZ)    // micro-op code
   val inst             = Bits(width = 32)
   val pc               = UInt(width = xprLen)
   val fu_code          = Bits(width = FUC_SZ)     // which functional unit do we use?
   val ctrl             = new CtrlSignals

   val wakeup_delay     = UInt(width = log2Up(MAX_WAKEUP_DELAY)) // unused
   val is_br_or_jmp     = Bool()                      // is this micro-op a (branch or jump) vs. a regular PC+4 inst?
   val is_jump          = Bool()                      // is this a jump? (note: not mutually exclusive with br_valid)
   val is_jal           = Bool()                      // is this a JAL? used for branch unit
   val is_ret           = Bool()                      // is jalr with rd=x0, rs1=x1? (i.e., a return)
   val is_call          = Bool()                      //
   val br_mask          = Bits(width = MAX_BR_COUNT)  // which branches are we being speculated under?
   val br_tag           = UInt(width = BR_TAG_SZ)

   val br_was_taken     = Bool()                      // set by Exe stage

   val fetch_pc_lob     = UInt(width = log2Up(FETCH_WIDTH*coreInstBytes)) // track which PC was used to fetch this instruction
   val btb_resp_valid   = Bool()                      // btb hit on this fetch packet (necessary to prevent duplicate entries in BTB)
   val btb_resp         = new rocket.BTBResp
   val btb_hit          = Bool()                      // btb hit on this instruction

   val imm_packed       = Bits(width = LONGEST_IMM_SZ) // densely pack the imm in decode... then translate and sign-extend in execute
   val rob_idx          = UInt(width = ROB_ADDR_SZ)
   val ldq_idx          = UInt(width = MEM_ADDR_SZ)
   val stq_idx          = UInt(width = MEM_ADDR_SZ)
   val pdst             = UInt(width = PREG_SZ)
   val pop1             = UInt(width = PREG_SZ)
   val pop2             = UInt(width = PREG_SZ)
   val pop3             = UInt(width = PREG_SZ)

   val prs1_busy        = Bool()
   val prs2_busy        = Bool()
   val prs3_busy        = Bool()
   val stale_pdst       = UInt(width = PREG_SZ)
   val exception        = Bool()
   val exc_cause        = UInt(width = xprLen)
   val sret             = Bool()
   val bypassable       = Bool()                      // can we bypass ALU results? (doesn't include loads, pcr, rdcycle, etc.... need to readdress this, SHOULD include PCRs?)
   val mem_cmd          = UInt(width = 4)             // sync primitives/cache flushes
   val mem_typ          = UInt(width = 3)             // memory mask type for loads/stores
   val is_fence         = Bool()
   val is_fencei        = Bool()
   val is_store         = Bool()                      // TODO AMOs are also considered stores?
   val is_amo           = Bool()
   val is_load          = Bool()
   val is_unique        = Bool()                      // only allow this instruction in the pipeline, wait for STQ to drain, clear fetch after it
                                                      // (tell ROB to un-ready until empty)
   val flush_on_commit  = Bool()                      // some instructions need to flush the pipeline behind them

   // logical specifiers (only used in Decode->Rename), except rollback (ldst)
   val ldst             = UInt(width=LREG_SZ)
   val lrs1             = UInt(width=LREG_SZ)
   val lrs2             = UInt(width=LREG_SZ)
   val lrs3             = UInt(width=LREG_SZ)
   val ldst_val         = Bool()              // is there a destination? invalid for stores, rd==x0, etc. TODO is there anytime the destination is pass through?
   val dst_rtype        = UInt(width=2)
   val lrs1_rtype       = UInt(width=2)
   val lrs2_rtype       = UInt(width=2)
   val frs3_en          = Bool()

   // floating point information
   val fp_val           = Bool()             // is a floating-point instruction (F- or D-extension)? If it's non-ld/st it will write back exception bits to the fcsr
   val fp_single        = Bool()             // single-precision floating point instruction (F-extension)

   // exception information
   val xcpt_ma          = Bool()
   val xcpt_if          = Bool()


   // purely debug information
   val debug_wdata      = Bits(width=xprLen)
   val debug_ei_enabled = Bool()
}

class FetchBundle extends Bundle with BOOMCoreParameters
{
//   val resp = new rocket.FrontEndResp // TODO consolidate everything into this
   val pc    = UInt(width = xprLen)
   val insts = Vec.fill(FETCH_WIDTH) { Bits(width = 32) }
   val mask  = Bits(width = FETCH_WIDTH) // mark which words are valid instructions

   val btb_resp_valid = Bool()
   val btb_resp = new rocket.BTBResp
   // TODO BUG XXX remove these two signals once things work
   val btb_pred_taken_idx = UInt(width=log2Up(FETCH_WIDTH))

   val xcpt_ma = Bool()
   val xcpt_if = Bool()
  override def clone = new FetchBundle().asInstanceOf[this.type]
}


class BrResolutionInfo extends BOOMCoreBundle
{
   val valid      = Bool()
   val mispredict = Bool()
   val mask       = Bits(width = MAX_BR_COUNT) // the resolve mask
   val tag        = UInt(width = BR_TAG_SZ)    // the branch tag that was resolved
   val exe_mask   = Bits(width = MAX_BR_COUNT) // the br_mask of the actual branch uop
                                               // used to reset the dec_br_mask


   val rob_idx    = UInt(width = ROB_ADDR_SZ)
   val ldq_idx    = UInt(width = MEM_ADDR_SZ)  // track the "tail" of loads and stores, so we can
   val stq_idx    = UInt(width = MEM_ADDR_SZ)  // quickly reset the LSU on a mispredict
}

class CacheCounters() extends Bundle
{
//   val dc_misses = UInt(width = xprLen)
   val dc_miss = Bool()
//   val ic_misses = UInt(width = xprLen)
   val ic_miss = Bool()
}

//-------------------------------------------------------------
//-------------------------------------------------------------
//-------------------------------------------------------------

class DpathIo() extends Bundle()
{
   val host = new uncore.HTIFIO
   val imem = new rocket.CPUFrontendIO
   val dmem = new DCMemPortIo
   val ptw =  new rocket.DatapathPTWIO().flip
   val counters = new CacheCounters().asInput
}

class DatPath() extends Module with BOOMCoreParameters
{
   val io = new DpathIo()

   //**********************************
   // Pipeline State Registers
   // Forward Declared Wires

   val flush_take_pc  = Bool()  // redirect PC due to a flush
   val flush_pc       = UInt()
   val flush_pipeline = Bool()  // kill entire pipeline (i.e., exception, load misspeculations)

   // Instruction Fetch State
   val if_pc_next     = UInt(width = xprLen)
   val pcr_exc_target = UInt(width = vaddrBits)


   // Branch Predict State
   val bp2_val               = Bool()
   val bp2_jal_val           = Bool()
   val bp2_jalr_val          = Bool()
   val bp2_take_pc           = Bool()
   val bp2_wants_to_take_pc  = Bool()
   val bp2_pred_target       = UInt(width=xprLen)
   val bp2_pc_of_jmp_inst    = UInt(width=xprLen)
   val bp2_jmp_inst          = Bits()

   // Instruction Decode State
   val dec_valids     = Vec.fill(DECODE_WIDTH) {Bool()}  // is the incoming, decoded instruction valid? It may be held up though. TODO confusing wrt dec_mask?
   val dec_uops       = Vec.fill(DECODE_WIDTH) {new MicroOp()}
   val dec_mask       = Vec.fill(DECODE_WIDTH) {Bool()}  // will the inst progress down the pipeline?
   val dec_rdy        = Bool()

   val rob_rdy        = Bool()
   val laq_full       = Bool()
   val stq_full       = Bool()

   val pcr_status     = new rocket.Status()

   // Register Rename State
   val ren_insts_can_proceed = Vec.fill(DECODE_WIDTH) { Bool() }

   // Dispatch State
   val dis_valid      = Bool() // used to insert into ROB, IW TODO: (let uops have valid signals?)
   val dis_insts_can_proceed = Vec.fill(DISPATCH_WIDTH) { Bool() }
   val dis_mask       = Vec.fill(DISPATCH_WIDTH) { Bool() } // true if uop WILL enter IW/ROB
   val dis_uops       = Vec.fill(DISPATCH_WIDTH) { new MicroOp() }


   // Issue State/Register Read/Execute State

   val exe_units = ArrayBuffer[ExecutionUnit]()

   if (DECODE_WIDTH == 1) println("\n   ~*** One-wide Machine ***~\n")
   else if (DECODE_WIDTH == 2) println("\n   ~*** Two-wide Machine ***~\n")
   else if (DECODE_WIDTH == 4) println("\n   ~*** Four-wide Machine ***~\n")
   else println("\n ~*** Unknown Machine Width ***~\n")

   require (ISSUE_WIDTH <= 3)

   if (ISSUE_WIDTH == 1)
   {
      println("\n    -== Single Issue ==- \n")
      var mem_unit:ExecutionUnit  = null
      if (params(BuildFPU).isEmpty)
      {
         println ("\n   FPU Unit Disabled\n")
         mem_unit = Module(new ALUMulDMemExeUnit(is_branch_unit = true,
                                          shares_pcr_wport = true))
      }
      else
      {
         println ("\n   FPU Unit Enabled\n")
         mem_unit = Module(new FPUALUMulDMemExeUnit(is_branch_unit = true,
                                          shares_pcr_wport = true))
      }
      exe_units += mem_unit
      mem_unit.io.dmem <> io.dmem
   }
   else if (ISSUE_WIDTH == 2)
   {
      println("\n    -== Dual Issue ==- \n")
      // TODO make a ALU/Mem unit, or a ALU-i/Mem unit
      val mem_unit = Module(new ALUMulDMemExeUnit())
      exe_units += Module(new ALUExeUnit(is_branch_unit = true,
                                          shares_pcr_wport = true))
      exe_units += mem_unit
      mem_unit.io.dmem <> io.dmem
   }
   else
   {
      println("\n    -== Triple Issue ==- \n")
      val alu_unit    = Module(new ALUExeUnit(is_branch_unit = true,
                                     shares_pcr_wport = true))
      val muld_unit   = Module(new ALUMulDExeUnit())
      //val muld_unit = Module(new MulDExeUnit())
      val mem_unit    = Module(new MemExeUnit())
      mem_unit.io.dmem <> io.dmem
      exe_units += alu_unit
      exe_units += muld_unit
      exe_units += mem_unit
   }

   val num_rf_read_ports = 3*exe_units.length // TODO BUG XXX correct this logic

   var num_rf_write_ports = 0
   var num_total_bypass_ports = 0
   var num_fast_wakeup_ports = 0 // +1 for each exe_unit that allows bypassing
   var num_slow_wakeup_ports = 0 // +1 for each exe_unit that writes to the regfile (not the LSU)
   for (w <- 0 until exe_units.length)
   {
      for (j <- 0 until exe_units(w).num_rf_write_ports)
      {
         num_slow_wakeup_ports += 1
         num_rf_write_ports += 1
      }

      if (exe_units(w).is_bypassable)
      {
         num_fast_wakeup_ports += 1
         for (i <- 0 until exe_units(w).get_num_bypass_ports)
         {
            num_total_bypass_ports = num_total_bypass_ports + 1
         }
      }
   }

   val num_wakeup_ports = num_slow_wakeup_ports + num_fast_wakeup_ports
   val rf_cost = (num_rf_read_ports+num_rf_write_ports)*(num_rf_read_ports+2*num_rf_write_ports) // TODO this number is wrong

   println("   Num RF Read Ports    : " + num_rf_read_ports)
   println("   Num RF Write Ports   : " + num_rf_write_ports + "\n")
   println("   RF Cost (R+W)*(R+2W) : " + rf_cost + "\n")
   println("   Num Slow Wakeup Ports: " + num_slow_wakeup_ports)
   println("   Num Fast Wakeup Ports: " + num_fast_wakeup_ports)
   println("   Num Bypass Ports     : " + num_total_bypass_ports)
   println("")

   val register_width = if (params(BuildFPU).isEmpty) xprLen else 65
   val bypasses = new BypassData(num_total_bypass_ports, register_width)

   val issue_width           = exe_units.length // TODO allow exe_units to have multiple issue ports
   val iss_valids            = Vec.fill(issue_width) {Bool()}
   val iss_uops              = Vec.fill(issue_width) {new MicroOp()}

   val br_unit               = new BranchUnitResp()

   val throw_idle_error      = Reg(init = Bool(false))

   // Memory State
   var lsu_io:LoadStoreUnitIo = null
   lsu_io = (exe_units.find(_.is_mem_unit).get).io.lsu_io // assume only one mem_unit

   // Writeback State

   // Commit Stage
   val com_valids            = Vec.fill(DECODE_WIDTH) {Bool()}
   val com_uops              = Vec.fill(DECODE_WIDTH) {new MicroOp()}
   val com_exception         = Bool()
   val com_exc_cause         = UInt()
   val com_handling_exc      = Bool()

   val com_fflags_val        = Bool()
   val com_fflags            = Bits()

   val com_rbk_valids        = Vec.fill(DECODE_WIDTH) {Bool()}

   val lsu_misspec           = Bool()

   val rob_empty             = Bool()


   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Fetch Stage/Frontend ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   val fetchbuffer_kill = Bool()
   val fetch_bundle = new FetchBundle()

   val FetchBuffer = Module(new Queue(gen=new FetchBundle,
                                entries=FETCH_BUFFER_SZ,
                                pipe=false,
                                flow=params(EnableFetchBufferFlowThrough),
                                _reset=(fetchbuffer_kill || reset.toBool)))

   val if_stalled = Bool() // if FetchBuffer backs up, we have to stall the front-end
   if_stalled := !(FetchBuffer.io.enq.ready)

   val com_sret = (Range(0,DECODE_WIDTH).map{i => com_valids(i) && com_uops(i).sret}).reduce(_|_)

   val take_pc = br_unit.take_pc ||
                 flush_take_pc ||
                 com_sret ||
                 (bp2_take_pc && !if_stalled) // TODO this seems way too low-level, to get this backpressure signal correct

   assert (!(Reg(next=com_exception) && !flush_pipeline), "exception occurred, but pipeline flush signal not set!")

   io.imem.req.valid   := take_pc // tell front-end we had an unexpected change in the stream
   io.imem.req.bits.pc := if_pc_next
   io.imem.resp.ready  := FetchBuffer.io.enq.ready // TODO perf BUG || take_pc?

   if_pc_next :=  Mux((com_exception || com_sret), pcr_exc_target,
                  Mux(flush_take_pc              , flush_pc,
                  Mux(br_unit.take_pc            , br_unit.target,
                                                   bp2_pred_target))) // bp2_take_pc

   // Fetch Buffer
   FetchBuffer.io.enq.valid := io.imem.resp.valid && !fetchbuffer_kill
   FetchBuffer.io.enq.bits  := fetch_bundle
   // delay sret signal for critical path reasons, but okay b/c we're still in the shadow of the PC redirect
   fetchbuffer_kill         := br_unit.brinfo.mispredict || com_exception || flush_pipeline || Reg(next=com_sret)

   fetch_bundle.pc   := io.imem.resp.bits.pc

   for (i <- 0 until FETCH_WIDTH)
   {
      fetch_bundle.insts(i) := io.imem.resp.bits.data(i)
   }
   fetch_bundle.btb_resp_valid  := io.imem.btb_resp.valid
   fetch_bundle.btb_resp := io.imem.btb_resp.bits
   fetch_bundle.btb_pred_taken_idx:= io.imem.btb_resp.bits.bridx

   fetch_bundle.xcpt_ma := io.imem.resp.bits.xcpt_ma
   fetch_bundle.xcpt_if := io.imem.resp.bits.xcpt_if

   // check for unallowed exceptions
   assert(!Reg(init=Bool(false),next=(com_exception && (com_exc_cause === UInt(rocket.Causes.misaligned_fetch) ||
                              com_exc_cause === UInt(rocket.Causes.fault_fetch)))), "Exception thrown by IMEM, not yet supported.")


   // TODO turn this into an I/O so we can bundle this up? Is there a way to Mux two bundles in?
//   io.imem.btb_update <> br_unit.btb_update
   // TODO flush_take_pc should probably be given to the branch unit, instead of resetting it here?
   val jal_opc = UInt(0x6f)
   val jalr_opc = UInt(0x67)
   def GetUop(inst: Bits): Bits = inst(6,0)
   def IsCall(inst: Bits): Bool = (inst === JAL || inst === JALR) && inst(RD_MSB,RD_LSB) === RA
//   def IsCall(inst: Bits): Bool = (GetUop(inst) === jal_opc || GetUop(inst) === jalr_opc) && inst(RD_MSB,RD_LSB) === RA TODO deleteme
   def IsReturn(inst: Bits): Bool = GetUop(inst) === jalr_opc && inst(RD_MSB,RD_LSB) === X0 && inst(RS1_MSB,RS1_LSB) === RA

   io.imem.btb_update.valid           := (br_unit.btb_update_valid || (bp2_take_pc && !if_stalled && !br_unit.take_pc)) && !flush_take_pc && !com_sret

   // if branch unit mispredicts, jump in decode is no longer valid
   io.imem.btb_update.bits.pc         := Mux(br_unit.btb_update_valid, br_unit.btb_update.pc, io.imem.resp.bits.pc)
   io.imem.btb_update.bits.br_pc      := Mux(br_unit.btb_update_valid, br_unit.btb_update.br_pc, bp2_pc_of_jmp_inst)
   io.imem.btb_update.bits.target     := Mux(br_unit.btb_update_valid, br_unit.btb_update.target, bp2_pred_target & SInt(-coreInstBytes))

   io.imem.btb_update.bits.prediction := Mux(br_unit.btb_update_valid, br_unit.btb_update.prediction, io.imem.btb_resp)
   io.imem.btb_update.bits.taken      := Mux(br_unit.btb_update_valid, br_unit.btb_update.taken, bp2_take_pc && !if_stalled)
   io.imem.btb_update.bits.isJump     := Mux(br_unit.btb_update_valid, br_unit.btb_update.isJump, Bool(true))
   io.imem.btb_update.bits.isReturn   := Mux(br_unit.btb_update_valid, br_unit.btb_update.isReturn, Bool(false))

   io.imem.bht_update := br_unit.bht_update

   val bp2_is_call = IsCall(bp2_jmp_inst)
   val bp2_is_ret  = IsReturn(bp2_jmp_inst)
   io.imem.ras_update.valid           := bp2_val &&
                                         (bp2_jal_val || bp2_jalr_val) &&
                                         !if_stalled &&
                                         !flush_take_pc
   io.imem.ras_update.bits.isCall     := bp2_is_call
   io.imem.ras_update.bits.isReturn   := !bp2_is_call
   io.imem.ras_update.bits.returnAddr := bp2_pc_of_jmp_inst + UInt(4)
   io.imem.ras_update.bits.prediction := io.imem.btb_resp


   // must flush cache on process change
   // if PCR tells me "flush due to TLB", also flush BTB
   io.imem.invalidate := Range(0,DECODE_WIDTH).map{i => com_valids(i) && com_uops(i).is_fencei}.reduce(_|_)
//                        pcr_ptbr_wen // invalidate on process switch (page table
                                     // walker updated base register)

   //io.imem.ptw := ...  // hooked straight up to tlb.io.ptw TODO
   //io.imem.ptw.status := pcr_status // hooked straight up to tlb.io.ptw TODO

   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Branch Prediction ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   // These stages are effectively in parallel with instruction fetch and
   // decode.  BHT look-up is in parallel with I$ access, and Branch Decode
   // occurs before fetch buffer insertion.

   //-------------------------------------------------------------
   // Branch Prediction (BP1 Stage)

   // TODO the "backing" branch predictor is being removed for now
   // it has been replaced by the GShared predictor in the rocket FrontEnd
   // adding this predictor back will require a more careful design that
   // properly accounts for super-scalar predictions and updates and works
   // properly with the frontend's predictors.

//   bp2_reg_predictor_out.taken := Bool(false)

   //-------------------------------------------------------------
   // Branch Decode (BP2 Stage)
   //
   // Look for JAL and compute targets.  Also need to look for JALR
   // for RAS shenanigans.
   //
   // kill all instructions behind the first jal.

   bp2_val := io.imem.resp.valid

   // round off to nearest fetch boundary
   val bp2_aligned_pc = io.imem.resp.bits.pc & SInt(-(FETCH_WIDTH*coreInstBytes))

   // and which is the first jmp?
   // Note: assume it's not possible for a JALR to be followed by a JAL (no
   // instruction compression coming into this stage).
   val bp2_jmp_idx = UInt()
   bp2_jal_val := Bool(false)
   bp2_jalr_val := Bool(false)
   bp2_jmp_idx := UInt(0)

   // look for branches and JALs in the fetch packet
   for (i <- FETCH_WIDTH-1 to 0 by -1)
   {
      val bpd_decoder = Module(new BranchDecode)
      bpd_decoder.io.inst := fetch_bundle.insts(i)

      when ((bpd_decoder.io.is_jal || bpd_decoder.io.is_jalr) && io.imem.resp.bits.mask(i))
      {
         bp2_jal_val := bpd_decoder.io.is_jal
         bp2_jalr_val := bpd_decoder.io.is_jalr
         bp2_jmp_idx := UInt(i)
      }
   }

   // pull out the instruction(s) we are predicting on, to compute the branch and jal targets
   val jinst = fetch_bundle.insts(bp2_jmp_idx)
   bp2_jmp_inst := jinst
   val bp2_jal_imm32 = Cat(Fill(jinst(31),12), jinst(19,12), jinst(20), jinst(30,25), jinst(24,21), Bits(0,1))
   val bp2_jalpred_target = UInt(width=xprLen)
   require (FETCH_WIDTH <= 2)
   bp2_jalpred_target := (bp2_aligned_pc + Mux(bp2_jmp_idx === UInt(1), UInt(4), UInt(0)) + Sext(bp2_jal_imm32, xprLen)) & SInt(-coreInstBytes)

   bp2_pc_of_jmp_inst := bp2_aligned_pc + (bp2_jmp_idx * UInt(4))

   if (DEBUG_PRINTF)
   {
      printf("bp2_aligned_pc: 0x%x bp2_pc_of_jmp: 0x%x, jal_idx: %d, imm32: 0x%x %d jalpred_target: 0x%x\n", bp2_aligned_pc, bp2_pc_of_jmp_inst, bp2_jmp_idx, bp2_jal_imm32, bp2_jal_imm32, bp2_jalpred_target)
   }

   // Does the branch predictor want to redirect the PC? This is before we've
   // arbitrated with the BTB.
   bp2_wants_to_take_pc := !(br_unit.brinfo.mispredict) &&
                           bp2_val &&
                           bp2_jal_val

   bp2_pred_target := bp2_jalpred_target

   // the instruction the branch predictor is predicting on
   val bp2_pred_idx = bp2_jmp_idx

   // does the BP2 stage get to change the pc? Or does the BTB's actions win?
   // The BTB wins if it predicts UNLESS BP2 redirects a jump that's earlier than the BTB's prediction.
   val bp2_bht_overrides_btb = bp2_val &&
                               bp2_jal_val &&
                               fetch_bundle.btb_resp_valid &&
                               (bp2_pred_idx < fetch_bundle.btb_pred_taken_idx)

   bp2_take_pc := bp2_wants_to_take_pc &&
                  (!(fetch_bundle.btb_resp_valid && fetch_bundle.btb_resp.taken) || bp2_bht_overrides_btb)



   // It's the job of the BHT to verify that if the BTB predicts on a JAL, it got it right.
   // It must also check that the BTB didn't miss the JAL and predict on a later branch

   // did the BTB predict JAL *AND* was it the first JAL in the fetch packet
   val btb_predicted_our_jal = fetch_bundle.btb_resp_valid &&
                               fetch_bundle.btb_resp.taken &&
                               bp2_jal_val &&
                               (bp2_jmp_idx === fetch_bundle.btb_pred_taken_idx)
   // check that the BTB predicted the correct jal target
//   assert (!Reg(init=Bool(false), next=(bp2_val && btb_predicted_our_jal && bp2_jalpred_target != io.imem.resp.bits.debug_taken_pc)), "BTB predicted incorrect JAL target")

   // TODO generalize the assert that checks for the BTB pred_idx
   require (FETCH_WIDTH <= 2)
   val btb_predicted_inst = fetch_bundle.insts(fetch_bundle.btb_pred_taken_idx)
   val btb_predicted_inst_pc =  bp2_aligned_pc + Mux(fetch_bundle.btb_pred_taken_idx === UInt(1), UInt(4), UInt(0))  + Sext(DebugGetBJImm(btb_predicted_inst), xprLen)
   //assert (!(io.imem.resp.valid &&
   //          io.imem.resp.bits.taken &&
   //          !DebugIsJALR(btb_predicted_inst) &&
   //          btb_predicted_inst_pc != io.imem.resp.bits.debug_taken_pc),
   //        "BTB predicted incorrect target.")

   val jal_kill_mask = Bits(width = FETCH_WIDTH)
   jal_kill_mask := Fill(bp2_jal_val, FETCH_WIDTH) & (SInt(-1, FETCH_WIDTH) << UInt(1) << bp2_jmp_idx)

   fetch_bundle.mask := (io.imem.resp.bits.mask & ~jal_kill_mask)


   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Decode Stage ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   // track mask of finished instructions in the bundle
   // use this to mask out insts coming from FetchBuffer that have been finished
   // for example, back pressure may cause us to only issue some instructions from FetchBuffer
   // but on the next cycle, we only want to retry a subset
   val dec_finished_mask = Reg(init = Bits(0, DECODE_WIDTH))

   // TODO need to figure out how to generalize this logic to other width disparities
   require (DECODE_WIDTH == FETCH_WIDTH)

   //-------------------------------------------------------------
   // Pull out instructions and send to the Decoders

   val dec_serializer = Module(new FetchSerializerNtoM)
   dec_serializer.io.enq <> FetchBuffer.io.deq

   dec_serializer.io.kill := fetchbuffer_kill
   dec_serializer.io.deq.ready := dec_rdy

   val fetched_inst_valid = dec_serializer.io.deq.valid
   val dec_fbundle        = dec_serializer.io.deq.bits

   //-------------------------------------------------------------
   // Decoders

   // allow early instructions to stall later instructions
   var dec_stall_next_inst = Bool(false)

   // stall fetch/dcode because we ran out of branch tags
   val branch_mask_full = Vec.fill(DECODE_WIDTH) { Bool() }

   for (w <- 0 until DECODE_WIDTH)
   {
      val decode_unit = Module(new DecodeUnit)
      dec_valids(w) := fetched_inst_valid && dec_fbundle(w).valid && !dec_finished_mask(w) // TODO a way to do this without being confusing wrt dec_mask?
      decode_unit.io.enq.uop     := dec_fbundle(w)
      decode_unit.io.status      := pcr_status

      val prev_insts_in_bundle_valid = Range(0,w).map{i => dec_valids(i)}.foldLeft(Bool(false))(_|_)

      // stall this instruction?
      // TODO tailor this to only care if a given instruction uses a resource?
      val stall_me = (  !(ren_insts_can_proceed(w))
                     || (dec_valids(w) && dec_uops(w).is_unique && (!rob_empty || !lsu_io.lsu_fencei_rdy || prev_insts_in_bundle_valid))
                     || !rob_rdy
                     || laq_full
                     || stq_full
                     || branch_mask_full(w)
                     || br_unit.brinfo.mispredict
                     || flush_pipeline
                     || dec_stall_next_inst
                     || (dec_valids(w) && dec_uops(w).is_fencei && !lsu_io.lsu_fencei_rdy)
                     )

      // stall the next instruction following me in the decode bundle?
      dec_stall_next_inst  = stall_me ||
                             (dec_valids(w) && dec_uops(w).is_unique)

      dec_mask(w) := dec_valids(w) && !stall_me
      dec_uops(w) := decode_unit.io.deq.uop
   }

   // all decoders are empty and ready for new instructions
   dec_rdy := !(dec_stall_next_inst)

   when (dec_rdy || fetchbuffer_kill)
   {
      dec_finished_mask := Bits(0)
   }
   .otherwise
   {
      dec_finished_mask := dec_mask.toBits | dec_finished_mask
   }

   //-------------------------------------------------------------
   // Branch Mask Logic

   val dec_brmask_logic = Module(new BranchMaskGenerationLogic(DECODE_WIDTH))

   dec_brmask_logic.io.brinfo := br_unit.brinfo
   dec_brmask_logic.io.flush_pipeline := flush_pipeline

   for (w <- 0 until DECODE_WIDTH)
   {
      dec_brmask_logic.io.is_branch(w) := (dec_valids(w) && dec_uops(w).is_br_or_jmp && !dec_uops(w).is_jal)
      dec_brmask_logic.io.will_fire(w) := dis_mask(w)

      dec_uops(w).br_tag  := dec_brmask_logic.io.br_tag(w)
      dec_uops(w).br_mask := dec_brmask_logic.io.br_mask(w)
   }

   branch_mask_full := dec_brmask_logic.io.is_full

   //-------------------------------------------------------------
   // LD/ST Unit Allocation Logic

   // TODO this is dupliciated logic with the the LSU... do we need ldq_idx/stq eisewhere?
   val new_ldq_idx = UInt()
   val new_stq_idx = UInt()

   var new_lidx = new_ldq_idx
   var new_sidx = new_stq_idx

   for (w <- 0 until DECODE_WIDTH)
   {
      dec_uops(w).ldq_idx := new_lidx
      dec_uops(w).stq_idx := new_sidx

      new_lidx = Mux(dec_mask(w) && dec_uops(w).is_load,  WrapInc(new_lidx, NUM_LSU_ENTRIES), new_lidx)
      new_sidx = Mux(dec_mask(w) && dec_uops(w).is_store, WrapInc(new_sidx, NUM_LSU_ENTRIES), new_sidx)
//      new_lidx = Mux(dec_mask(w) && dec_uops(w).is_load, new_lidx + UInt(1), new_lidx)
//      new_sidx = Mux(dec_mask(w) && dec_uops(w).is_store, new_sidx + UInt(1), new_sidx)
   }


   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Register Rename Stage ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   val rename_stage = Module(new RenameStage(DECODE_WIDTH, num_wakeup_ports))

   rename_stage.io.dis_inst_can_proceed := dis_insts_can_proceed

   rename_stage.io.kill     := br_unit.brinfo.mispredict || flush_pipeline
   rename_stage.io.brinfo   := br_unit.brinfo

   rename_stage.io.flush_pipeline := flush_pipeline // TODO temp refactor

   for (w <- 0 until DECODE_WIDTH)
   {
      rename_stage.io.dec_mask(w) := dec_mask(w)
   }

   rename_stage.io.dec_uops := dec_uops
   ren_insts_can_proceed := rename_stage.io.inst_can_proceed

   var wu_idx = 0
   for (i <- 0 until exe_units.length)
   {
      // Slow Wakeup (uses write-port to register file)
      for (j <- 0 until exe_units(i).num_rf_write_ports)
      {
         rename_stage.io.wb_valids(wu_idx) := exe_units(i).io.resp(j).valid &&
                                              exe_units(i).io.resp(j).bits.uop.ctrl.rf_wen &&       // TODO? is rf_wen redudant?!
                                              !exe_units(i).io.resp(j).bits.uop.bypassable &&
                                              (exe_units(i).io.resp(j).bits.uop.dst_rtype === RT_FIX ||
                                                 exe_units(i).io.resp(j).bits.uop.dst_rtype === RT_FLT)
         rename_stage.io.wb_pdsts(wu_idx)  := exe_units(i).io.resp(j).bits.uop.pdst
         wu_idx += 1
      }

      // Fast Wakeup (uses just-issued uops)
      if (exe_units(i).is_bypassable)
      {
         rename_stage.io.wb_valids(wu_idx) := iss_valids(i) && (iss_uops(i).dst_rtype === RT_FIX || iss_uops(i).dst_rtype === RT_FLT) && (iss_uops(i).bypassable)
         rename_stage.io.wb_pdsts(wu_idx)  := iss_uops(i).pdst
         wu_idx += 1
         assert (!(iss_uops(i).dst_rtype === RT_FLT && iss_uops(i).bypassable), "Bypassing FP is not supported.")
      }
   }
   require (wu_idx == num_wakeup_ports)


   rename_stage.io.com_valids := com_valids
   rename_stage.io.com_uops := com_uops
   rename_stage.io.com_rbk_valids := com_rbk_valids

   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Dispatch Stage ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   // TODO get rid of, let the ROB handle this...?
   val dis_curr_rob_row_idx = UInt(width = ROB_ADDR_SZ)

   for (w <- 0 until DECODE_WIDTH)
   {
      dis_mask(w)         := rename_stage.io.ren_mask(w)
      dis_uops(w)         := rename_stage.io.ren_uops(w)
      dis_uops(w).br_mask := GetNewBrMask(br_unit.brinfo, rename_stage.io.ren_uops(w))

      // note: this assumes uops haven't been shifted - there's a 1:1 match between PC's LSBs and "w" here
      // (thus the LSB of the rob_idx gives part of the PC)
      if (DECODE_WIDTH == 1)
         dis_uops(w).rob_idx := dis_curr_rob_row_idx
      else
         dis_uops(w).rob_idx := Cat(dis_curr_rob_row_idx, UInt(w, log2Up(DECODE_WIDTH)))
   }


   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Issue Stage ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   val issue_unit = Module(new IssueUnit(issue_width, num_wakeup_ports))

   // Input (Dispatch)
   issue_unit.io.dis_mask  := dis_mask
   issue_unit.io.dis_uops  := dis_uops

   // Output (Issue)

   for (w <- 0 until issue_width)
   {
      iss_valids(w) := issue_unit.io.iss_valids(w)
      iss_uops(w)   := issue_unit.io.iss_uops(w)

      issue_unit.io.fu_types(w) := exe_units(w).io.fu_types
   }

   dis_insts_can_proceed := issue_unit.io.dis_inst_can_proceed



   issue_unit.io.brinfo := br_unit.brinfo
   issue_unit.io.flush_pipeline := flush_pipeline

   wu_idx = 0
   for (i <- 0 until exe_units.length)
   {
      // Slow Wakeup (uses write-port to register file)
      for (j <- 0 until exe_units(i).num_rf_write_ports)
      {
         issue_unit.io.wakeup_vals(wu_idx)  := exe_units(i).io.resp(j).valid &&
                                               exe_units(i).io.resp(j).bits.uop.ctrl.rf_wen && // TODO get rid of other rtype checks
                                               !exe_units(i).io.resp(j).bits.uop.bypassable &&
                                               (exe_units(i).io.resp(j).bits.uop.dst_rtype === RT_FIX || exe_units(i).io.resp(j).bits.uop.dst_rtype === RT_FLT)
         issue_unit.io.wakeup_pdsts(wu_idx) := exe_units(i).io.resp(j).bits.uop.pdst
         wu_idx += 1
      }

      // Fast Wakeup (uses just-issued uops)

      if (exe_units(i).is_bypassable)
      {
         issue_unit.io.wakeup_vals(wu_idx)  := iss_valids(i) && (iss_uops(i).dst_rtype === RT_FIX || iss_uops(i).dst_rtype === RT_FLT) && iss_uops(i).ldst_val && (iss_uops(i).bypassable)
         issue_unit.io.wakeup_pdsts(wu_idx) := iss_uops(i).pdst
         wu_idx += 1
      }
   }
   require (wu_idx == num_wakeup_ports)


   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Register Read Stage ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   // Register Read <- Issue (rrd <- iss)


   val register_read = Module(new RegisterRead(issue_width
                                               , num_rf_read_ports
                                               , num_total_bypass_ports
                                               , register_width))

// TODO why the fuck does this change code behavior
//   register_read.io.iss_valids := iss_valids
//   register_read.io.iss_uops := iss_uops
   for (w <- 0 until issue_width)
   {
      register_read.io.iss_valids(w) := iss_valids(w)
      register_read.io.iss_uops(w) := iss_uops(w)
   }

   register_read.io.brinfo := br_unit.brinfo
   register_read.io.kill   := flush_pipeline

   register_read.io.bypass := bypasses

   //-------------------------------------------------------------
   // Privileged Co-processor 0 Register File
   // Note: Normally this would be bad in that I'm writing state before
   // committing, so to get this to work I stall the entire pipeline for
   // MTPCR/MFPCR so I never speculate these instructions.
   // TODO rename k0, k1, as they could use it
   // TODO update the naming here; pcr/mtpcr/etc. is outdated
   // flush pipeline on all writes (because they could goof things up like writing base reg)
   // TODO scratch everything, let's just have the ROB execute this uop?

   require (exe_units(0).uses_pcr_wport)
   val pcr = Module(new rocket.CSRFile())
   pcr.io.host <> io.host
   pcr.io.rw.addr  := ImmGen(exe_units(0).io.resp(0).bits.uop.imm_packed, IS_I)
   val pcr_read_out = pcr.io.rw.rdata

   val pcr_rw_cmd = exe_units(0).io.resp(0).bits.uop.ctrl.pcr_fcn
   pcr.io.rw.cmd   := Mux(exe_units(0).io.resp(0).valid, pcr_rw_cmd, CSR.N)
   val wb_wdata    = exe_units(0).io.resp(0).bits.data
   pcr.io.rw.wdata := Mux(pcr_rw_cmd === CSR.S, pcr.io.rw.rdata | wb_wdata,
                      Mux(pcr_rw_cmd === CSR.C, pcr.io.rw.rdata & ~wb_wdata,
                                                 wb_wdata))

   // TODO is there anything else that's going on spuriously?
   assert (!(pcr_rw_cmd != CSR.N && !exe_units(0).io.resp(0).valid), "PCR is being written to spuriously.")

   // Extra I/O
   pcr_status       := pcr.io.status
   pcr.io.pc        := flush_pc
   pcr.io.exception := com_exception
   pcr.io.retire    := PopCount(com_valids.toBits)
   pcr.io.cause     := com_exc_cause
   pcr.io.sret      := com_sret
   pcr_exc_target   := pcr.io.evec
   pcr.io.badvaddr_wen := Bool(false); require (params(UseVM) == false) // TODO VM virtual memory

   // reading requires serializing the entire pipeline
   pcr.io.fcsr_flags.valid := com_fflags_val
   pcr.io.fcsr_flags.bits := com_fflags

   exe_units.map(_.io.fcsr_rm := pcr.io.fcsr_rm)

   // --------------------------------------
   // Register File

   val regfile = Module(new RegisterFile(PHYS_REG_COUNT
                                        , num_rf_read_ports
                                        , num_rf_write_ports
                                        , register_width
                                        , ENABLE_REGFILE_BYPASSING))


   // --------------------------------------
   // Read Ports

   regfile.io.read_ports <> register_read.io.rf_read_ports


   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Execute Stage ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   var idx = 0
   for (w <- 0 until exe_units.length)
   {
      exe_units(w).io.req <> register_read.io.exe_reqs(w)
      exe_units(w).io.brinfo := br_unit.brinfo
      exe_units(w).io.com_handling_exc := com_handling_exc // TODO get rid of this?


      if (exe_units(w).is_bypassable)
      {
         for (i <- 0 until exe_units(w).get_num_bypass_ports)
         {
            println("  Hooking up bypasses for idx = " + idx + ", exe_unit #" + w)
            bypasses.valid(idx) := exe_units(w).io.bypass.valid(i)
            bypasses.uop(idx)   := exe_units(w).io.bypass.uop(i)
            bypasses.data(idx)  := exe_units(w).io.bypass.data(i)
            idx = idx + 1
         }
      }
   }
   require (idx == num_total_bypass_ports)


   var br_cnt = 0
   var brunit_idx = 0
   for (w <- 0 until exe_units.length)
   {
      if (exe_units(w).has_branch_unit)
      {
         println("  Hooking up Branch Unit for exe_unit #" + w)
         br_unit <> exe_units(w).io.br_unit
         br_cnt = br_cnt + 1
         brunit_idx = w
      }
   }
   require (br_cnt == 1)


   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Memory Stage ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   val com_st_mask = Vec.fill(DECODE_WIDTH) {Bool()}
   val com_ld_mask = Vec.fill(DECODE_WIDTH) {Bool()}


   // enqueue basic store info in Decode
   lsu_io.dec_uops := dec_uops


   for (w <- 0 until DECODE_WIDTH)
   {
      lsu_io.dec_st_vals(w) := dec_mask(w) && rename_stage.io.inst_can_proceed(w) && !com_exception && dec_uops(w).is_store
      lsu_io.dec_ld_vals(w) := dec_mask(w) && rename_stage.io.inst_can_proceed(w) && !com_exception && dec_uops(w).is_load

      lsu_io.dec_uops(w).rob_idx := dis_uops(w).rob_idx // for debug purposes (comit logging)
   }

   lsu_io.commit_store_mask := com_st_mask
   lsu_io.commit_load_mask  := com_ld_mask

   lsu_io.exception         := flush_pipeline //com_exception, com.exception comes too early, will fight against a branch that resolves same cycle as an exception
   lsu_io.lsu_misspec       := lsu_misspec

   // Handle Branch Mispeculations
   lsu_io.brinfo      := br_unit.brinfo
   io.dmem.brinfo     := br_unit.brinfo


   laq_full    := lsu_io.laq_full
   stq_full    := lsu_io.stq_full
   new_ldq_idx := lsu_io.new_ldq_idx
   new_stq_idx := lsu_io.new_stq_idx

   io.dmem.flush_pipe := flush_pipeline


   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Writeback Stage ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   val tsc_reg = Reg(init = UInt(0, xprLen))

   var cnt = 0
   for (i <- 0 until exe_units.length)
   {
      for (j <- 0 until exe_units(i).num_rf_write_ports)
      {
         if (exe_units(i).data_width > 64)
         {
            assert (!(exe_units(i).io.resp(j).valid &&
                      exe_units(i).io.resp(j).bits.uop.ctrl.rf_wen &&
                      exe_units(i).io.resp(j).bits.uop.dst_rtype === RT_FIX &&
                      exe_units(i).io.resp(j).bits.data(64).toBool),
                      "the 65th bit was set on a fixed point write-back to the regfile.")
         }



         if (exe_units(i).uses_pcr_wport && (j == 0))
         {
            regfile.io.write_ports(cnt).wen  := exe_units(i).io.resp(j).valid &&
                                                exe_units(i).io.resp(j).bits.uop.ctrl.rf_wen && // TODO get rid of other checks
                                                (exe_units(i).io.resp(j).bits.uop.dst_rtype === RT_FIX || exe_units(i).io.resp(j).bits.uop.dst_rtype === RT_FLT)
            regfile.io.write_ports(cnt).addr := exe_units(i).io.resp(j).bits.uop.pdst
            regfile.io.write_ports(cnt).data := Mux(exe_units(i).io.resp(j).bits.uop.ctrl.pcr_fcn != rocket.CSR.N, pcr_read_out,
                                                                                          exe_units(i).io.resp(j).bits.data)
         }
         else
         {
            regfile.io.write_ports(cnt).wen  := exe_units(i).io.resp(j).valid &&
                                                exe_units(i).io.resp(j).bits.uop.ctrl.rf_wen && // TODO get rid of other checks
                                                (exe_units(i).io.resp(j).bits.uop.dst_rtype === RT_FIX || exe_units(i).io.resp(j).bits.uop.dst_rtype === RT_FLT)
            regfile.io.write_ports(cnt).addr := exe_units(i).io.resp(j).bits.uop.pdst
            regfile.io.write_ports(cnt).data := exe_units(i).io.resp(j).bits.data
         }
         cnt += 1
      }
   }


   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Commit Stage ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   val rob  = Module(new Rob(DECODE_WIDTH, NUM_ROB_ENTRIES, num_slow_wakeup_ports))

      // Dispatch
      rob_rdy := rob.io.ready

      rob.io.dis_uops := dis_uops
      rob.io.dis_mask := dis_mask

      dis_curr_rob_row_idx  := rob.io.curr_rob_tail

      rob_empty := rob.io.empty

      // Writeback
      cnt = 0
      for (w <- 0 until exe_units.length)
      {
         for (j <- 0 until exe_units(w).num_rf_write_ports)
         {
            val wb_uop = exe_units(w).io.resp(j).bits.uop
            rob.io.wb_resps(cnt).valid := exe_units(w).io.resp(j).valid && !(wb_uop.is_store && !wb_uop.is_amo)
            rob.io.wb_resps(cnt).bits <> exe_units(w).io.resp(j).bits

            // for commit logging...
            rob.io.debug_wb_valids(cnt) := exe_units(w).io.resp(j).valid &&
                                           wb_uop.ctrl.rf_wen &&
                                           (wb_uop.dst_rtype === RT_FIX || wb_uop.dst_rtype === RT_FLT)

            val data = exe_units(w).io.resp(j).bits.data
            val unrec_s = hardfloat.recodedFloatNToFloatN(data, 23, 9)
            val unrec_d = hardfloat.recodedFloatNToFloatN(data, 52, 12)
            val unrec_out = Mux(wb_uop.fp_single, Cat(Fill(32, unrec_s(31)), unrec_s), unrec_d)
//            val is_negnan_s = UInt(0xffffffff) === unrec_s && wb_uop.fp_single

            if (exe_units(w).uses_pcr_wport && (j == 0))
            {
               rob.io.debug_wb_wdata(cnt) := Mux(wb_uop.ctrl.pcr_fcn != rocket.CSR.N, pcr_read_out,
//                                             Mux(wb_uop.fp_val && wb_uop.dst_rtype === RT_FLT && is_negnan_s, UInt(0xffffffff),
                                             Mux(wb_uop.fp_val && wb_uop.dst_rtype === RT_FLT, unrec_out,
                                                                                               data))
            }
            else
            {
               rob.io.debug_wb_wdata(cnt) := Mux(exe_units(w).io.resp(j).bits.uop.fp_val, unrec_out, data)
            }
            cnt += 1
         }

         if (exe_units(w).is_mem_unit)
         {
            // memory exceptions
            rob.io.mem_xcpt_val := exe_units(w).io.ma_xcpt_val
            rob.io.mem_xcpt_uop := exe_units(w).io.ma_xcpt_uop
            rob.io.mem_xcpt     := exe_units(w).io.ma_xcpt

            rob.io.ldo_xcpt_val := exe_units(w).io.lsu_io.ldo_xcpt_val
            rob.io.ldo_xcpt_uop := exe_units(w).io.lsu_io.ldo_xcpt_uop
         }
      }

      // branch resolution
//      rob.io.br_unit <> br_unit // TODO figure out why this errors out
      rob.io.br_unit.take_pc := br_unit.take_pc
      rob.io.br_unit.target := br_unit.target
      rob.io.br_unit.taken := br_unit.taken
      rob.io.br_unit.brinfo := br_unit.brinfo
      rob.io.br_unit.btb_update_valid := br_unit.btb_update_valid
      rob.io.br_unit.btb_update := br_unit.btb_update
      rob.io.br_unit.debug_btb_pred := br_unit.debug_btb_pred

      // branch unit fetches PC from ROB cycle earlier than needed (for critical path reasons)

      // branch unit requests PCs from ROB during register read
      rob.io.get_pc.rob_idx := iss_uops(brunit_idx).rob_idx
      exe_units(brunit_idx).io.get_rob_pc.curr_pc  := Reg(next=rob.io.get_pc.curr_pc)
      exe_units(brunit_idx).io.get_rob_pc.next_val := Reg(next=rob.io.get_pc.next_val)
      exe_units(brunit_idx).io.get_rob_pc.next_pc  := Reg(next=rob.io.get_pc.next_pc)

      // LSU <> ROB
      lsu_misspec := rob.io.lsu_misspec
      rob.io.lsu_clr_bsy_valid   := lsu_io.lsu_clr_bsy_valid
      rob.io.lsu_clr_bsy_rob_idx := lsu_io.lsu_clr_bsy_rob_idx

      // Commit (ROB outputs)
      com_valids       := rob.io.com_valids
      com_uops         := rob.io.com_uops
      com_fflags_val   := rob.io.com_fflags_val
      com_fflags       := rob.io.com_fflags

      com_st_mask      := rob.io.com_st_mask
      com_ld_mask      := rob.io.com_ld_mask

      com_exception    := rob.io.com_exception    // on for only a single cycle (to PCR)
      com_exc_cause    := rob.io.com_exc_cause
      com_handling_exc := rob.io.com_handling_exc // on for duration of roll-back
      com_rbk_valids   := rob.io.com_rbk_valids


   // throw assertion failure if a store or load have a misaligned or vm fault
   // as neither are supported as of yet.
   require (params(UseVM) == false)
   assert (!(com_exception &&
             (com_exc_cause === UInt(rocket.Causes.misaligned_load) ||
              com_exc_cause === UInt(rocket.Causes.fault_load) ||
              com_exc_cause === UInt(rocket.Causes.misaligned_store) ||
              com_exc_cause === UInt(rocket.Causes.fault_store))),
             "An unsupported memory exception occurred (misaligned st/ld or faulting st/ld).")

   //-------------------------------------------------------------
   // **** Flush Pipeline ****
   //-------------------------------------------------------------
   // flush on exceptions, miniexeptions, and after some special instructions

   flush_take_pc  := rob.io.flush_take_pc
   flush_pipeline := rob.io.flush_pipeline
   flush_pc       := rob.io.flush_pc

   for (w <- 0 until exe_units.length)
   {
      exe_units(w).io.req.bits.kill := flush_pipeline
   }

   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Outputs to the External World ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

//    TODO have a way to detect this
//   val saw_rdcycle = Bool()
//   debug(saw_rdcycle)
//   saw_rdcycle := Range(0,COMMIT_WIDTH).map(w => com_valids(w) && com_uops(w).uopc === uopRDC).reduce(_|_)

   for (w <- 0 until DECODE_WIDTH)
   {
      debug(com_uops(w).inst)
      debug(com_valids(w))
   }
   debug(br_unit.brinfo.valid)
   debug(br_unit.brinfo.mispredict)

   // detect pipeline freezes
   // if building an actual chip, then flush & restart pipeline...
   val idle_cycles = Reg(init = UInt(0, 14))
   when (com_valids.toBits.orR)
   {
//      idle_cycles := UInt(0) ^ (rob.io.com_valids.toBits ^ rob.io.com_valids.toBits)
//      idle_cycles := UInt(0) ^ (rob.io.com_valids(0) ^ rob.io.com_valids(0)) ^ (rob.io.com_valids(1) ^ rob.io.com_valids(1)) // weird chisel bug we're trying to solve
//      idle_cycles := UInt(0) ^ rob.io.com_valids.map(_^_).foldLeft(_^_)
      idle_cycles := UInt(0)
   }
   .otherwise
   {
      idle_cycles := idle_cycles + UInt(1)
   }

   when (idle_cycles === UInt(1 << 12))
   {
      throw_idle_error := Bool(true)
   }

   debug(throw_idle_error)
   debug(lsu_misspec)

   assert (!(throw_idle_error), "Pipeline has hung.")
   // TODO XXX stuff an error code down the csr_tohost to end the run


   //-------------------------------------------------------------
   // Counters

   val laq_full_count = Reg(init = UInt(0, xprLen))
   when (laq_full) { laq_full_count := laq_full_count + UInt(1) }
   debug(laq_full_count)

   val stq_full_count = Reg(init = UInt(0, xprLen))
   when (stq_full) { stq_full_count := stq_full_count + UInt(1) }
   debug(stq_full_count)

   val stalls = Reg(init = UInt(0, xprLen))
   when (!dec_rdy) { stalls := stalls + UInt(1) }
   debug(stalls)

   val lsu_misspec_count = Reg(init = UInt(0, xprLen))
   when (lsu_misspec) { lsu_misspec_count := lsu_misspec_count + UInt(1) }
   debug(lsu_misspec_count)



   // Time Stamp Counter & Retired Instruction Counter
   // (only used for printf and vcd dumps - the actual counters are in the CSRFile)
//   val tsc_reg = Reg(init = UInt(0, xprLen))
   val irt_reg = Reg(init = UInt(0, xprLen))
   val irt_ei_reg = Reg(init = UInt(0, xprLen))
   tsc_reg := tsc_reg + UInt(1)
   irt_reg := irt_reg + PopCount(com_valids.toBits)
   when (pcr.io.status.ei) { irt_ei_reg := irt_ei_reg + PopCount(com_valids.toBits) }
   debug(tsc_reg)
   debug(irt_reg)
   debug(irt_ei_reg)

   // UARCH Counters
   // these take up a significant amount of area, so don't enable them lightly
   if (params(EnableUarchCounters))
   {
      println("\n   UArch Counters Enabled\n")
      pcr.io.uarch_counters(0)  := br_unit.brinfo.valid
      pcr.io.uarch_counters(1)  := br_unit.brinfo.mispredict
   //   pcr.io.uarch_counters(2)  := com_exception
      pcr.io.uarch_counters(2)  := !rob_rdy
      pcr.io.uarch_counters(3)  := laq_full
      pcr.io.uarch_counters(4)  := stq_full
      pcr.io.uarch_counters(5)  := branch_mask_full.reduce(_|_)
      pcr.io.uarch_counters(6)  := io.counters.ic_miss
      pcr.io.uarch_counters(7)  := io.counters.dc_miss
      pcr.io.uarch_counters(8)  := lsu_io.counters.ld_valid
      pcr.io.uarch_counters(9)  := lsu_io.counters.ld_forwarded
      pcr.io.uarch_counters(10) := lsu_io.counters.ld_sleep
      pcr.io.uarch_counters(11) := lsu_io.counters.ld_killed
      pcr.io.uarch_counters(12) := lsu_io.counters.ld_order_fail
      pcr.io.uarch_counters(13) := PopCount((Range(0,COMMIT_WIDTH)).map{w => com_valids(w) && com_uops(w).is_br_or_jmp})
      pcr.io.uarch_counters(14) := PopCount((Range(0,COMMIT_WIDTH)).map{w => com_valids(w) && com_uops(w).is_store})
      pcr.io.uarch_counters(15) := PopCount((Range(0,COMMIT_WIDTH)).map{w => com_valids(w) && com_uops(w).is_load})
   }
   else
   {
      println("\n   UArch Counters Disabled\n")
      pcr.io.uarch_counters.foreach(_ := Bool(false))
   }

   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Handle Cycle-by-Cycle Printouts ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   if (DEBUG_PRINTF)
   {
      println("\n Chisel Printout Enabled\n")

      // color codes for output files
      // if you use VIM to view, you'll need the AnsiEsc plugin.
      // 1 is bold, 2 is background, 4 is k
      val blk   = if (DEBUG_ENABLE_COLOR) "\033[1;30m" else " "
      val red   = if (DEBUG_ENABLE_COLOR) "\033[1;31m" else " "
      val grn   = if (DEBUG_ENABLE_COLOR) "\033[1;32m" else " "
      val ylw   = if (DEBUG_ENABLE_COLOR) "\033[1;33m" else " "
      val blu   = if (DEBUG_ENABLE_COLOR) "\033[1;34m" else " "
      val mgt   = if (DEBUG_ENABLE_COLOR) "\033[1;35m" else " "
      val cyn   = if (DEBUG_ENABLE_COLOR) "\033[1;36m" else " "
      val wht   = if (DEBUG_ENABLE_COLOR) "\033[1;37m" else " "
      val end   = if (DEBUG_ENABLE_COLOR) "\033[0m"    else ""

      val b_blk = if (DEBUG_ENABLE_COLOR) "\033[2;30m" else " "
      val b_red = if (DEBUG_ENABLE_COLOR) "\033[2;31m" else " "
      val b_grn = if (DEBUG_ENABLE_COLOR) "\033[2;32m" else " "
      val b_ylw = if (DEBUG_ENABLE_COLOR) "\033[2;33m" else " "
      val b_blu = if (DEBUG_ENABLE_COLOR) "\033[2;34m" else " "
      val b_mgt = if (DEBUG_ENABLE_COLOR) "\033[2;35m" else " "
      val b_cyn = if (DEBUG_ENABLE_COLOR) "\033[2;36m" else " "
      val b_wht = if (DEBUG_ENABLE_COLOR) "\033[2;37m" else " "

      val u_blk = if (DEBUG_ENABLE_COLOR) "\033[4;30m" else " "
      val u_red = if (DEBUG_ENABLE_COLOR) "\033[4;31m" else " "
      val u_grn = if (DEBUG_ENABLE_COLOR) "\033[4;32m" else " "
      val u_ylw = if (DEBUG_ENABLE_COLOR) "\033[4;33m" else " "
      val u_blu = if (DEBUG_ENABLE_COLOR) "\033[4;34m" else " "
      val u_mgt = if (DEBUG_ENABLE_COLOR) "\033[4;35m" else " "
      val u_cyn = if (DEBUG_ENABLE_COLOR) "\033[4;36m" else " "
      val u_wht = if (DEBUG_ENABLE_COLOR) "\033[4;37m" else " "

      var white_space = 47  - NUM_LSU_ENTRIES- INTEGER_ISSUE_SLOT_COUNT - (NUM_ROB_ENTRIES/COMMIT_WIDTH)

      def InstsStr(insts: Bits, width: Int) =
      {
         var string = sprintf("")
         for (w <- 0 until width)
            string = sprintf("%s(DASM(%x))", string, insts(((w+1)*32)-1,w*32))
         string
      }

      // Front-end
      printf("--- Cyc=%d , ----------------- Ret: %d ---------------------------------- User Retired: %d\n  BrPred1:        (IF1_PC= n/a - Predict:n/a) ------ PC: [%s%s%s-%s for br_id: %d, %s %s next: 0x%x ifst:%d]\nI$ Response: (%s) IF2_PC= 0x%x (mask:0x%x) \033[1;35m%s\033[0m  ----BrPred2:(%s,%s,%s,%s,%s %d) [btbtarg: 0x%x predtarg: 0x%x] jkilmsk:0x%x ->(0x%x)\n"
         , tsc_reg
         , irt_reg & UInt(0xffffff)
         , irt_ei_reg & UInt(0xffffff)
      // Fetch Stage 1
         , Mux(br_unit.brinfo.valid, Str("V"), Str("-"))
         , Mux(br_unit.taken, Str("T"), Str("-"))
         , Mux(br_unit.debug_btb_pred, Str("B"), Str("_"))
         , Mux(br_unit.brinfo.mispredict, Str(b_mgt + "MISPREDICT" + end), Str(grn + "          " + end))
         , br_unit.brinfo.tag
         , Mux(take_pc, Str("TAKE_PC"), Str(" "))
         , Mux(com_sret, Str("SRET"),
           Mux(flush_take_pc, Str("FLSH"),
           Mux(br_unit.take_pc, Str("BRU "),
           Mux(bp2_take_pc && !if_stalled, Str("BP2"),
           Mux(bp2_take_pc, Str("J-s"),
                              Str(" "))))))
         , if_pc_next
         , if_stalled
      // Fetch Stage 2
         , Mux(io.imem.resp.valid && !fetchbuffer_kill, Str(mgt + "V" + end), Str(grn + "-" + end))
         , io.imem.resp.bits.pc
         , io.imem.resp.bits.mask
         , InstsStr(io.imem.resp.bits.data.toBits, FETCH_WIDTH)
         , Mux(bp2_val, Str("V"), Str("-"))
         , Mux(io.imem.btb_resp.valid, Str("H"), Str("-"))
//         , Mux(bpd_br_val, Str("B"), Str("-"))
         , Mux(io.imem.btb_resp.bits.taken, Str("T"), Str("-"))
//         , Mux(bp2_prediction.taken, Str("T"), Str("n"))
         , Mux(bp2_jal_val, Str("J"), Str("-"))
         , Mux(btb_predicted_our_jal, Str("C"), Str("-"))
//         , bpd_br_idx
         , bp2_jmp_idx
         , io.imem.btb_resp.bits.target(19,0)
         , bp2_pred_target(19,0)
         , jal_kill_mask
         , fetch_bundle.mask
         )

      // Back-end
      for (w <- 0 until DECODE_WIDTH)
      {
         if (w == 0)
         {
            printf("Dec:  ([0x%x]                        ", rename_stage.io.ren_uops(w).pc(19,0))
         }
         else
         {
            printf("[0x%x]                        ", rename_stage.io.ren_uops(w).pc(19,0))
         }
      }

      printf(") State: (%s:%s %s %s \033[1;31m%s\033[0m %s %s) BMsk:%x %s %s %s\n"
         , Mux(rob.io.debug.state === UInt(0), Str("RESET"),
           Mux(rob.io.debug.state === UInt(1), Str("NORMAL"),
           Mux(rob.io.debug.state === UInt(2), Str("ROLLBK"),
           Mux(rob.io.debug.state === UInt(3), Str("WAIT_E"),
                                               Str(" ")))))
         , Mux(rob_rdy,Str("_"), Str("!ROB_RDY"))
         , Mux(laq_full, Str("LAQ_FULL"), Str("_"))
         , Mux(stq_full, Str("STQ_FULL"), Str("_"))
         , Mux(flush_pipeline, Str("FLUSH_PIPELINE"), Str(" "))
         , Mux(branch_mask_full.reduce(_|_), Str("BR_MSK_FULL"), Str(" "))
         , Mux(io.dmem.req.ready, Str("D$_Rdy"), Str("D$_BSY"))
         , dec_brmask_logic.io.debug.branch_mask
         , Mux(pcr.io.status.s, Str("SUPERVISOR"), Str("USERMODE"))
         , Mux(pcr.io.status.ei, Str("EI"), Str("-"))
         , Mux(pcr.io.status.pei, Str("PEI"), Str("-"))
         )


      for (w <- 0 until DECODE_WIDTH)
      {
         printf("(%s%s) " + red + "DASM(%x)" + end + " |  "
            , Mux(fetched_inst_valid && dec_fbundle(w).valid && !dec_finished_mask(w), Str("V"), Str("-"))
            , Mux(dec_mask(w), Str("V"), Str("-"))
            , dec_fbundle(w).inst
            )
      }

      printf(")\n   fin(%x)", dec_finished_mask)

      for (w <- 0 until DECODE_WIDTH)
      {
         printf("  [ISA:%d,%d,%d,%d] [Phs:%d(%s)%d[%s](%s)%d[%s](%s)%d[%s](%s)] "
            , dec_uops(w).ldst
            , dec_uops(w).lrs1
            , dec_uops(w).lrs2
            , dec_uops(w).lrs3
            , dis_uops(w).pdst
            , Mux(dec_uops(w).dst_rtype   === RT_FIX, Str("X")
              , Mux(dec_uops(w).dst_rtype === RT_X  , Str("-")
              , Mux(dec_uops(w).dst_rtype === RT_FLT, Str("f")
              , Mux(dec_uops(w).dst_rtype === RT_PAS, Str("C"), Str("?")))))
            , dis_uops(w).pop1
            , Mux(rename_stage.io.ren_uops(w).prs1_busy, Str("B"), Str("R"))
            , Mux(dec_uops(w).lrs1_rtype    === RT_FIX, Str("X")
               , Mux(dec_uops(w).lrs1_rtype === RT_X  , Str("-")
               , Mux(dec_uops(w).lrs1_rtype === RT_FLT, Str("f")
               , Mux(dec_uops(w).lrs1_rtype === RT_PAS, Str("C"), Str("?")))))
            , dis_uops(w).pop2
            , Mux(rename_stage.io.ren_uops(w).prs2_busy, Str("B"), Str("R"))
            , Mux(dec_uops(w).lrs2_rtype    === RT_FIX, Str("X")
               , Mux(dec_uops(w).lrs2_rtype === RT_X  , Str("-")
               , Mux(dec_uops(w).lrs2_rtype === RT_FLT, Str("f")
               , Mux(dec_uops(w).lrs2_rtype === RT_PAS, Str("C"), Str("?")))))
            , dis_uops(w).pop3
            , Mux(rename_stage.io.ren_uops(w).prs3_busy, Str("B"), Str("R"))
            , Mux(dec_uops(w).frs3_en, Str("f"), Str("-"))
            )
      }



      printf("Exct(%s%d) Commit(%x) fl: 0x%x (%d) is: 0x%x (%d)\n"
         , Mux(com_exception, Str("E"), Str("-"))
         , com_exc_cause
         , com_valids.toBits
         , rename_stage.io.debug.freelist
         , PopCount(rename_stage.io.debug.freelist)
         , rename_stage.io.debug.isprlist
         , PopCount(rename_stage.io.debug.isprlist)
         )

      // branch unit
      printf("                          Branch Unit: %s,%s,%d PC=0x%x, %d Targ=0x%x NPC=%d,0x%x %d%d\n"
         , Mux(br_unit.brinfo.valid,Str("V"), Str(" "))
         , Mux(br_unit.brinfo.mispredict, Str("M"), Str(" "))
         , br_unit.taken
         , br_unit.btb_update.br_pc(19,0)
         , br_unit.btb_update_valid
         , br_unit.btb_update.target(19,0)
         , exe_units(brunit_idx).io.get_rob_pc.next_val
         , exe_units(brunit_idx).io.get_rob_pc.next_pc(19,0)
         , br_unit.btb_update.isJump
         , br_unit.btb_update.isReturn
      )
      // Issue Window
      for (i <- 0 until INTEGER_ISSUE_SLOT_COUNT)
      {
         printf("  integer_issue_slot[%d](%s)(Req:%s):wen=%s P:(%s,%s,%s) OP:(%d,%d,%d) PDST:%d %s [%s[DASM(%x)]"+end+" 0x%x: %d] ri:%d bm=%d imm=0x%x\n"
            , UInt(i, log2Up(INTEGER_ISSUE_SLOT_COUNT))
            , Mux(issue_unit.io.debug.slot(i).valid, Str("V"), Str("-"))
            , Mux(issue_unit.io.debug.slot(i).request, Str(u_red + "R" + end), Str(grn + "-" + end))
            , Mux(issue_unit.io.debug.slot(i).in_wen, Str(u_wht + "W" + end),  Str(grn + " " + end))
            , Mux(issue_unit.io.debug.slot(i).p1, Str("!"), Str(" "))
            , Mux(issue_unit.io.debug.slot(i).p2, Str("!"), Str(" "))
            , Mux(issue_unit.io.debug.slot(i).p3, Str("!"), Str(" "))
            , issue_unit.io.debug.slot(i).uop.pop1
            , issue_unit.io.debug.slot(i).uop.pop2
            , issue_unit.io.debug.slot(i).uop.pop3
            , issue_unit.io.debug.slot(i).uop.pdst
            , Mux(issue_unit.io.debug.slot(i).uop.dst_rtype === RT_FIX, Str("X"),
              Mux(issue_unit.io.debug.slot(i).uop.dst_rtype === RT_X, Str("-"),
              Mux(issue_unit.io.debug.slot(i).uop.dst_rtype === RT_FLT, Str("f"),
              Mux(issue_unit.io.debug.slot(i).uop.dst_rtype === RT_PAS, Str("C"), Str("?")))))
            , Mux(issue_unit.io.debug.slot(i).valid, Str(b_wht), Str(grn))
            , issue_unit.io.debug.slot(i).uop.inst
            , issue_unit.io.debug.slot(i).uop.pc(31,0)
            , issue_unit.io.debug.slot(i).uop.uopc  // getUopStr
            , issue_unit.io.debug.slot(i).uop.rob_idx
            , issue_unit.io.debug.slot(i).uop.br_mask
            , issue_unit.io.debug.slot(i).uop.imm_packed
            )
      }

      //ROB
      var r_idx = 0
      for (i <- 0 until (NUM_ROB_ENTRIES/COMMIT_WIDTH))
      {
//            rob[ 0]           (  )(  ) 0x00002000 [ -                       ][unknown                  ]    ,   (d:X p 1, bm:0 - sdt: 0) (d:- p 3, bm:f - sdt:60)
//            rob[ 1]           (  )(B ) 0xc71cb68e [flw     fa3, -961(s11)   ][ -                       ] E31,   (d:- p22, bm:e T sdt:57) (d:- p 0, bm:0 - sdt: 0)
//            rob[ 2] HEAD ---> (vv)( b) 0x00002008 [lui     ra, 0x2          ][addi    ra, ra, 704      ]    ,   (d:x p 2, bm:1 - sdt: 0) (d:x p 3, bm:1 - sdt: 2)
//            rob[ 3]           (vv)(bb) 0x00002010 [lw      s1, 0(ra)        ][lui     t3, 0xff0        ]    ,   (d:x p 4, bm:0 - sdt: 0) (d:x p 5, bm:0 - sdt: 0)
//            rob[ 4]      TL-> (v )(b ) 0x00002018 [addiw   t3, t3, 255      ][li      t2, 2            ]    ,   (d:x p 6, bm:0 - sdt: 5) (d:x p 7, bm:0 - sdt: 0)

         val row = if (COMMIT_WIDTH == 1) r_idx else (r_idx >> log2Up(COMMIT_WIDTH))
         val r_head = rob.io.debug.rob_head
         val r_tail = rob.io.curr_rob_tail

         printf("    rob[%d] %s ("
            , UInt(row, ROB_ADDR_SZ)
            , Mux(r_head === UInt(row) && r_tail === UInt(row), Str("HEAD,TL->"),
              Mux(r_head === UInt(row), Str("HEAD --->"),
              Mux(r_tail === UInt(row), Str("     TL->"),
                                        Str(" "))))
            )

         if (COMMIT_WIDTH == 1)
         {
            printf("(%s)(%s) 0x%x [DASM(%x)] %s%d "
               , Mux(rob.io.debug.entry(r_idx+0).valid, Str(b_cyn + "V" + end), Str(grn + " " + end))
               , Mux(rob.io.debug.entry(r_idx+0).busy, Str(b_ylw + "B" + end),  Str(grn + " " + end))
               , rob.io.debug.entry(r_idx+0).uop.pc(31,0)
               , rob.io.debug.entry(r_idx+0).uop.inst
               , Mux(rob.io.debug.entry(r_idx+0).exception, Str("E"), Str("-"))
               , rob.io.debug.entry(r_idx+0).eflags(7,0)
               )
         }
         else if (COMMIT_WIDTH == 2)
         {
            val row_is_val = rob.io.debug.entry(r_idx+0).valid || rob.io.debug.entry(r_idx+1).valid
            printf("(%s%s)(%s%s) 0x%x %x [%sDASM(%x)][DASM(%x)" + end + "] %s%d,%s%d "
               , Mux(rob.io.debug.entry(r_idx+0).valid, Str(b_cyn + "V" + end), Str(grn + " " + end))
               , Mux(rob.io.debug.entry(r_idx+1).valid, Str(b_cyn + "V" + end), Str(grn + " " + end))
               , Mux(rob.io.debug.entry(r_idx+0).busy,  Str(b_ylw + "B" + end), Str(grn + " " + end))
               , Mux(rob.io.debug.entry(r_idx+1).busy,  Str(b_ylw + "B" + end), Str(grn + " " + end))
               , rob.io.debug.entry(r_idx+0).uop.pc(31,0)
               , rob.io.debug.entry(r_idx+1).uop.pc(15,0)
               , Mux(r_head === UInt(row) && row_is_val, Str(b_red),
                 Mux(row_is_val                        , Str(b_cyn),
                                                         Str(grn)))
               , rob.io.debug.entry(r_idx+0).uop.inst
               , rob.io.debug.entry(r_idx+1).uop.inst
               , Mux(rob.io.debug.entry(r_idx+0).exception, Str("E"), Str("-"))
               , rob.io.debug.entry(r_idx+0).eflags(7,0)
               , Mux(rob.io.debug.entry(r_idx+1).exception, Str("E"), Str("-"))
               , rob.io.debug.entry(r_idx+1).eflags(7,0)
               )
         }
         else
         {
            println("  BOOM's Chisel printf does not support commit_width >= " + COMMIT_WIDTH)
         }

         var temp_idx = r_idx
         for (w <- 0 until COMMIT_WIDTH)
         {
            printf("(d:%s p%d, bm:%x %s sdt:%d) "
               , Mux(rob.io.debug.entry(temp_idx).uop.dst_rtype === RT_FIX, Str("X"),
                 Mux(rob.io.debug.entry(temp_idx).uop.dst_rtype === RT_PAS, Str("C"),
                 Mux(rob.io.debug.entry(temp_idx).uop.dst_rtype === RT_FLT, Str("f"),
                 Mux(rob.io.debug.entry(temp_idx).uop.dst_rtype === RT_X, Str("-"), Str("?")))))
               , rob.io.debug.entry    (temp_idx).uop.pdst
               , rob.io.debug.entry    (temp_idx).uop.br_mask
               , Mux(rob.io.debug.entry(temp_idx).uop.br_was_taken, Str("T"), Str("-"))
               , rob.io.debug.entry    (temp_idx).uop.stale_pdst
            )
            temp_idx = temp_idx + 1
         }

         r_idx = r_idx + COMMIT_WIDTH

         printf("\n")
      }

      // Load/Store Unit

      printf("  Mem[%s,%s:%d,%s,%s %s %s]\n"
            , Mux(io.dmem.debug.memreq, Str("MREQ"), Str(" "))
            , Mux(io.dmem.debug.memresp, Str("MRESP"), Str(" "))
            , io.dmem.debug.cache_resp_idx
            , Mux(io.dmem.debug.req_kill, Str("KILL"), Str(" "))
            , Mux(io.dmem.debug.nack, Str("NACK"), Str(" "))
            , Mux(io.dmem.debug.cache_nack, Str("CN"), Str(" "))
            , Mux(lsu_io.forward_val, Str("FWD"), Str(" "))
            )
      for (i <- 0 until NUM_LSU_ENTRIES)
      {
         printf("         ldq[%d]=(%s%s%s%s%s%s%d) st_dep(%d,m=%x) 0x%x %s %s   saq[%d]=(%s%s%s%s%s%s) b:%x 0x%x -> 0x%x %s %s %s"
            , UInt(i, MEM_ADDR_SZ)
            , Mux(lsu_io.debug.entry(i).laq_allocated, Str("V"), Str("-"))
            , Mux(lsu_io.debug.entry(i).laq_addr_val, Str("A"), Str("-"))
            , Mux(lsu_io.debug.entry(i).laq_executed, Str("E"), Str("-"))
            , Mux(lsu_io.debug.entry(i).laq_succeeded, Str("S"), Str("-"))
            , Mux(lsu_io.debug.entry(i).laq_failure, Str("F"), Str("_"))
            , Mux(lsu_io.debug.entry(i).laq_forwarded_std_val, Str("X"), Str("_"))
            , lsu_io.debug.entry(i).laq_forwarded_stq_idx
            , lsu_io.debug.entry(i).laq_yng_st_idx
            , lsu_io.debug.entry(i).laq_st_dep_mask
            , lsu_io.debug.entry(i).laq_addr(19,0)

            , Mux(lsu_io.debug.laq_head === UInt(i), Str("<- H "), Str(" "))
            , Mux(lsu_io.debug.laq_tail=== UInt(i), Str("T "), Str(" "))

            , UInt(i, MEM_ADDR_SZ)
            , Mux(lsu_io.debug.entry(i).stq_entry_val, Str("V"), Str("-"))
            , Mux(lsu_io.debug.entry(i).saq_val, Str("A"), Str("-"))
            , Mux(lsu_io.debug.entry(i).sdq_val, Str("D"), Str("-"))
            , Mux(lsu_io.debug.entry(i).stq_committed, Str("C"), Str("-"))
            , Mux(lsu_io.debug.entry(i).stq_executed, Str("E"), Str("-"))
            , Mux(lsu_io.debug.entry(i).stq_succeeded, Str("S"), Str("-"))
            , lsu_io.debug.entry(i).stq_uop.br_mask
            , lsu_io.debug.entry(i).saq_addr(19,0)
            , lsu_io.debug.entry(i).sdq_data

            , Mux(lsu_io.debug.stq_head === UInt(i), Str("<- H "), Str(" "))
            , Mux(lsu_io.debug.stq_commit_head === UInt(i), Str("C "), Str(" "))
            , Mux(lsu_io.debug.stq_tail=== UInt(i), Str("T "), Str(" "))
            )

         if (i < io.dmem.debug.ld_req_slot.size)
         {
            printf("                 ld_req_slot[%d]=(%s%s) - laq_idx:%d pdst: %d bm:%x"
               , UInt(i)
               , Mux(io.dmem.debug.ld_req_slot(i).valid, Str("V"), Str("-"))
               , Mux(io.dmem.debug.ld_req_slot(i).killed, Str("K"), Str("-"))
               , io.dmem.debug.ld_req_slot(i).uop.ldq_idx
               , io.dmem.debug.ld_req_slot(i).uop.pdst
               , io.dmem.debug.ld_req_slot(i).uop.br_mask
            )
         }

         printf("\n")

      }

      // Rename Map Tables / ISA Register File
      val xpr_to_string =
              Vec(Str(" x0"), Str(" ra"), Str(" sp"), Str(" gp"),
                   Str(" tp"), Str(" t0"), Str(" t1"), Str(" t2"),
                   Str(" s0"), Str(" s1"), Str(" a0"), Str(" a1"),
                   Str(" a2"), Str(" a3"), Str(" a4"), Str(" a5"),
                   Str(" a6"), Str(" a7"), Str(" s2"), Str(" s3"),
                   Str(" s4"), Str(" s5"), Str(" s6"), Str(" s7"),
                   Str(" s8"), Str(" s9"), Str("s10"), Str("s11"),
                   Str(" t3"), Str(" t4"), Str(" t5"), Str(" t6"))

      val fpr_to_string =
              Vec( Str("ft0"), Str("ft1"), Str("ft2"), Str("ft3"),
                   Str("ft4"), Str("ft5"), Str("ft6"), Str("ft7"),
                   Str("fs0"), Str("fs1"), Str("fa0"), Str("fa1"),
                   Str("fa2"), Str("fa3"), Str("fa4"), Str("fa5"),
                   Str("fa6"), Str("fa7"), Str("fs2"), Str("fs3"),
                   Str("fs4"), Str("fs5"), Str("fs6"), Str("fs7"),
                   Str("fs8"), Str("fs9"), Str("fs10"), Str("fs11"),
                   Str("ft8"), Str("ft9"), Str("ft10"), Str("ft11"))


      if (white_space > 7)
      {
         white_space -= 7
         for (x <- 0 until 7)
         {
            if (x != 0) printf("\n")

            for (y <- 0 until 5)
            {
               val i = x + y*7

               if (i < 32)
               {
                  val phs_reg = rename_stage.io.debug.map_table(i).element

                  printf(" %sx%d(%s)=p%d[0x%x](%s)"
                     , Mux(rename_stage.io.debug.map_table(i).rbk_wen, Str("E"), Str(" "))
                     , UInt(i, LREG_SZ)
                     , xpr_to_string(i)
                     , phs_reg
                     //, rename_stage.io.debug.map_table(i).committed_element
                     , regfile.io.debug.registers(phs_reg)
                     , Mux(rename_stage.io.debug.bsy_table(phs_reg), Str("b"), Str("_"))
                  )
               }
            }
         }
         printf("\n")
      }
      if (white_space > 7)
      {
         white_space -= 7
         printf("\n")
         for (x <- 0 until 7)
         {
            if (x != 0) printf("\n")

            for (y <- 0 until 5)
            {
               val i = x + y*7

               if (i < 32)
               {
                  val phs_reg = rename_stage.io.debug.map_table(i+32).element

                  printf(" %sf%d(%s)=p%d[0x%x](%s)"
                     , Mux(rename_stage.io.debug.map_table(i).rbk_wen, Str("E"), Str(" "))
                     , UInt(i, LREG_SZ)
                     , fpr_to_string(i)
                     , phs_reg
                     //, rename_stage.io.debug.map_table(i).committed_element
                     , regfile.io.debug.registers(phs_reg)
                     , Mux(rename_stage.io.debug.bsy_table(phs_reg), Str("b"), Str("_"))
                  )
               }
            }
         }
      }

      for (x <- 0 until white_space)
      {
         printf("\n")
      }
   } // End DEBUG_PRINTF



   if (COMMIT_LOG_PRINTF)
   {
      var new_commit_cnt = UInt(0)
      for (w <- 0 until COMMIT_WIDTH)
      {
         val commit_log_enabled = if (COMMIT_LOG_EI_ONLY) (pcr.io.status.ei || com_uops(w).sret) else Bool(true)

         when (com_valids(w) && commit_log_enabled)
         {
            when (com_uops(w).dst_rtype === RT_FIX && com_uops(w).ldst != UInt(0))
            {
//               printf("0x%x (0x%x) x%d 0x%x |%d\n", com_uops(w).pc, com_uops(w).inst, com_uops(w).inst(RD_MSB,RD_LSB), com_uops(w).debug_wdata, tsc_reg)
               printf("@@@ 0x%x (0x%x) x%d 0x%x\n", com_uops(w).pc, com_uops(w).inst, com_uops(w).inst(RD_MSB,RD_LSB), com_uops(w).debug_wdata)
            }
            .elsewhen (com_uops(w).dst_rtype === RT_FLT)
            {
//               printf("0x%x (0x%x) f%d 0x%x |%d\n", com_uops(w).pc, com_uops(w).inst, com_uops(w).inst(RD_MSB,RD_LSB), com_uops(w).debug_wdata, tsc_reg)
               printf("@@@ 0x%x (0x%x) f%d 0x%x\n", com_uops(w).pc, com_uops(w).inst, com_uops(w).inst(RD_MSB,RD_LSB), com_uops(w).debug_wdata)
            }
            .otherwise
            {
//               printf("0x%x (0x%x) |%d\n", com_uops(w).pc, com_uops(w).inst, tsc_reg)
               printf("@@@ 0x%x (0x%x)\n", com_uops(w).pc, com_uops(w).inst)
            }
         }
      }
   }



   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // Page Table Walker

   io.ptw.ptbr := pcr.io.ptbr
   io.ptw.invalidate := pcr.io.fatc
   io.ptw.sret := com_sret
   io.ptw.status := pcr.io.status

   //-------------------------------------------------------------
   //-------------------------------------------------------------
   // **** Handle Reset Signal ****
   //-------------------------------------------------------------
   //-------------------------------------------------------------

   //when (reset.toBool)
   //{
   //}
}


}
