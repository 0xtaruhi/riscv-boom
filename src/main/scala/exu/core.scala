//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISC-V Processor Core
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// BOOM has the following (conceptual) stages:
//   if0 - Instruction Fetch 0 (next-pc select)
//   if1 - Instruction Fetch 1 (I$ access)
//   if2 - Instruction Fetch 2 (instruction return)
//   if3 - Instruction Fetch 3 (enqueue to fetch buffer)
//   if4 - Instruction Fetch 4 (redirect from bpd)
//   dec - Decode
//   ren - Rename1
//   dis - Rename2/Dispatch
//   iss - Issue
//   rrd - Register Read
//   exe - Execute
//   mem - Memory
//   sxt - Sign-extend
//   wb  - Writeback
//   com - Commit

package boom.exu

import java.nio.file.{Paths}

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.rocket.Instructions._
import freechips.rocketchip.rocket.{Causes, PRV}
import freechips.rocketchip.util.{Str, UIntIsOneOf, CoreMonitorBundle}
import freechips.rocketchip.devices.tilelink.{PLICConsts, CLINTConsts}

import boom.common._
import boom.exu.FUConstants._
import boom.common.BoomTilesKey
import boom.util.{RobTypeToChars, BoolToChar, GetNewUopAndBrMask, Sext, WrapInc, BoomCoreStringPrefix, DromajoCosimBlackBox}

/**
 * IO bundle for the BOOM Core. Connects the external components such as
 * the Frontend to the core.
 */
trait HasBoomCoreIO extends freechips.rocketchip.tile.HasTileParameters
{
  implicit val p: Parameters
  val io = new freechips.rocketchip.tile.CoreBundle
    with freechips.rocketchip.tile.HasExternallyDrivenTileConstants
  {
    val interrupts = Input(new freechips.rocketchip.tile.CoreInterrupts())
    val ifu = new boom.ifu.BoomFrontendIO
    val ptw = Flipped(new freechips.rocketchip.rocket.DatapathPTWIO())
    val rocc = Flipped(new freechips.rocketchip.tile.RoCCCoreIO())
    val lsu = Flipped(new boom.lsu.LSUCoreIO)
    val ptw_tlb = new freechips.rocketchip.rocket.TLBPTWIO()
    val trace = Output(Vec(coreParams.retireWidth,
      new freechips.rocketchip.rocket.TracedInstruction))
    val fcsr_rm = UInt(freechips.rocketchip.tile.FPConstants.RM_SZ.W)
  }
}

/**
 * Top level core object that connects the Frontend to the rest of the pipeline.
 */
