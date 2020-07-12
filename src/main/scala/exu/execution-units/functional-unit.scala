//******************************************************************************
// Copyright (c) 2013 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Functional Units
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// If regfile bypassing is disabled, then the functional unit must do its own
// bypassing in here on the WB stage (i.e., bypassing the io.resp.data)
//
// TODO: explore possibility of conditional IO fields? if a branch unit... how to add extra to IO in subclass?

package boom.exu

import chisel3._
import chisel3.util._
import chisel3.experimental.chiselName

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.rocket.ALU._
import freechips.rocketchip.util._
import freechips.rocketchip.tile
import freechips.rocketchip.rocket.{PipelinedMultiplier,BP,BreakpointUnit,Causes,CSR}

import boom.common._
import boom.ifu._
import boom.util._

/**t
 * Functional unit constants
 */
object FUConstants
{
  // bit mask, since a given execution pipeline may support multiple functional units
  val FUC_SZ = 11
  val FU_X   = BitPat.dontCare(FUC_SZ)
  val FU_ALU =   1.U(FUC_SZ.W)
  val FU_JMP =   2.U(FUC_SZ.W)
  val FU_AGEN=   4.U(FUC_SZ.W)
  val FU_DGEN=   8.U(FUC_SZ.W)
  val FU_MUL =  16.U(FUC_SZ.W)
  val FU_DIV =  32.U(FUC_SZ.W)
  val FU_CSR =  64.U(FUC_SZ.W)
  val FU_FPU = 128.U(FUC_SZ.W)
  val FU_FDV = 256.U(FUC_SZ.W)
  val FU_I2F = 512.U(FUC_SZ.W)
  val FU_F2I =1024.U(FUC_SZ.W)

  // FP stores generate data through FP F2I, and generate address through MemAddrCalc
  val FU_F2IMEM = 1028.U(FUC_SZ.W)

  val FU_STORE  = 12.U(FUC_SZ.W)
}
import FUConstants._



/**
 * Bundle for signals sent to the functional unit
 *
 * @param dataWidth width of the data sent to the functional unit
 */
class FuncUnitReq(val dataWidth: Int)(implicit p: Parameters) extends BoomBundle
  with HasBoomUOP
{
  val numOperands = 3

  val rs1_data = UInt(dataWidth.W)
  val rs2_data = UInt(dataWidth.W)
  val rs3_data = UInt(dataWidth.W) // only used for FMA units
  val pred_data = Bool()
}
/**
 * Branch resolution information given from the branch unit
 */
class BrResolutionInfo(implicit p: Parameters) extends BoomBundle with HasBoomUOP
{
  val mispredict = Bool()
  val taken      = Bool()                     // which direction did the branch go?
  val cfi_type   = UInt(CFI_SZ.W)

  // Info for recalculating the pc for this branch
  val pc_sel     = UInt(2.W)

  val jalr_target = UInt(vaddrBitsExtended.W)
  val target_offset = SInt(21.W)
}

class BrUpdateInfo(implicit p: Parameters) extends BoomBundle
{
  // On the first cycle we get masks to kill registers
  val b1 = new BrUpdateMasks
  // On the second cycle we get indices to reset pointers
  val b2 = new BrResolutionInfo
}

class BrUpdateMasks(implicit p: Parameters) extends BoomBundle
{
  val resolve_mask = UInt(maxBrCount.W)
  val mispredict_mask = UInt(maxBrCount.W)
}


/**
 * Abstract top level functional unit class that wraps a lower level hand made functional unit
 *
 * @param isPipelined is the functional unit pipelined?
 * @param numStages how many pipeline stages does the functional unit have
 * @param numBypassStages how many bypass stages does the function unit have
 * @param dataWidth width of the data being operated on in the functional unit
 * @param hasBranchUnit does this functional unit have a branch unit?
 */
