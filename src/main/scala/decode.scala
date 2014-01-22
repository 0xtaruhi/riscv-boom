package BOOM
{

import Chisel._
import Node._

import rocket.Instructions._
import FUCode._
import uncore.constants.MemoryOpConstants._

object Decode
{
                        //                                                                             wakeup_delay 
                        //     is val inst?                  rs1 regtype     imm sel                   |   bypassable (aka, known, fixed latency)
                        //     |  micro-opcode               |       rs2 type|                         |   |  is eret
                        //     |  |         func unit        |       |       |    mem    mem           |   |  |  is syscall
                        //     |  |         |        dst     |       |       |     cmd    msk          |   |  |  |  is privileged
                        //     |  |         |        regtype |       |       |     |      |            |   |  |  |  |  is inst unique? (clear pipeline for it)
                        //     |  |         |        |       |       |       |     |      |            |   |  |  |  |  |  flush on commit
   val table =          //List(N, uopNOP  , FU_X   , RT_X  , RT_X  , RT_X  , IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
            Array(        
               LD      -> List(Y, uopLD   , FU_MEM , RT_FIX, RT_FIX, RT_X  , IS_I, M_XRD, MSK_D , UInt(3), N, N, N, N, N, N),
               LW      -> List(Y, uopLD   , FU_MEM , RT_FIX, RT_FIX, RT_X  , IS_I, M_XRD, MSK_W , UInt(3), N, N, N, N, N, N),
               LWU     -> List(Y, uopLD   , FU_MEM , RT_FIX, RT_FIX, RT_X  , IS_I, M_XRD, MSK_WU, UInt(3), N, N, N, N, N, N),
               LH      -> List(Y, uopLD   , FU_MEM , RT_FIX, RT_FIX, RT_X  , IS_I, M_XRD, MSK_H , UInt(3), N, N, N, N, N, N),
               LHU     -> List(Y, uopLD   , FU_MEM , RT_FIX, RT_FIX, RT_X  , IS_I, M_XRD, MSK_HU, UInt(3), N, N, N, N, N, N),
               LB      -> List(Y, uopLD   , FU_MEM , RT_FIX, RT_FIX, RT_X  , IS_I, M_XRD, MSK_B , UInt(3), N, N, N, N, N, N),
               LBU     -> List(Y, uopLD   , FU_MEM , RT_FIX, RT_FIX, RT_X  , IS_I, M_XRD, MSK_BU, UInt(3), N, N, N, N, N, N),
               
               SD      -> List(Y, uopSTA  , FU_MEM , RT_X  , RT_FIX, RT_FIX, IS_S, M_XWR, MSK_D , UInt(0), N, N, N, N, N, N),
               SW      -> List(Y, uopSTA  , FU_MEM , RT_X  , RT_FIX, RT_FIX, IS_S, M_XWR, MSK_W , UInt(0), N, N, N, N, N, N),
               SH      -> List(Y, uopSTA  , FU_MEM , RT_X  , RT_FIX, RT_FIX, IS_S, M_XWR, MSK_H , UInt(0), N, N, N, N, N, N),
               SB      -> List(Y, uopSTA  , FU_MEM , RT_X  , RT_FIX, RT_FIX, IS_S, M_XWR, MSK_B , UInt(0), N, N, N, N, N, N),
               
               LUI     -> List(Y, uopLUI  , FU_ALU , RT_FIX, RT_X  , RT_X  , IS_U, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),

               ADDI    -> List(Y, uopADDI , FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               ANDI    -> List(Y, uopANDI , FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               ORI     -> List(Y, uopORI  , FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               XORI    -> List(Y, uopXORI , FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               SLTI    -> List(Y, uopSLTI , FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               SLTIU   -> List(Y, uopSLTIU, FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               SLLI    -> List(Y, uopSLLI , FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               SRAI    -> List(Y, uopSRAI , FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               SRLI    -> List(Y, uopSRLI , FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               
               ADDIW   -> List(Y, uopADDIW, FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               SLLIW   -> List(Y, uopSLLIW, FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               SRAIW   -> List(Y, uopSRAIW, FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               SRLIW   -> List(Y, uopSRLIW, FU_ALU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),

               SLL     -> List(Y, uopSLL  , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               ADD     -> List(Y, uopADD  , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               SUB     -> List(Y, uopSUB  , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               SLT     -> List(Y, uopSLT  , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               SLTU    -> List(Y, uopSLTU , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               AND     -> List(Y, uopAND  , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               OR      -> List(Y, uopOR   , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               XOR     -> List(Y, uopXOR  , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               SRA     -> List(Y, uopSRA  , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               SRL     -> List(Y, uopSRL  , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               
               ADDW    -> List(Y, uopADDW , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               SUBW    -> List(Y, uopSUBW , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               SLLW    -> List(Y, uopSLLW , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               SRAW    -> List(Y, uopSRAW , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_I, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
               SRLW    -> List(Y, uopSRLW , FU_ALU , RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N),
               
               MUL     -> List(Y, uopMUL  , FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               MULH    -> List(Y, uopMULH , FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               MULHU   -> List(Y, uopMULHU, FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               MULHSU  -> List(Y, uopMULHSU,FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               MULW    -> List(Y, uopMULW , FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),

               DIV     -> List(Y, uopDIV  , FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               DIVU    -> List(Y, uopDIVU , FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               REM     -> List(Y, uopREM  , FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               REMU    -> List(Y, uopREMU , FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               DIVW    -> List(Y, uopDIVW , FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               DIVUW   -> List(Y, uopDIVUW, FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               REMW    -> List(Y, uopREMW , FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               REMUW   -> List(Y, uopREMUW, FU_MULD, RT_FIX, RT_FIX, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),

               JAL     -> List(Y, uopJAL  , FU_BRU , RT_FIX, RT_X  , RT_X  , IS_J, M_N  , MSK_X , UInt(1), N, N, N, N, N, N),
               JALR    -> List(Y, uopJALR , FU_BRU , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(1), N, N, N, N, N, N),
               AUIPC   -> List(Y, uopAUIPC, FU_BRU , RT_FIX, RT_X  , RT_X  , IS_U, M_N  , MSK_X , UInt(1), N, N, N, N, N, N), // use BRU for the PC read
               BEQ     -> List(Y, uopBEQ  , FU_BRU , RT_X  , RT_FIX, RT_FIX, IS_B, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               BNE     -> List(Y, uopBNE  , FU_BRU , RT_X  , RT_FIX, RT_FIX, IS_B, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               BGE     -> List(Y, uopBGE  , FU_BRU , RT_X  , RT_FIX, RT_FIX, IS_B, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               BGEU    -> List(Y, uopBGEU , FU_BRU , RT_X  , RT_FIX, RT_FIX, IS_B, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               BLT     -> List(Y, uopBLT  , FU_BRU , RT_X  , RT_FIX, RT_FIX, IS_B, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               BLTU    -> List(Y, uopBLTU , FU_BRU , RT_X  , RT_FIX, RT_FIX, IS_B, M_N  , MSK_X , UInt(0), N, N, N, N, N, N),
               
               // I-type, the immediate12 holds the CSR register. 
               CSRRW   -> List(Y, uopCSRRW, FU_PCR , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(0), N, N, N, Y, Y, Y), 
               CSRRS   -> List(Y, uopCSRRS, FU_PCR , RT_FIX, RT_FIX, RT_X  , IS_I, M_N  , MSK_X , UInt(0), N, N, N, Y, Y, Y), 
               CSRRWI  -> List(Y, uopCSRRWI,FU_PCR , RT_FIX, RT_X  , RT_X  , IS_I, M_N  , MSK_X , UInt(0), N, N, N, Y, Y, Y), // NOTE: RT_X really means "keep the same value", since we're using RS1 to pass through an immediate

//               MTPCR   -> List(Y, uopMTPCR, FU_PCR , RT_FIX, RT_PCR, RT_FIX, IS_X, M_N  , MSK_X , UInt(0), N, N, N, Y, Y, Y), 
//               MFPCR   -> List(Y, uopMFPCR, FU_PCR , RT_FIX, RT_PCR, RT_X  , IS_X, M_N  , MSK_X , UInt(0), N, N, N, Y, Y, Y), 
//               CLEARPCR-> List(Y, uopCLPCR, FU_PCR , RT_FIX, RT_PCR, RT_X  , IS_I, M_N  , MSK_X , UInt(0), N, N, N, Y, Y, Y), 
//               SETPCR  -> List(Y, uopSTPCR, FU_PCR , RT_FIX, RT_PCR, RT_X  , IS_I, M_N  , MSK_X , UInt(0), N, N, N, Y, Y, Y), 

               // TODO guarantee that these instructions will be monotonic, maybe just make "unique"
//               RDCYCLE  -> List(Y, uopRDC , FU_CNTR, RT_FIX, RT_X  , RT_X  , IS_X, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 
//               RDINSTRET-> List(Y, uopRDI , FU_CNTR, RT_FIX, RT_X  , RT_X  , IS_X, M_N  , MSK_X , UInt(1), Y, N, N, N, N, N), 

               SCALL   -> List(Y, uopNOP  , FU_ALU , RT_X  , RT_X  , RT_X  , IS_X, M_N  , MSK_X , UInt(0), N, N, Y, N, Y, N), 
               SRET    -> List(Y, uopSRET , FU_ALU , RT_X  , RT_X  , RT_X  , IS_X, M_N  , MSK_X , UInt(0), N, Y, N, Y, Y, N), 

               // TODO M_NOP... use ot be M_FENCE, but hellacache no longer does fences?
               FENCE_I -> List(Y, uopFENCEI    ,FU_MEM, RT_X, RT_X, RT_X , IS_X, M_NOP  , MSK_X , UInt(0), N, N, N, N, Y, Y), 
               FENCE   -> List(Y, uopMEMSPECIAL,FU_MEM, RT_X, RT_X, RT_X , IS_X, M_NOP  , MSK_X , UInt(0), N, N, N, N, N, N)
               )
                 

}


class DecodeUnitIo extends Bundle
{
   val enq = new Bundle
   {
      val inst  = Bits(width = XPRLEN)
   }.asInput

   val deq = new Bundle
   {
      val valid = Bool() // not valid if we are getting stalled
      val uop   = new MicroOp()
      val ready = Bool() // we may be busy writing out multiple micro-ops per macro-inst or waiting on ROB to empty
   }.asOutput

   val status    = new rocket.Status().asInput
}

// Takes in a single instruction, generates a MicroOp (or multiply micro-ops over x cycles)
class DecodeUnit extends Module
{
   val io = new DecodeUnitIo

   val uop = new MicroOp()
   uop.inst := io.enq.inst

   val dec_csignals = ListLookup(uop.inst, 
                                 List(N, uopNOP, FU_X, RT_X, RT_X, RT_X, IS_X, M_N, MSK_X, UInt(0), N, N, N, N, N, N),
                                 Decode.table)
                                   
   val cs_inst_val :: cs_uopc :: cs_fu_code :: cs_dst_type :: cs_rs1_type :: cs_rs2_type :: cs_imm_sel :: cs_mem_cmd :: cs_mem_typ :: cs_wakeup_delay :: cs_bypassable :: cs_eret :: cs_syscall :: cs_privileged :: cs_inst_unique :: cs_flush_on_commit :: Nil = dec_csignals
   

   // Exception Handling
   val exc_illegal = !cs_inst_val 
   val exc_priv    = cs_privileged.toBool && !(io.status.s)

   uop.eret      := cs_eret.toBool
   uop.syscall   := cs_syscall.toBool
   
   uop.exception := cs_syscall.toBool  ||
                       exc_illegal ||
                       exc_priv
                                             
   uop.exc_cause := Mux(exc_illegal,           UInt(rocket.Causes.illegal_instruction),
                       Mux(exc_priv,           UInt(rocket.Causes.privileged_instruction),
                       Mux(cs_syscall.toBool,  UInt(rocket.Causes.syscall),
                                               UInt(0,5))))
   
   //-------------------------------------------------------------
    
   uop.uopc       := cs_uopc
   uop.fu_code    := cs_fu_code
     
   uop.ldst       := uop.inst(RD_MSB,RD_LSB).toUInt
   uop.lrs1       := uop.inst(RS1_MSB,RS1_LSB).toUInt
   uop.lrs2       := uop.inst(RS2_MSB,RS2_LSB).toUInt

   uop.ldst_val   := (cs_dst_type != RT_X && (uop.ldst != UInt(0)))
   uop.ldst_rtype := cs_dst_type
   uop.lrs1_rtype := cs_rs1_type
   uop.lrs2_rtype := cs_rs2_type

   uop.mem_cmd    := cs_mem_cmd.toUInt
   uop.mem_typ    := cs_mem_typ
   uop.is_load    := cs_uopc === uopLD
   uop.is_store   := uop.uopc === uopSTA || uop.uopc === uopMEMSPECIAL || uop.uopc === uopFENCEI
   uop.is_fence   := uop.uopc === uopMEMSPECIAL || uop.uopc === uopFENCEI // TODO just make fence a bit in the ctrl table
   uop.is_unique  := cs_inst_unique.toBool
   uop.flush_on_commit := cs_flush_on_commit.toBool
   //treat fences, flushes as "stores that write to all addresses"
   
   uop.wakeup_delay := cs_wakeup_delay
   uop.bypassable   := cs_bypassable.toBool
  
   //-------------------------------------------------------------
   // immediates

   // repackage the immediate, and then pass the fewest number of bits around
   val di24_20 = Mux(cs_imm_sel === IS_B || cs_imm_sel === IS_S, uop.inst(11,7), uop.inst(24,20))
   uop.imm_packed := Cat(uop.inst(31,25), di24_20, uop.inst(19,12))

   //-------------------------------------------------------------
   // TODO move this to the decode table, rename to "allocate_br_mask"?
   uop.is_br_or_jmp := (uop.uopc === uopBEQ)  || 
                 (uop.uopc === uopBNE)  ||
                 (uop.uopc === uopBGE)  ||
                 (uop.uopc === uopBGEU) ||
                 (uop.uopc === uopBLT)  ||
                 (uop.uopc === uopBLTU) ||
                 (uop.uopc === uopJAL)  ||
                 (uop.uopc === uopJALR)

   uop.is_jump:= (uop.uopc === uopJAL) ||
                 (uop.uopc === uopJALR) 
   uop.is_ret := (uop.uopc === uopJALR) &&
                 (uop.ldst === X0) &&
                 (uop.lrs1 === RA)


   //-------------------------------------------------------------

   io.deq.uop   := uop

   //-------------------------------------------------------------

}


class BranchDecode extends Module
{
   val io = new Bundle
   {
      val inst    = Bits(INPUT, 32)

      val is_br   = Bool(OUTPUT)
      val brtype  = Bits(OUTPUT, UOPC_SZ)
      val imm_sel = UInt(OUTPUT, IS_X.getWidth)
   }
     
   val bpd_csignals =
      ListLookup(io.inst,
                          List(uopNOP  , Bool(false), IS_X),
            Array(        /*           | is       | Br   */
                          /*           |  Br?     | Type */
               JAL     -> List(uopJAL  , Bool(true), IS_J),
               JALR    -> List(uopJALR , Bool(true), IS_I),
               BEQ     -> List(uopBEQ  , Bool(true), IS_B),
               BNE     -> List(uopBNE  , Bool(true), IS_B),
               BGE     -> List(uopBGE  , Bool(true), IS_B),
               BGEU    -> List(uopBGEU , Bool(true), IS_B),
               BLT     -> List(uopBLT  , Bool(true), IS_B),
               BLTU    -> List(uopBLTU , Bool(true), IS_B)
//               ERET    -> List(uopERET , Bool(true), IS_X)
            ))

   val bpd_brtype_ :: bpd_br_val :: bpd_imm_sel_ :: Nil = bpd_csignals

   io.is_br   := bpd_br_val.toBool
   io.brtype  := bpd_brtype_.toBits
   io.imm_sel := bpd_imm_sel_
}


class FetchSerializerIO(implicit conf: BOOMConfiguration) extends Bundle
{
   val enq = new DecoupledIO(new FetchBundle()).flip
   val deq = new DecoupledIO(Vec.fill(DECODE_WIDTH){new MicroOp()}) 
      
//   val stall = Bits(INPUT, DECODE_WIDTH)

   val kill = Bool(INPUT) 

  override def clone = new FetchSerializerIO().asInstanceOf[this.type]
}



// TODO horrific hodgepodge, needs refactoring
// connect a N-word wide Fetch Buffer with a M-word decode
// currently only works for 2 wide fetch to 1 wide decode, OR N:N fetch/decode
// TODO instead of counter, clear mask bits as instructions are finished?
class FetchSerializerNtoM(implicit conf: BOOMConfiguration) extends Module
{
   val io = new FetchSerializerIO

   val counter = Reg(init = UInt(0, log2Up(FETCH_WIDTH)))
   val inst_idx = UInt()
   inst_idx := UInt(0)

   //-------------------------------------------------------------
   // Compute index for where to get the instruction
   when (counter === UInt(1))
   {
      inst_idx := UInt(1)
   }
   .otherwise
   {
      inst_idx := Mux(io.enq.bits.mask === Bits(2), UInt(1), UInt(0))
   }

   //-------------------------------------------------------------
   // Compute Enqueue Ready (get the next bundle)
   io.enq.ready := io.deq.ready && 
                     (io.enq.bits.mask != Bits(3) || (counter === UInt(1)))


   //-------------------------------------------------------------
   // Compute Counter
   when (io.kill || io.enq.ready)
   {
      // reset counter on every new bundle
      counter := UInt(0)
   }
   .elsewhen (io.deq.valid && io.deq.ready)
   {
      counter := counter + UInt(1)
   }

   
   //-------------------------------------------------------------
   // override all the above logic for FW==1
   if (FETCH_WIDTH == 1)
   {
      inst_idx := UInt(0)
      io.enq.ready := io.deq.ready 
   }

   io.deq.bits(0).pc             := io.enq.bits.pc + Mux(inst_idx.orR,UInt(4),UInt(0))
   io.deq.bits(0).inst           := io.enq.bits.insts(inst_idx)
   io.deq.bits(0).br_prediction  := io.enq.bits.br_predictions(inst_idx)
   io.deq.bits(0).btb_pred_taken := io.enq.bits.btb_pred_taken
   io.deq.bits(0).valid          := io.enq.bits.mask(0)



   //-------------------------------------------------------------
   // override all the above logic for DW>1
   // assume FW is also DW, and pass everything through
   if ((DECODE_WIDTH == FETCH_WIDTH) && (FETCH_WIDTH > 1))
   {
      // 1:1, so pass everything straight through!
      for (i <- 0 until DECODE_WIDTH)
      {
         io.deq.bits(i).pc := io.enq.bits.pc + UInt(i << 2)
         io.deq.bits(i).inst := io.enq.bits.insts(i)
         io.deq.bits(i).br_prediction  := io.enq.bits.br_predictions(i)
         io.deq.bits(i).btb_pred_taken := Mux(io.enq.bits.btb_pred_taken_idx === UInt(i), 
                                                             io.enq.bits.btb_pred_taken, 
                                                             Bool(false))
         io.deq.bits(i).valid := io.enq.bits.mask(i)
      }

      io.enq.ready := io.deq.ready
   }

   // Pipe valid straight through, since conceptually, 
   // we are just an extension of the Fetch Buffer
   io.deq.valid := io.enq.valid
 
}


// track the current "branch mask", and give out the branch mask to each micro-op in Decode
// (each micro-op in the machine has a branch mask which says which branches it
// is being speculated under. 
class BranchMaskGenerationLogic(val pl_width: Int) extends Module
{
   val io = new Bundle
   {
      val will_fire = Vec.fill(pl_width) { Bool(INPUT) }
      val is_branch = Vec.fill(pl_width) { Bool(INPUT) }

      val br_tag    = Vec.fill(pl_width) { UInt(OUTPUT, BR_TAG_SZ) }
      val br_mask   = Vec.fill(pl_width) { Bits(OUTPUT, MAX_BR_COUNT) }


      val is_full   = Vec.fill(pl_width) { Bool(OUTPUT) } // tell decoders the branch
//      val is_full   = Bits(OUTPUT, MAX_BR_COUNT) // tell decoders the branch
                                                 // mask has filled up, but on
                                                 // the granularity of an
                                                 // individual micro-op (so
                                                 // some micro-ops can go
                                                 // through)
      
      val brinfo         = new BrResolutionInfo().asInput
      val flush_pipeline = Bool(INPUT)
   }

   val branch_mask       = Reg(init = Bits(0, MAX_BR_COUNT))

   //-------------------------------------------------------------
   // Give out the branch mask and branch tag to each micro-op

   var curr_br_mask = branch_mask 

   for (w <- 0 until pl_width)
   {
      io.is_full(w) := (curr_br_mask === ~(Bits(0,MAX_BR_COUNT))) && io.is_branch(w) 
      io.br_mask(w) := GetNewBrMask(io.brinfo, curr_br_mask)
       

      // find br_tag and compute next br_mask
      val new_br_mask = Bits(width = MAX_BR_COUNT)
      new_br_mask := curr_br_mask
      val new_br_tag = UInt(width = BR_TAG_SZ)
      new_br_tag := UInt(0)

      for (i <- MAX_BR_COUNT-1 to 0 by -1)
      {
         when (~curr_br_mask(i))
         {
            new_br_mask := (UInt(1) << UInt(i)) | curr_br_mask
            new_br_tag  := UInt(i)
         }
      }

      io.br_tag(w)  := new_br_tag

      curr_br_mask = Mux(io.is_branch(w) && io.will_fire(w), new_br_mask 
                                                           , curr_br_mask)
   }

   //-------------------------------------------------------------
   // Update the current branch_mask

   when (io.flush_pipeline)
   {
      branch_mask := Bits(0)
   }
   .elsewhen (io.brinfo.valid && io.brinfo.mispredict)
   {
      branch_mask := io.brinfo.exe_mask
   }
   .otherwise
   {
      branch_mask := GetNewBrMask(io.brinfo, curr_br_mask)
   }

}

}