class BoomCore(implicit p: Parameters) extends BoomModule
   with HasBoomCoreIO
{
  //**********************************
  // construct all of the modules

  // Only holds integer-registerfile execution units.
  val exe_units = new boom.exu.RingExecutionUnits

  // Meanwhile, the FP pipeline holds the FP issue window, FP regfile, and FP arithmetic units.
  var fp_pipeline: FpPipeline = null
  if (usingFPU) fp_pipeline = Module(new FpPipeline)

  // ********************************************************
  // Clear fp_pipeline before use
  if (usingFPU) {
    fp_pipeline.io.ll_wports := DontCare
    fp_pipeline.io.wb_valids := DontCare
    fp_pipeline.io.wb_pdsts  := DontCare
  }

  val numIrfWritePorts   = exe_units.numIrfWritePorts + memWidth
  val numLlIrfWritePorts = exe_units.numLlIrfWritePorts
  val numIrfReadPorts    = exe_units.numIrfReadPorts
  val numFpWakeupPorts   = if (usingFPU) fp_pipeline.io.wakeups.length else 0

  val decode_units     = for (w <- 0 until decodeWidth) yield { val d = Module(new DecodeUnit); d }
  val dec_brmask_logic = Module(new BranchMaskGenerationLogic(coreWidth))
  val rename_stage     = Module(new RenameStage(coreWidth, numIntPhysRegs, coreWidth, false))
  val fp_rename_stage  = if (usingFPU) Module(new RenameStage(coreWidth, numFpPhysRegs, numFpWakeupPorts, true))
                         else null
  val rename_stages    = if (usingFPU) Seq(rename_stage, fp_rename_stage) else Seq(rename_stage)

  val scheduler        = Module(new RingScheduler(intIssueParam.issueWidth))

  val dispatcher       = Module(new BasicDispatcher)

  val iregfile         = Module(new BankedRegisterFileSynthesizable(numIntPhysRegs, xLen))

  // wb arbiter for the 0th ll writeback
  // TODO: should this be a multi-arb?
  val ll_wbarb         = Module(new Arbiter(new ExeUnitResp(xLen), 1 +
                                                                   (if (usingFPU) 1 else 0) +
                                                                   (if (usingRoCC) 1 else 0)))
  val iregister_read   = Module(new RingRegisterRead(exe_units.withFilter(_.readsIrf).map(_.supportedFuncUnits)))
  val rob              = Module(new Rob(
                           numIrfWritePorts + numFpWakeupPorts, // +memWidth for ll writebacks
                           numFpWakeupPorts))

  val writebacks = Wire(Vec(coreWidth, Valid(new ExeUnitResp(xLen))))
  val wakeups    = Wire(Vec(coreWidth, Valid(UInt(pregSz.W)))) // 'Slow' wakeups
  wakeups := DontCare

  //***********************************
  // Pipeline State Registers and Wires

  // Decode/Rename1 Stage
  val dec_valids = Wire(Vec(coreWidth, Bool()))  // are the decoded instruction valid? It may be held up though.
  val dec_uops   = Wire(Vec(coreWidth, new MicroOp()))
  val dec_fire   = Wire(Vec(coreWidth, Bool()))  // can the instruction fire beyond decode?
                                                    // (can still be stopped in ren or dis)
  val dec_ready  = Wire(Bool())
  val dec_xcpts  = Wire(Vec(coreWidth, Bool()))
  val ren_stalls = Wire(Vec(coreWidth, Bool()))

  // Rename2/Dispatch stage
  val dis_valids = Wire(Vec(coreWidth, Bool()))
  val dis_uops   = Wire(Vec(coreWidth, new MicroOp))
  val dis_fire   = Wire(Vec(coreWidth, Bool()))
  val dis_ready  = Wire(Bool())

  // Issue Stage/Register Read
  val iss_valids = Wire(Vec(exe_units.numIrfReaders, Bool()))
  val iss_uops   = Wire(Vec(exe_units.numIrfReaders, new MicroOp()))
  val bypasses   = Wire(new BypassData(exe_units.numTotalBypassPorts, xLen))

  // Branch Unit
  val br_unit = Wire(new BranchUnitResp())
  val brunit_idx = exe_units.br_unit_idx
  br_unit <> exe_units.br_unit_io

  val flush_ifu = br_unit.brinfo.mispredict || // In practice, means flushing everything prior to dispatch.
                         rob.io.flush.valid || // i.e. 'flush in-order part of the pipeline'
                         io.ifu.sfence_take_pc

  assert (!(br_unit.brinfo.mispredict && rob.io.commit.rollback), "Can't have a mispredict during rollback.")

  for (eu <- exe_units) {
    eu.io.brinfo := br_unit.brinfo
  }

  if (usingFPU) {
    fp_pipeline.io.brinfo := br_unit.brinfo
  }

  // Load/Store Unit & ExeUnits
  val mem_units = exe_units.memory_units
  val mem_resps = mem_units.map(_.io.ll_iresp)
  for (i <- 0 until memWidth) {
    mem_units(i).io.lsu_io <> io.lsu.exe(i)
  }

  //-------------------------------------------------------------
  // Uarch Hardware Performance Events (HPEs)

  val perfEvents = new freechips.rocketchip.rocket.EventSets(Seq(
    new freechips.rocketchip.rocket.EventSet((mask, hits) => (mask & hits).orR, Seq(
      ("exception", () => rob.io.com_xcpt.valid),
      ("nop",       () => false.B),
      ("nop",       () => false.B),
      ("nop",       () => false.B))),

    new freechips.rocketchip.rocket.EventSet((mask, hits) => (mask & hits).orR, Seq(
      ("I$ blocked",                        () => icache_blocked),
      ("nop",                               () => false.B),
      ("branch misprediction",              () => br_unit.brinfo.mispredict),
      ("control-flow target misprediction", () => br_unit.brinfo.mispredict &&
                                                  br_unit.brinfo.is_jr),
      ("flush",                             () => rob.io.flush.valid),
      ("branch resolved",                   () => br_unit.brinfo.valid))),

    new freechips.rocketchip.rocket.EventSet((mask, hits) => (mask & hits).orR, Seq(
      ("I$ miss",     () => io.ifu.perf.acquire),
//      ("D$ miss",     () => io.dmem.perf.acquire),
//      ("D$ release",  () => io.dmem.perf.release),
      ("ITLB miss",   () => io.ifu.perf.tlbMiss),
//      ("DTLB miss",   () => io.dmem.perf.tlbMiss),
      ("L2 TLB miss", () => io.ptw.perf.l2miss)))))

  val csr = Module(new freechips.rocketchip.rocket.CSRFile(perfEvents, boomParams.customCSRs.decls))
  csr.io.inst foreach { c => c := DontCare }
  csr.io.rocc_interrupt := io.rocc.interrupt

  val custom_csrs = Wire(new BoomCustomCSRs)
  (custom_csrs.csrs zip csr.io.customCSRs).map { case (lhs, rhs) => lhs := rhs }

  // evaluate performance counters
  val icache_blocked = !(io.ifu.fetchpacket.valid || RegNext(io.ifu.fetchpacket.valid))
  csr.io.counters foreach { c => c.inc := RegNext(perfEvents.evaluate(c.eventSel)) }

  //****************************************
  // Time Stamp Counter & Retired Instruction Counter
  // (only used for printf and vcd dumps - the actual counters are in the CSRFile)
  val debug_tsc_reg = RegInit(0.U(xLen.W))
  val debug_irt_reg = RegInit(0.U(xLen.W))
  debug_tsc_reg := debug_tsc_reg + Mux(O3PIPEVIEW_PRINTF.B, O3_CYCLE_TIME.U, 1.U)
  debug_irt_reg := debug_irt_reg + PopCount(rob.io.commit.valids.asUInt)
  dontTouch(debug_tsc_reg)
  dontTouch(debug_irt_reg)

  //****************************************
  // Print-out information about the machine

  val issStr =
    if (enableAgePriorityIssue) " (Age-based Priority)"
    else " (Unordered Priority)"

  val btbStr =
    if (enableBTB) ("" + boomParams.btb.nSets * boomParams.btb.nWays + " entries (" + boomParams.btb.nSets + " x " + boomParams.btb.nWays + " ways)")
    else 0

  val fpPipelineStr =
    if (usingFPU) fp_pipeline.toString
    else ""

  override def toString: String =
    (BoomCoreStringPrefix("====Overall Core Params====") + "\n"
    + exe_units.toString + "\n"
    + fpPipelineStr + "\n"
    + rob.toString + "\n"
    + BoomCoreStringPrefix(
        "===Other Core Params===",
        "Fetch Width           : " + fetchWidth,
        "Decode Width          : " + coreWidth,
        "Issue Width           : " + issueParams.map(_.issueWidth).sum,
        "ROB Size              : " + numRobEntries,
        "Issue Window Size     : " + issueParams.map(_.numEntries) + issStr,
        "Load/Store Unit Size  : " + numLdqEntries + "/" + numStqEntries,
        "Num Int Phys Registers: " + numIntPhysRegs,
        "Num FP  Phys Registers: " + numFpPhysRegs,
        "Max Branch Count      : " + maxBrCount)
    + BoomCoreStringPrefix(
        "RAS Size              : " + (if (enableBTB) boomParams.btb.nRAS else 0)) + "\n"
    + iregfile.toString + "\n"
    + BoomCoreStringPrefix(
        "Num Slow Wakeup Ports : " + numIrfWritePorts,
        "Num Fast Wakeup Ports : " + exe_units.count(_.bypassable),
        "Num Bypass Ports      : " + exe_units.numTotalBypassPorts) + "\n"
    + BoomCoreStringPrefix(
        "DCache Ways           : " + dcacheParams.nWays,
        "DCache Sets           : " + dcacheParams.nSets,
        "DCache nMSHRs         : " + dcacheParams.nMSHRs,
        "ICache Ways           : " + icacheParams.nWays,
        "ICache Sets           : " + icacheParams.nSets,
        "D-TLB Entries         : " + dcacheParams.nTLBEntries,
        "I-TLB Entries         : " + icacheParams.nTLBEntries,
        "Paddr Bits            : " + paddrBits,
        "Vaddr Bits            : " + vaddrBits) + "\n"
    + BoomCoreStringPrefix(
        "Using FPU Unit?       : " + usingFPU.toString,
        "Using FDivSqrt?       : " + usingFDivSqrt.toString,
        "Using VM?             : " + usingVM.toString) + "\n")

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Fetch Stage/Frontend ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  io.ifu.br_unit := br_unit
  io.ifu.tsc_reg := debug_tsc_reg

  // Breakpoint info
  io.ifu.status  := csr.io.status
  io.ifu.bp      := csr.io.bp

  // SFence needs access to the PC to inject an address into the TLB's CAM port. The ROB
  // will have to later redirect the PC back to the regularly scheduled program.
  io.ifu.sfence_take_pc := false.B
  io.ifu.sfence_addr    := DontCare
  for (i <- 0 until memWidth) {
    when (io.lsu.exe(i).req.bits.sfence.valid) {
      io.ifu.sfence_take_pc    := true.B
      io.ifu.sfence_addr       := io.lsu.exe(i).req.bits.sfence.bits.addr
    }
  }

  // We must redirect the PC the cycle after playing the SFENCE game.
  io.ifu.flush_take_pc     := rob.io.flush.valid || RegNext(io.ifu.sfence_take_pc)

  // TODO FIX THIS HACK
  // The below code works because of two quirks with the flush mechanism
  //  1 ) All flush_on_commit instructions are also is_unique,
  //      In the future, this constraint will be relaxed.
  //  2 ) We send out flush signals one cycle after the commit signal. We need to
  //      mux between one/two cycle delay for the following cases:
  //       ERETs are reported to the CSR two cycles before we send the flush
  //       Exceptions are reported to the CSR one cycle before we send the flush
  // This discrepency should be resolved elsewhere.
  io.ifu.flush_pc          := Mux(RegNext(RegNext(csr.io.eret)),
                                  RegNext(RegNext(csr.io.evec)),
                                  RegNext(csr.io.evec))
  io.ifu.com_ftq_idx       := rob.io.com_xcpt.bits.ftq_idx

  io.ifu.clear_fetchbuffer := flush_ifu

  io.ifu.flush_info.valid  := rob.io.flush.valid
  io.ifu.flush_info.bits   := rob.io.flush.bits

  // Tell the FTQ it can deallocate entries by passing youngest ftq_idx.
  val youngest_com_idx = (coreWidth-1).U - PriorityEncoder(rob.io.commit.valids.reverse)
  io.ifu.commit.valid := rob.io.commit.valids.reduce(_|_)
  io.ifu.commit.bits  := rob.io.commit.uops(youngest_com_idx).ftq_idx

  io.ifu.flush_icache :=
    Range(0,coreWidth).map{i => rob.io.commit.valids(i) && rob.io.commit.uops(i).is_fencei}.reduce(_|_) ||
    (br_unit.brinfo.mispredict && br_unit.brinfo.is_jr &&  csr.io.status.debug)

  // Delay sfence to match pushing the sfence.addr into the TLB's CAM port.
  io.ifu.sfence.valid := false.B
  io.ifu.sfence.bits  := DontCare
  for (i <- 0 until memWidth) {
    when (RegNext(io.lsu.exe(i).req.bits.sfence.valid)) {
      io.ifu.sfence.valid := true.B
      io.ifu.sfence.bits  := RegNext(io.lsu.exe(i).req.bits.sfence.bits)
    }
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Branch Prediction ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  io.ifu.flush := rob.io.flush.valid

  io.ifu.status_prv    := csr.io.status.prv
  io.ifu.status_debug  := csr.io.status.debug

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Decode Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // track mask of finished instructions in the bundle
  // use this to mask out insts coming from FetchBuffer that have been finished
  // for example, back pressure may cause us to only issue some instructions from FetchBuffer
  // but on the next cycle, we only want to retry a subset
  val dec_finished_mask = RegInit(0.U(coreWidth.W))

  //-------------------------------------------------------------
  // Pull out instructions and send to the Decoders

  io.ifu.fetchpacket.ready := dec_ready
  val dec_fbundle = io.ifu.fetchpacket.bits

  //-------------------------------------------------------------
  // Decoders

  // stall fetch/dcode because we ran out of branch tags
  val branch_mask_full = Wire(Vec(coreWidth, Bool()))

  for (w <- 0 until coreWidth) {
    dec_valids(w)                      := io.ifu.fetchpacket.valid && dec_fbundle.uops(w).valid &&
                                          !dec_finished_mask(w)
    decode_units(w).io.enq.uop         := dec_fbundle.uops(w).bits
    decode_units(w).io.status          := csr.io.status
    decode_units(w).io.csr_decode      <> csr.io.decode(w)
    decode_units(w).io.interrupt       := csr.io.interrupt
    decode_units(w).io.interrupt_cause := csr.io.interrupt_cause

    dec_uops(w) := decode_units(w).io.deq.uop
  }

  //-------------------------------------------------------------
  // FTQ GetPC Port Arbitration

  val bru_pc_req  = Wire(Decoupled(UInt(log2Ceil(ftqSz).W)))
  val xcpt_pc_req = Wire(Decoupled(UInt(log2Ceil(ftqSz).W)))

  val ftq_arb = Module(new Arbiter(UInt(log2Ceil(ftqSz).W), 2))

  ftq_arb.io.in(0) <> bru_pc_req
  ftq_arb.io.in(1) <> xcpt_pc_req

  // Hookup FTQ
  io.ifu.get_pc.ftq_idx := ftq_arb.io.out.bits
  ftq_arb.io.out.ready  := true.B

  // Branch Unit Requests
  bru_pc_req.valid := RegNext(iss_valids(brunit_idx))
  bru_pc_req.bits  := RegNext(iss_uops(brunit_idx).ftq_idx)
  exe_units(brunit_idx).io.get_ftq_pc.fetch_pc := RegNext(io.ifu.get_pc.fetch_pc)
  exe_units(brunit_idx).io.get_ftq_pc.next_val := RegNext(io.ifu.get_pc.next_val)
  exe_units(brunit_idx).io.get_ftq_pc.next_pc  := RegNext(io.ifu.get_pc.next_pc)

  // Frontend Exception Requests
  val xcpt_idx = PriorityEncoder(dec_xcpts)
  xcpt_pc_req.valid    := dec_xcpts.reduce(_||_)
  xcpt_pc_req.bits     := dec_uops(xcpt_idx).ftq_idx
  rob.io.xcpt_fetch_pc := RegEnable(io.ifu.get_pc.fetch_pc, dis_ready)

  //-------------------------------------------------------------
  // Decode/Rename1 pipeline logic

  dec_xcpts := dec_uops zip dec_valids map {case (u,v) => u.exception && v}
  val dec_xcpt_stall = dec_xcpts.reduce(_||_) && !xcpt_pc_req.ready

  val dec_hazards = (0 until coreWidth).map(w =>
                      dec_valids(w) &&
                      (  !dis_ready
                      || rob.io.commit.rollback
                      || dec_xcpt_stall
                      || branch_mask_full(w)
                      || flush_ifu))

  val dec_stalls = dec_hazards.scanLeft(false.B) ((s,h) => s || h).takeRight(coreWidth)
  dec_fire := (0 until coreWidth).map(w => dec_valids(w) && !dec_stalls(w))

  // all decoders are empty and ready for new instructions
  dec_ready := dec_fire.last

  when (dec_ready || flush_ifu) {
    dec_finished_mask := 0.U
  } .otherwise {
    dec_finished_mask := dec_fire.asUInt | dec_finished_mask
  }

  //-------------------------------------------------------------
  // Branch Mask Logic

  dec_brmask_logic.io.brinfo := br_unit.brinfo
  dec_brmask_logic.io.flush_pipeline := rob.io.flush.valid

  for (w <- 0 until coreWidth) {
    dec_brmask_logic.io.is_branch(w) := !dec_finished_mask(w) && dec_uops(w).allocate_brtag
    dec_brmask_logic.io.will_fire(w) :=  dec_fire(w) &&
                                         dec_uops(w).allocate_brtag // ren, dis can back pressure us
    dec_uops(w).br_tag  := dec_brmask_logic.io.br_tag(w)
    dec_uops(w).br_mask := dec_brmask_logic.io.br_mask(w)
  }

  branch_mask_full := dec_brmask_logic.io.is_full

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Register Rename Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Inputs
  for (rename <- rename_stages) {
    rename.io.kill := flush_ifu
    rename.io.brinfo := br_unit.brinfo

    rename.io.debug_rob_empty := rob.io.empty

    rename.io.dec_fire := dec_fire
    rename.io.dec_uops := dec_uops

    rename.io.dis_fire := dis_fire
    rename.io.dis_ready := dis_ready

    rename.io.com_valids := rob.io.commit.valids
    rename.io.com_uops := rob.io.commit.uops
    rename.io.rbk_valids := rob.io.commit.rbk_valids
    rename.io.rollback := rob.io.commit.rollback
  }

  // Outputs
  dis_uops := rename_stage.io.ren2_uops
  dis_valids := rename_stage.io.ren2_mask
  ren_stalls := rename_stage.io.ren_stalls


  /**
   * TODO This is a bit nasty, but it's currently necessary to
   * split the INT/FP rename pipelines into separate instantiations.
   * Won't have to do this anymore with a properly decoupled FP pipeline.
   */
  for (w <- 0 until coreWidth) {
    val i_uop   = rename_stage.io.ren2_uops(w)
    val f_uop   = if (usingFPU) fp_rename_stage.io.ren2_uops(w) else NullMicroOp
    val f_stall = if (usingFPU) fp_rename_stage.io.ren_stalls(w) else false.B

    // lrs1 can "pass through" to prs1. Used solely to index the csr file.
    dis_uops(w).prs1 := Mux(dis_uops(w).lrs1_rtype === RT_FLT, f_uop.prs1,
                        Mux(dis_uops(w).lrs1_rtype === RT_FIX, i_uop.prs1, dis_uops(w).lrs1))
    dis_uops(w).prs2 := Mux(dis_uops(w).lrs2_rtype === RT_FLT, f_uop.prs2, i_uop.prs2)
    dis_uops(w).prs3 := f_uop.prs3
    dis_uops(w).pdst := Mux(dis_uops(w).dst_rtype  === RT_FLT, f_uop.pdst, i_uop.pdst)
    dis_uops(w).stale_pdst := Mux(dis_uops(w).dst_rtype === RT_FLT, f_uop.stale_pdst, i_uop.stale_pdst)

    dis_uops(w).prs1_busy := i_uop.prs1_busy && (dis_uops(w).lrs1_rtype === RT_FIX) ||
                             f_uop.prs1_busy && (dis_uops(w).lrs1_rtype === RT_FLT)
    dis_uops(w).prs2_busy := i_uop.prs2_busy && (dis_uops(w).lrs2_rtype === RT_FIX) ||
                             f_uop.prs2_busy && (dis_uops(w).lrs2_rtype === RT_FLT)
    dis_uops(w).prs3_busy := f_uop.prs3_busy && dis_uops(w).frs3_en

    ren_stalls(w) := rename_stage.io.ren_stalls(w) || f_stall
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Dispatch Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  //-------------------------------------------------------------
  // Rename2/Dispatch pipeline logic

  val dis_prior_slot_valid = dis_valids.scanLeft(false.B) ((s,v) => s || v)
  val dis_prior_slot_unique = (dis_uops zip dis_valids).scanLeft(false.B) {case (s,(u,v)) => s || v && u.is_unique}
  val wait_for_empty_pipeline = (0 until coreWidth).map(w => (dis_uops(w).is_unique || custom_csrs.disableOOO) &&
                                  (!rob.io.empty || !io.lsu.fencei_rdy || dis_prior_slot_valid(w)))
  val rocc_shim_busy = if (usingRoCC) !exe_units.rocc_unit.io.rocc.rxq_empty else false.B
  val wait_for_rocc = (0 until coreWidth).map(w =>
                        (dis_uops(w).is_fence || dis_uops(w).is_fencei) && (io.rocc.busy || rocc_shim_busy))
  val rxq_full = if (usingRoCC) exe_units.rocc_unit.io.rocc.rxq_full else false.B
  val block_rocc = (dis_uops zip dis_valids).map{case (u,v) => v && u.uopc === uopROCC}.scanLeft(rxq_full)(_||_)
  val dis_rocc_alloc_stall = (dis_uops.map(_.uopc === uopROCC) zip block_rocc) map {case (p,r) =>
                               if (usingRoCC) p && r else false.B}

  val dis_hazards = (0 until coreWidth).map(w =>
                      dis_valids(w) &&
                      (  !rob.io.ready
                      || ren_stalls(w)
                      || io.lsu.ldq_full(w) && dis_uops(w).uses_ldq
                      || io.lsu.stq_full(w) && dis_uops(w).uses_stq
                      || !dispatcher.io.ren_uops(w).ready
                      || wait_for_empty_pipeline(w)
                      || wait_for_rocc(w)
                      || dis_prior_slot_unique(w)
                      || dis_rocc_alloc_stall(w)
                      || flush_ifu))


  io.lsu.fence_dmem := (dis_valids zip wait_for_empty_pipeline).map {case (v,w) => v && w} .reduce(_||_)

  val dis_stalls = dis_hazards.scanLeft(false.B) ((s,h) => s || h).takeRight(coreWidth)
  dis_fire := dis_valids zip dis_stalls map {case (v,s) => v && !s}
  dis_ready := !dis_stalls.last

  //-------------------------------------------------------------
  // LDQ/STQ Allocation Logic

  for (w <- 0 until coreWidth) {
    // Dispatching instructions request load/store queue entries when they can proceed.
    dis_uops(w).ldq_idx := io.lsu.dis_ldq_idx(w)
    dis_uops(w).stq_idx := io.lsu.dis_stq_idx(w)
  }

  //-------------------------------------------------------------
  // Rob Allocation Logic

  rob.io.enq_valids := dis_fire
  rob.io.enq_uops   := dis_uops
  rob.io.enq_partial_stall := dis_stalls.last
  rob.io.debug_tsc := debug_tsc_reg
  rob.io.csr_stall := csr.io.csr_stall

  for (w <- 0 until coreWidth) {
    // note: this assumes uops haven't been shifted - there's a 1:1 match between PC's LSBs and "w" here
    // (thus the LSB of the rob_idx gives part of the PC)
    if (coreWidth == 1) {
      dis_uops(w).rob_idx := rob.io.rob_tail_idx
    } else {
      dis_uops(w).rob_idx := Cat(rob.io.rob_tail_idx >> log2Ceil(coreWidth).U,
                               w.U(log2Ceil(coreWidth).W))
    }
  }

  //-------------------------------------------------------------
  // RoCC allocation logic
  if (usingRoCC) {
    for (w <- 0 until coreWidth) {
      // We guarantee only decoding 1 RoCC instruction per cycle
      dis_uops(w).rxq_idx := exe_units.rocc_unit.io.rocc.rxq_idx(w)
    }
  }

  //-------------------------------------------------------------
  // Dispatch to issue queues

  for (w <- 0 until coreWidth) {
    scheduler.io.dis_uops(w).bits  := dis_uops(w)
    scheduler.io.dis_uops(w).valid := dis_valids(w) && dis_uops(w).iq_type === IQT_INT
  }

  // Only fp_pipeline uses the 'dispatcher' module
  fp_pipeline.io.dis_uops <> dispatcher.io.dis_uops(0)

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Issue Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Generate 'slow' wakeup signals from writeback ports.
  for (w <- 0 until coreWidth) {
    val wb = writebacks(w)
    wakeups(w).bits  := wb.bits.uop.pdst
    wakeups(w).valid := wb.valid
                       && wb.bits.uop.rf_wen
                       && wb.bits.uop.dst_rtype === RT_FIX
  }

  for ((renport, intport) <- rename_stage.io.wakeups zip wakeups) {
    renport <> intport
  }
  if (usingFPU) {
    for ((renport, fpport) <- fp_rename_stage.io.wakeups zip fp_pipeline.io.wakeups) {
      renport <> fpport
    }
  }

  val idiv_issued = false.B

  for (w <- 0 until coreWidth) {
    iss_valids(w) := scheduler.io.iss_uops(w).valid
    iss_uops(w)   := scheduler.io.iss_uops(w).bits

    idiv_issued = idiv_issued || (iss_valids(iss_idx) && iss_uops(iss_idx).fu_code_is(FU_DIV))
  }

  // Send slow wakeups to scheduler
  scheduler.io.wakeups := wakeups

  // Load-hit Misspeculations
  scheduler.io.ld_miss := io.lsu.ld_miss

  // Supress just-issued divides from issuing back-to-back, since it's an iterative divider.
  // But it takes a cycle to get to the Exe stage, so it can't tell us it is busy yet.
  scheduler.io.div_busy := RegNext(idiv_issued) || exe_units.idiv_busy

  scheduler.io.brinfo := br_unit.brinfo
  scheduler.io.flush  := rob.io.flush.valid

  mem_units.map(u => u.io.com_exception := rob.io.flush.valid)


  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Register Read Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Register Read <- Issue (rrd <- iss)
  iregister_read.io.rf_read_ports <> iregfile.io.read_ports

  iregister_read.io.iss_valids := iss_valids
  iregister_read.io.iss_uops := iss_uops
  iregister_read.io.iss_uops map { u => u.iw_p1_poisoned := false.B; u.iw_p2_poisoned := false.B }

  iregister_read.io.brinfo := br_unit.brinfo
  iregister_read.io.kill   := rob.io.flush.valid

  iregister_read.io.bypass := bypasses

  //-------------------------------------------------------------
  // Privileged Co-processor 0 Register File
  // Note: Normally this would be bad in that I'm writing state before
  // committing, so to get this to work I stall the entire pipeline for
  // CSR instructions so I never speculate these instructions.

  val csr_exe_unit = exe_units.csr_unit

  // for critical path reasons, we aren't zero'ing this out if resp is not valid
  val csr_rw_cmd = csr_exe_unit.io.iresp.bits.uop.ctrl.csr_cmd
  val wb_wdata = csr_exe_unit.io.iresp.bits.data

  csr.io.rw.addr        := csr_exe_unit.io.iresp.bits.uop.csr_addr
  csr.io.rw.cmd         := freechips.rocketchip.rocket.CSR.maskCmd(csr_exe_unit.io.iresp.valid, csr_rw_cmd)
  csr.io.rw.wdata       := wb_wdata

  // Extra I/O
  csr.io.retire    := PopCount(rob.io.commit.valids.asUInt)
  csr.io.exception := rob.io.com_xcpt.valid
  // csr.io.pc used for setting EPC during exception or CSR.io.trace.
  csr.io.pc        := (boom.util.AlignPCToBoundary(io.ifu.com_fetch_pc, icBlockBytes)
                     + rob.io.com_xcpt.bits.pc_lob
                     - Mux(rob.io.com_xcpt.bits.edge_inst, 2.U, 0.U))
  // Cause not valid for for CALL or BREAKPOINTs (CSRFile will override it).
  csr.io.cause     := rob.io.com_xcpt.bits.cause
  csr.io.ungated_clock := clock

  val tval_valid = csr.io.exception &&
    csr.io.cause.isOneOf(
      //Causes.illegal_instruction.U, we currently only write 0x0 for illegal instructions
      Causes.breakpoint.U,
      Causes.misaligned_load.U,
      Causes.misaligned_store.U,
      Causes.load_access.U,
      Causes.store_access.U,
      Causes.fetch_access.U,
      Causes.load_page_fault.U,
      Causes.store_page_fault.U,
      Causes.fetch_page_fault.U)

  csr.io.tval := Mux(tval_valid,
    encodeVirtualAddress(rob.io.com_xcpt.bits.badvaddr, rob.io.com_xcpt.bits.badvaddr), 0.U)

  // TODO move this function to some central location (since this is used elsewhere).
  def encodeVirtualAddress(a0: UInt, ea: UInt) =
    if (vaddrBitsExtended == vaddrBits) {
      ea
    } else {
      // Efficient means to compress 64-bit VA into vaddrBits+1 bits.
      // (VA is bad if VA(vaddrBits) != VA(vaddrBits-1)).
      val a = a0.asSInt >> vaddrBits
      val msb = Mux(a === 0.S || a === -1.S, ea(vaddrBits), !ea(vaddrBits-1))
      Cat(msb, ea(vaddrBits-1,0))
    }

  // reading requires serializing the entire pipeline
  csr.io.fcsr_flags.valid := rob.io.commit.fflags.valid
  csr.io.fcsr_flags.bits  := rob.io.commit.fflags.bits
  csr.io.set_fs_dirty.get := rob.io.commit.fflags.valid

  exe_units.withFilter(_.hasFcsr).map(_.io.fcsr_rm := csr.io.fcsr_rm)
  io.fcsr_rm := csr.io.fcsr_rm

  if (usingFPU) {
    fp_pipeline.io.fcsr_rm := csr.io.fcsr_rm
  }

  csr.io.hartid := io.hartid
  csr.io.interrupts := io.interrupts

// TODO can we add this back in, but handle reset properly and save us
//      the mux above on csr.io.rw.cmd?
//   assert (!(csr_rw_cmd =/= rocket.CSR.N && !exe_units(0).io.resp(0).valid),
//   "CSRFile is being written to spuriously.")

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Execute Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  iss_idx = 0
  var bypass_idx = 0
  for (w <- 0 until exe_units.length) {
    val exe_unit = exe_units(w)
    if (exe_unit.readsIrf) {
      exe_unit.io.req <> iregister_read.io.exe_reqs(iss_idx)

      if (exe_unit.bypassable) {
        for (i <- 0 until exe_unit.numBypassStages) {
          bypasses.valid(bypass_idx) := exe_unit.io.bypass.valid(i)
          bypasses.uop(bypass_idx)   := exe_unit.io.bypass.uop(i)
          bypasses.data(bypass_idx)  := exe_unit.io.bypass.data(i)
          bypass_idx += 1
        }
      }
      iss_idx += 1
    }
  }
  require (bypass_idx == exe_units.numTotalBypassPorts)

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Load/Store Unit ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // enqueue basic load/store info in Decode
  for (w <- 0 until coreWidth) {
    io.lsu.dis_uops(w).valid := dis_fire(w)
    io.lsu.dis_uops(w).bits  := dis_uops(w)
  }

  // tell LSU about committing loads and stores to clear entries
  io.lsu.commit                  := rob.io.commit

  // tell LSU that it should fire a load that waits for the rob to clear
  io.lsu.commit_load_at_rob_head := rob.io.com_load_is_at_rob_head

  //com_xcpt.valid comes too early, will fight against a branch that resolves same cycle as an exception
  io.lsu.exception := rob.io.flush.valid

  // Handle Branch Mispeculations
  io.lsu.brinfo := br_unit.brinfo
  io.lsu.rob_head_idx := rob.io.rob_head_idx
  io.lsu.rob_pnr_idx  := rob.io.rob_pnr_idx

  io.lsu.tsc_reg := debug_tsc_reg


  if (usingFPU) {
    io.lsu.fp_stdata <> fp_pipeline.io.to_sdq
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Writeback Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  var w_cnt = 1
  iregfile.io.write_ports(0) := WritePort(ll_wbarb.io.out, ipregSz, xLen, RT_FIX)
  ll_wbarb.io.in(0) <> mem_resps(0)
  assert (ll_wbarb.io.in(0).ready) // never backpressure the memory unit.
  for (i <- 1 until memWidth) {
    iregfile.io.write_ports(w_cnt) := WritePort(mem_resps(i), ipregSz, xLen, RT_FIX)
    w_cnt += 1
  }

  for (i <- 0 until exe_units.length) {
    if (exe_units(i).writesIrf) {
      val wbresp = exe_units(i).io.iresp
      val wbpdst = wbresp.bits.uop.pdst
      val wbdata = wbresp.bits.data

      def wbIsValid(rtype: UInt) =
        wbresp.valid && wbresp.bits.uop.rf_wen && wbresp.bits.uop.dst_rtype === rtype
      val wbReadsCSR = wbresp.bits.uop.ctrl.csr_cmd =/= freechips.rocketchip.rocket.CSR.N

      iregfile.io.write_ports(w_cnt).valid     := wbIsValid(RT_FIX)
      iregfile.io.write_ports(w_cnt).bits.addr := wbpdst
      wbresp.ready := true.B
      if (exe_units(i).hasCSR) {
        iregfile.io.write_ports(w_cnt).bits.data := Mux(wbReadsCSR, csr.io.rw.rdata, wbdata)
      } else {
        iregfile.io.write_ports(w_cnt).bits.data := wbdata
      }

      assert (!wbIsValid(RT_FLT), "[fppipeline] An FP writeback is being attempted to the Int Regfile.")

      assert (!(wbresp.valid &&
        !wbresp.bits.uop.rf_wen &&
        wbresp.bits.uop.dst_rtype === RT_FIX),
        "[fppipeline] An Int writeback is being attempted with rf_wen disabled.")

      assert (!(wbresp.valid &&
        wbresp.bits.uop.rf_wen &&
        wbresp.bits.uop.dst_rtype =/= RT_FIX),
        "[fppipeline] writeback being attempted to Int RF with dst != Int type exe_units("+i+").iresp")
      w_cnt += 1
    }
  }
  require(w_cnt == iregfile.io.write_ports.length)


  if (usingFPU) {
    // Connect IFPU
    fp_pipeline.io.from_int  <> exe_units.ifpu_unit.io.ll_fresp
    // Connect FPIU
    ll_wbarb.io.in(1)        <> fp_pipeline.io.to_int
    // Connect FLDs
    fp_pipeline.io.ll_wports <> exe_units.memory_units.map(_.io.ll_fresp)
  }
  if (usingRoCC) {
    require(usingFPU)
    ll_wbarb.io.in(2)       <> exe_units.rocc_unit.io.ll_iresp
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Commit Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Writeback
  // ---------
  // First connect the ll_wport
  val ll_uop = ll_wbarb.io.out.bits.uop
  rob.io.wb_resps(0).valid  := ll_wbarb.io.out.valid && !(ll_uop.uses_stq && !ll_uop.is_amo)
  rob.io.wb_resps(0).bits   <> ll_wbarb.io.out.bits
  rob.io.debug_wb_valids(0) := ll_wbarb.io.out.valid && ll_uop.dst_rtype =/= RT_X
  rob.io.debug_wb_wdata(0)  := ll_wbarb.io.out.bits.data
  var cnt = 1
  for (i <- 1 until memWidth) {
    val mem_uop = mem_resps(i).bits.uop
    rob.io.wb_resps(cnt).valid := mem_resps(i).valid && !(mem_uop.uses_stq && !mem_uop.is_amo)
    rob.io.wb_resps(cnt).bits  := mem_resps(i).bits
    rob.io.debug_wb_valids(cnt) := mem_resps(i).valid && mem_uop.dst_rtype =/= RT_X
    rob.io.debug_wb_wdata(cnt)  := mem_resps(i).bits.data
    cnt += 1
  }
  var f_cnt = 0 // rob fflags port index
  for (eu <- exe_units) {
    if (eu.writesIrf)
    {
      val resp   = eu.io.iresp
      val wb_uop = resp.bits.uop
      val data   = resp.bits.data

      rob.io.wb_resps(cnt).valid := resp.valid && !(wb_uop.uses_stq && !wb_uop.is_amo)
      rob.io.wb_resps(cnt).bits  <> resp.bits
      rob.io.debug_wb_valids(cnt) := resp.valid && wb_uop.rf_wen && wb_uop.dst_rtype === RT_FIX
      if (eu.hasFFlags) {
        rob.io.fflags(f_cnt) <> resp.bits.fflags
        f_cnt += 1
      }
      if (eu.hasCSR) {
        rob.io.debug_wb_wdata(cnt) := Mux(wb_uop.ctrl.csr_cmd =/= freechips.rocketchip.rocket.CSR.N,
          csr.io.rw.rdata,
          data)
      } else {
        rob.io.debug_wb_wdata(cnt) := data
      }
      cnt += 1
    }
  }

  require(cnt == numIrfWritePorts)
  if (usingFPU) {
    for ((wdata, wakeup) <- fp_pipeline.io.debug_wb_wdata zip fp_pipeline.io.wakeups) {
      rob.io.wb_resps(cnt) <> wakeup
      rob.io.fflags(f_cnt) <> wakeup.bits.fflags
      rob.io.debug_wb_valids(cnt) := wakeup.valid
      rob.io.debug_wb_wdata(cnt) := wdata
      cnt += 1
      f_cnt += 1

      assert (!(wakeup.valid && wakeup.bits.uop.dst_rtype =/= RT_FLT),
        "[core] FP wakeup does not write back to a FP register.")

      assert (!(wakeup.valid && !wakeup.bits.uop.fp_val),
        "[core] FP wakeup does not involve an FP instruction.")
    }
  }

  require (cnt == rob.numWakeupPorts)
  require (f_cnt == rob.numFpuPorts)

  // branch resolution
  rob.io.brinfo <> br_unit.brinfo

  exe_units(brunit_idx).io.status := csr.io.status

  // Connect breakpoint info to memaddrcalcunit
  for (i <- 0 until memWidth) {
    mem_units(i).io.status := csr.io.status
    mem_units(i).io.bp     := csr.io.bp
  }

  // LSU <> ROB
  rob.io.lsu_clr_bsy    := io.lsu.clr_bsy
  rob.io.lsu_clr_unsafe := io.lsu.clr_unsafe
  rob.io.lxcpt          <> io.lsu.lxcpt

  assert (!(csr.io.singleStep), "[core] single-step is unsupported.")

  rob.io.bxcpt <> br_unit.xcpt

  //-------------------------------------------------------------
  // **** Flush Pipeline ****
  //-------------------------------------------------------------
  // flush on exceptions, miniexeptions, and after some special instructions

  if (usingFPU) {
    fp_pipeline.io.flush_pipeline := rob.io.flush.valid
  }

  for (w <- 0 until exe_units.length) {
    exe_units(w).io.req.bits.kill := rob.io.flush.valid
  }

  assert (!(RegNext(rob.io.com_xcpt.valid) && !rob.io.flush.valid),
    "[core] exception occurred, but pipeline flush signal not set!")

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Outputs to the External World ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // detect pipeline freezes and throw error
  val idle_cycles = freechips.rocketchip.util.WideCounter(32)
  when (rob.io.commit.valids.asUInt.orR ||
        csr.io.csr_stall ||
        io.rocc.busy ||
        reset.asBool) {
    idle_cycles := 0.U
  }
  assert (!(idle_cycles.value(13)), "Pipeline has hung.")

  if (usingFPU) {
    fp_pipeline.io.debug_tsc_reg := debug_tsc_reg
  }

  // enable Dromajo cosimulation
  if (DROMAJO_COSIM_ENABLE) {
    // currently only supports single-core systems
    require(p(BoomTilesKey).size == 1)

    tileParams.asInstanceOf[BoomTileParams].dromajoParams match {
      case Some(params) => {
        val bootromParams = params.bootromParams.get
        val extMemParams = params.extMemParams.get
        val plicParams = params.plicParams.get
        val clintParams = params.clintParams.get

        val resetVectorStr = "0x" + f"${bootromParams.hang}%X"
        val bootromFile = Paths.get(bootromParams.contentFileName).toAbsolutePath.toString
        val mmioStart = "0x" + f"${bootromParams.address + bootromParams.size}%X"
        val mmioEnd = "0x" + f"${extMemParams.master.base}%X"
        val plicBase = "0x" + f"${plicParams.baseAddress}%X"
        val plicSize = "0x" + f"${PLICConsts.size(plicParams.maxHarts)}%X"
        val clintBase = "0x" + f"${clintParams.baseAddress}%X"
        val clintSize = "0x" + f"${CLINTConsts.size}%X"
        val memSize = "0x" + f"${extMemParams.master.size}%X"

        // instantiate dromajo cosim bbox
        val dromajo = Module(new DromajoCosimBlackBox(
          coreWidth,
          xLen,
          bootromFile,
          resetVectorStr,
          mmioStart,
          mmioEnd,
          plicBase,
          plicSize,
          clintBase,
          clintSize,
          memSize))

        def getInst(uop: MicroOp): UInt = {
          Mux(uop.is_rvc, Cat(0.U(16.W), uop.debug_inst(15,0)), uop.debug_inst)
        }

        def getWdata(uop: MicroOp): UInt = {
          Mux((uop.dst_rtype === RT_FIX && uop.ldst =/= 0.U) || (uop.dst_rtype === RT_FLT), uop.debug_wdata, 0.U(xLen.W))
        }

        dromajo.io.clock := clock
        dromajo.io.reset := reset
        dromajo.io.valid := rob.io.commit.valids.asUInt
        dromajo.io.hartid := io.hartid
        dromajo.io.pc     := Cat(rob.io.commit.uops.reverse.map(uop => Sext(uop.debug_pc(vaddrBits-1,0), xLen)))
        dromajo.io.inst   := Cat(rob.io.commit.uops.reverse.map(uop => getInst(uop)))
        dromajo.io.wdata  := Cat(rob.io.commit.uops.reverse.map(uop => getWdata(uop)))
        dromajo.io.mstatus := 0.U // Currently not used in Dromajo
        dromajo.io.check   := ((1 << coreWidth) - 1).U
        dromajo.io.int_xcpt := rob.io.com_xcpt.valid
        dromajo.io.cause    := rob.io.com_xcpt.bits.cause
      }

      case None => throw new java.lang.Exception("Error: No BootROMParams found in BoomTile parameters")
    }
  }

  // TODO: Does anyone want this debugging functionality?
  val coreMonitorBundle = Wire(new CoreMonitorBundle(xLen))
  coreMonitorBundle.clock  := clock
  coreMonitorBundle.reset  := reset
  coreMonitorBundle.hartid := DontCare
  coreMonitorBundle.timer  := DontCare
  coreMonitorBundle.valid  := DontCare
  coreMonitorBundle.pc     := DontCare
  coreMonitorBundle.wrdst  := DontCare
  coreMonitorBundle.wrdata := DontCare
  coreMonitorBundle.wren   := DontCare
  coreMonitorBundle.rd0src := DontCare
  coreMonitorBundle.rd0val := DontCare
  coreMonitorBundle.rd1src := DontCare
  coreMonitorBundle.rd1val := DontCare
  coreMonitorBundle.inst   := DontCare

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Page Table Walker

  io.ptw.ptbr       := csr.io.ptbr
  io.ptw.status     := csr.io.status
  io.ptw.pmp        := csr.io.pmp
  io.ptw.sfence     := io.ifu.sfence

  //-------------------------------------------------------------
  //-------------------------------------------------------------

  io.rocc := DontCare
  io.rocc.exception := csr.io.exception && csr.io.status.xs.orR
  if (usingRoCC) {
    exe_units.rocc_unit.io.rocc.rocc         <> io.rocc
    exe_units.rocc_unit.io.rocc.dis_uops     := dis_uops
    exe_units.rocc_unit.io.rocc.rob_head_idx := rob.io.rob_head_idx
    exe_units.rocc_unit.io.rocc.rob_pnr_idx  := rob.io.rob_pnr_idx
    exe_units.rocc_unit.io.com_exception     := rob.io.flush.valid
    exe_units.rocc_unit.io.status            := csr.io.status

    for (w <- 0 until coreWidth) {
       exe_units.rocc_unit.io.rocc.dis_rocc_vals(w) := (
         dis_fire(w) &&
         dis_uops(w).uopc === uopROCC)
    }
  }

  if (p(BoomTilesKey)(0).trace) {
    for (w <- 0 until coreWidth) {
      io.trace(w).clock      := clock
      io.trace(w).reset      := reset
      io.trace(w).valid      := rob.io.commit.valids(w)
      io.trace(w).iaddr      := Sext(rob.io.commit.uops(w).debug_pc(vaddrBits-1,0), xLen)
      io.trace(w).insn       := rob.io.commit.uops(w).debug_inst
      // These csr signals do not exactly match up with the ROB commit signals.
      io.trace(w).priv       := csr.io.status.prv
      // Can determine if it is an interrupt or not based on the MSB of the cause
      io.trace(w).exception  := rob.io.com_xcpt.valid && !rob.io.com_xcpt.bits.cause(xLen - 1)
      io.trace(w).interrupt  := rob.io.com_xcpt.valid && rob.io.com_xcpt.bits.cause(xLen - 1)
      io.trace(w).cause      := rob.io.com_xcpt.bits.cause
      io.trace(w).tval       := csr.io.tval
    }
    dontTouch(io.trace)
  } else {
    io.trace := DontCare
    io.trace map (t => t.valid := false.B)
  }
}