abstract class FunctionalUnit(
  val numBypassStages: Int,
  val dataWidth: Int,
  val isJmpUnit: Boolean = false,
  val isAluUnit: Boolean = false,
  val needsFcsr: Boolean = false)
  (implicit p: Parameters) extends BoomModule
{
  val io = IO(new Bundle {
    val kill   = Input(Bool())

    val req    = Flipped(new DecoupledIO(new FuncUnitReq(dataWidth)))
    val resp   = (new DecoupledIO(new ExeUnitResp(dataWidth)))
    val fflags = new ValidIO(new FFlagsResp)

    val brupdate = Input(new BrUpdateInfo())

    val bypass = Output(Vec(numBypassStages, Valid(new ExeUnitResp(dataWidth))))

    // only used by the fpu unit
    val fcsr_rm = if (needsFcsr) Input(UInt(tile.FPConstants.RM_SZ.W)) else null

    // only used by branch unit
    val brinfo     = if (isAluUnit) Output(Valid(new BrResolutionInfo)) else null
    val get_ftq_pc = if (isJmpUnit) Flipped(new GetPCFromFtqIO()) else null
  })
}

/**
 * Functional unit that wraps RocketChips ALU
 *
 * @param isBranchUnit is this a branch unit?
 * @param numStages how many pipeline stages does the functional unit have
 * @param dataWidth width of the data being operated on in the functional unit
 */
@chiselName
class ALUUnit(isJmpUnit: Boolean = false, numStages: Int = 0, dataWidth: Int)(implicit p: Parameters)
  extends FunctionalUnit(
    numBypassStages = numStages,
    isAluUnit = true,
    dataWidth = dataWidth,
    isJmpUnit = isJmpUnit)
  with boom.ifu.HasBoomFrontendParameters
  with freechips.rocketchip.rocket.constants.ScalarOpConstants
{
  val uop = io.req.bits.uop

  // immediate generation
  val imm_xprlen = ImmGen(uop.imm_packed, uop.imm_sel)

  // operand 1 select
  var op1_data: UInt = null
  if (isJmpUnit) {
    // Get the uop PC for jumps
    val block_pc = AlignPCToBoundary(io.get_ftq_pc.pc, icBlockBytes)
    val uop_pc = (block_pc | uop.pc_lob) - Mux(uop.edge_inst, 2.U, 0.U)

    op1_data = Mux(uop.op1_sel === OP1_RS1 , io.req.bits.rs1_data,
               Mux(uop.op1_sel === OP1_PC  , Sext(uop_pc, xLen),
                                             0.U))
  } else {
    op1_data = Mux(uop.op1_sel === OP1_RS1 , io.req.bits.rs1_data,
                                             0.U)
  }

  // operand 2 select
  val op2_data = Mux(uop.op2_sel === OP2_IMM,  Sext(imm_xprlen.asUInt, xLen),
                 Mux(uop.op2_sel === OP2_IMMC, io.req.bits.uop.prs1(4,0),
                 Mux(uop.op2_sel === OP2_RS2 , io.req.bits.rs2_data,
                 Mux(uop.op2_sel === OP2_NEXT, Mux(uop.is_rvc, 2.U, 4.U),
                                               0.U))))

  val alu = Module(new freechips.rocketchip.rocket.ALU())

  alu.io.in1 := op1_data.asUInt
  alu.io.in2 := op2_data.asUInt
  alu.io.fn  := uop.fcn_op
  alu.io.dw  := uop.fcn_dw


  val rs1 = io.req.bits.rs1_data
  val rs2 = io.req.bits.rs2_data
  val br_eq  = (rs1 === rs2)
  val br_ltu = (rs1.asUInt < rs2.asUInt)
  val br_lt  = (~(rs1(xLen-1) ^ rs2(xLen-1)) & br_ltu |
                rs1(xLen-1) & ~rs2(xLen-1)).asBool

  val pc_sel = MuxLookup(uop.br_type, PC_PLUS4,
                 Seq(   B_N   -> PC_PLUS4,
                        B_NE  -> Mux(!br_eq,  PC_BRJMP, PC_PLUS4),
                        B_EQ  -> Mux( br_eq,  PC_BRJMP, PC_PLUS4),
                        B_GE  -> Mux(!br_lt,  PC_BRJMP, PC_PLUS4),
                        B_GEU -> Mux(!br_ltu, PC_BRJMP, PC_PLUS4),
                        B_LT  -> Mux( br_lt,  PC_BRJMP, PC_PLUS4),
                        B_LTU -> Mux( br_ltu, PC_BRJMP, PC_PLUS4),
                        B_J   -> PC_BRJMP,
                        B_JR  -> PC_JALR
                        ))

  val is_taken = io.req.valid &&
                   (uop.is_br || uop.is_jalr || uop.is_jal) &&
                   (pc_sel =/= PC_PLUS4)

  // "mispredict" means that a branch has been resolved and it must be killed
  val mispredict = WireInit(false.B)

  val is_br          = io.req.valid && uop.is_br && !uop.is_sfb
  val is_jal         = io.req.valid && uop.is_jal
  val is_jalr        = io.req.valid && uop.is_jalr

  when (is_br || is_jalr) {
    if (!isJmpUnit) {
      assert (pc_sel =/= PC_JALR)
    }
    when (pc_sel === PC_PLUS4) {
      mispredict := uop.taken
    }
    when (pc_sel === PC_BRJMP) {
      mispredict := !uop.taken
    }
  }

  val brinfo = Wire(Valid(new BrResolutionInfo))

  // note: jal doesn't allocate a branch-mask, so don't clear a br-mask bit
  brinfo.valid          := is_br || is_jalr
  brinfo.bits.mispredict     := mispredict
  brinfo.bits.uop            := uop
  brinfo.bits.cfi_type       := Mux(is_jalr, CFI_JALR,
                           Mux(is_br  , CFI_BR, CFI_X))
  brinfo.bits.taken          := is_taken
  brinfo.bits.pc_sel         := pc_sel

  brinfo.bits.jalr_target    := DontCare


  // Branch/Jump Target Calculation
  // For jumps we read the FTQ, and can calculate the target
  // For branches we emit the offset for the core to redirect if necessary
  val target_offset = imm_xprlen(20,0).asSInt
  brinfo.bits.jalr_target := DontCare
  if (isJmpUnit) {
    def encodeVirtualAddress(a0: UInt, ea: UInt) = if (vaddrBitsExtended == vaddrBits) {
      ea
    } else {
      // Efficient means to compress 64-bit VA into vaddrBits+1 bits.
      // (VA is bad if VA(vaddrBits) != VA(vaddrBits-1)).
      val a = a0.asSInt >> vaddrBits
      val msb = Mux(a === 0.S || a === -1.S, ea(vaddrBits), !ea(vaddrBits-1))
      Cat(msb, ea(vaddrBits-1,0))
    }


    val jalr_target_base = io.req.bits.rs1_data.asSInt
    val jalr_target_xlen = Wire(UInt(xLen.W))
    jalr_target_xlen := (jalr_target_base + target_offset).asUInt
    val jalr_target = (encodeVirtualAddress(jalr_target_xlen, jalr_target_xlen).asSInt & -2.S).asUInt

    brinfo.bits.jalr_target := jalr_target
    val cfi_idx = ((uop.pc_lob ^ Mux(io.get_ftq_pc.entry.start_bank === 1.U, 1.U << log2Ceil(bankBytes), 0.U)))(log2Ceil(fetchWidth),1)

    when (pc_sel === PC_JALR) {
      mispredict := !io.get_ftq_pc.next_val ||
                    (io.get_ftq_pc.next_pc =/= jalr_target) ||
                    !io.get_ftq_pc.entry.cfi_idx.valid ||
                    (io.get_ftq_pc.entry.cfi_idx.bits =/= cfi_idx)
    }
  }

  brinfo.bits.target_offset := target_offset


  io.brinfo := brinfo



// Response
// TODO add clock gate on resp bits from functional units
//   io.resp.bits.data := RegEnable(alu.io.out, io.req.valid)
//   val reg_data = Reg(outType = Bits(width = xLen))
//   reg_data := alu.io.out
//   io.resp.bits.data := reg_data
  val alu_out = Mux(io.req.bits.uop.is_sfb_shadow && io.req.bits.pred_data,
      Mux(io.req.bits.uop.ldst_is_rs1, io.req.bits.rs1_data, io.req.bits.rs2_data),
      Mux(io.req.bits.uop.is_mov, io.req.bits.rs2_data, alu.io.out))
  if (numStages == 0) {
    require (numBypassStages == 0)
    io.resp.valid := io.req.valid
    io.resp.bits.uop := io.req.bits.uop
    io.resp.bits.data := Mux(io.req.bits.uop.is_sfb_br, pc_sel === PC_BRJMP, alu_out)
    io.resp.bits.predicated := io.req.bits.uop.is_sfb_shadow && io.req.bits.pred_data
  } else {
    require(numStages == numBypassStages)

    val pipe = Module(new BranchKillablePipeline(new ExeUnitResp(xLen), numStages))
    pipe.io.req.valid           := io.req.valid
    pipe.io.req.bits.uop        := io.req.bits.uop
    pipe.io.req.bits.data       := Mux(io.req.bits.uop.is_sfb_br, pc_sel === PC_BRJMP, alu_out)
    pipe.io.req.bits.predicated := io.req.bits.uop.is_sfb_shadow && io.req.bits.pred_data

    pipe.io.brupdate := io.brupdate
    pipe.io.flush := io.kill

    io.bypass(0) := pipe.io.req
    for (i <- 0 until numStages-1) {
      io.bypass(i+1) := pipe.io.resp(i)
    }
    io.resp.valid := pipe.io.resp(numStages-1).valid
    io.resp.bits  := pipe.io.resp(numStages-1).bits
  }

  // Exceptions
  io.fflags.valid := false.B
}



/**
 * Functional unit to wrap lower level FPU
 *
 * Currently, bypassing is unsupported!
 * All FP instructions are padded out to the max latency unit for easy
 * write-port scheduling.
 */
class FPUUnit(implicit p: Parameters)
  extends FunctionalUnit(
    numBypassStages = 0,
    dataWidth = 65,
    needsFcsr = true)
{
  val numStages = p(tile.TileKey).core.fpu.get.dfmaLatency

  val pipe = Module(new BranchKillablePipeline(new FuncUnitReq(dataWidth), numStages))
  pipe.io.req := io.req
  pipe.io.flush := io.kill
  pipe.io.brupdate := io.brupdate
  val fpu = Module(new FPU())
  fpu.io.req.valid         := io.req.valid
  fpu.io.req.bits.uop      := io.req.bits.uop
  fpu.io.req.bits.rs1_data := io.req.bits.rs1_data
  fpu.io.req.bits.rs2_data := io.req.bits.rs2_data
  fpu.io.req.bits.rs3_data := io.req.bits.rs3_data
  fpu.io.req.bits.fcsr_rm  := io.fcsr_rm

  io.resp.valid        := pipe.io.resp(numStages-1).valid
  io.resp.bits.uop     := pipe.io.resp(numStages-1).bits.uop
  io.resp.bits.data    := fpu.io.resp.bits.data

  io.fflags.valid      := fpu.io.fflags.valid && io.resp.valid
  io.fflags.bits       := fpu.io.fflags.bits
  io.fflags.bits.uop   := io.resp.bits.uop
}

/**
 * Int to FP conversion functional unit
 *
 * @param latency the amount of stages to delay by
 */
class IntToFPUnit(latency: Int)(implicit p: Parameters)
  extends FunctionalUnit(
    numBypassStages = 0,
    dataWidth = 65,
    needsFcsr = true)
  with tile.HasFPUParameters
{
  val pipe = Module(new BranchKillablePipeline(new FuncUnitReq(dataWidth), latency))
  pipe.io.req := io.req
  pipe.io.flush := io.kill
  pipe.io.brupdate := io.brupdate
  val fp_decoder = Module(new UOPCodeFPUDecoder) // TODO use a simpler decoder
  val io_req = io.req.bits
  fp_decoder.io.uopc := io_req.uop.uopc
  val fp_ctrl = fp_decoder.io.sigs
  val fp_rm = Mux(ImmGenRm(io_req.uop.imm_packed) === 7.U, io.fcsr_rm, ImmGenRm(io_req.uop.imm_packed))
  val req = Wire(new tile.FPInput)
  val tag = !fp_ctrl.singleIn

  req <> fp_ctrl

  req.rm := fp_rm
  req.in1 := unbox(io_req.rs1_data, tag, None)
  req.in2 := unbox(io_req.rs2_data, tag, None)
  req.in3 := DontCare
  req.typ := ImmGenTyp(io_req.uop.imm_packed)
  req.fmaCmd := DontCare

  assert (!(io.req.valid && fp_ctrl.fromint && req.in1(xLen).asBool),
    "[func] IntToFP integer input has 65th high-order bit set!")

  assert (!(io.req.valid && !fp_ctrl.fromint),
    "[func] Only support fromInt micro-ops.")

  val ifpu = Module(new tile.IntToFP(intToFpLatency))
  ifpu.io.in.valid := io.req.valid
  ifpu.io.in.bits := req
  ifpu.io.in.bits.in1 := io_req.rs1_data
  val out_double = Pipe(io.req.valid, !fp_ctrl.singleOut, intToFpLatency).bits

  io.resp.valid        := pipe.io.resp(latency-1).valid
  io.resp.bits.uop     := pipe.io.resp(latency-1).bits.uop
  io.resp.bits.data    := box(ifpu.io.out.bits.data, out_double)
  io.fflags.valid      := io.resp.valid
  io.fflags.bits.uop   := io.resp.bits.uop
  io.fflags.bits.flags := ifpu.io.out.bits.exc
}

/**
 * Divide functional unit.
 *
 * @param dataWidth data to be passed into the functional unit
 */
class DivUnit(dataWidth: Int)(implicit p: Parameters)
  extends FunctionalUnit(numBypassStages = 0, dataWidth = dataWidth)
{

  // We don't use the iterative multiply functionality here.
  // Instead we use the PipelinedMultiplier
  val div = Module(new freechips.rocketchip.rocket.MulDiv(mulDivParams, width = dataWidth))

  val req = Reg(Valid(new MicroOp()))

  when (io.req.fire()) {
    req.valid := !IsKilledByBranch(io.brupdate, io.req.bits) && !io.kill
    req.bits  := UpdateBrMask(io.brupdate, io.req.bits.uop)
  } .otherwise {
    req.valid := !IsKilledByBranch(io.brupdate, req.bits) && !io.kill && req.valid
    req.bits  := UpdateBrMask(io.brupdate, req.bits)
  }
  when (reset.asBool) {
    req.valid := false.B
  }

  // request
  div.io.req.valid    := io.req.valid && !IsKilledByBranch(io.brupdate, io.req.bits) && !io.kill
  div.io.req.bits.dw  := io.req.bits.uop.fcn_dw
  div.io.req.bits.fn  := io.req.bits.uop.fcn_op
  div.io.req.bits.in1 := io.req.bits.rs1_data
  div.io.req.bits.in2 := io.req.bits.rs2_data
  div.io.req.bits.tag := DontCare
  io.req.ready        := div.io.req.ready && !req.valid

  // handle pipeline kills and branch misspeculations
  div.io.kill         := (req.valid && IsKilledByBranch(io.brupdate, req.bits)) || io.kill

  // response
  io.resp.valid       := div.io.resp.valid && req.valid
  div.io.resp.ready   := io.resp.ready
  io.resp.valid       := div.io.resp.valid && req.valid
  io.resp.bits.data   := div.io.resp.bits.data
  io.resp.bits.uop    := req.bits
  when (io.resp.fire()) {
    req.valid := false.B
  }
}

/**
 * Pipelined multiplier functional unit that wraps around the RocketChip pipelined multiplier
 *
 * @param numStages number of pipeline stages
 * @param dataWidth size of the data being passed into the functional unit
 */
class PipelinedMulUnit(numStages: Int, dataWidth: Int)(implicit p: Parameters)
  extends FunctionalUnit(numBypassStages = 0, dataWidth = dataWidth)
{
  val imul = Module(new PipelinedMultiplier(xLen, numStages))
  val pipe = Module(new BranchKillablePipeline(new FuncUnitReq(dataWidth), numStages))
  // request
  imul.io.req.valid    := io.req.valid
  imul.io.req.bits.fn  := io.req.bits.uop.fcn_op
  imul.io.req.bits.dw  := io.req.bits.uop.fcn_dw
  imul.io.req.bits.in1 := io.req.bits.rs1_data
  imul.io.req.bits.in2 := io.req.bits.rs2_data
  imul.io.req.bits.tag := DontCare

  pipe.io.req          := io.req
  pipe.io.flush        := io.kill
  pipe.io.brupdate     := io.brupdate
  // response
  io.resp.valid        := pipe.io.resp(numStages-1).valid
  io.resp.bits.uop     := pipe.io.resp(numStages-1).bits.uop
  io.resp.bits.data    := imul.io.resp.bits.data
  io.resp.bits.predicated := false.B
}
