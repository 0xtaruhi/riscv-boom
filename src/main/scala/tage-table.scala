//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// TAGE Table (used by the TAGE branch predictor)
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Christopher Celio
// 2016 Feb 26



package boom

import Chisel._
import cde.Parameters

import util.Str


class TageTableIo(
   fetch_width: Int,
   num_entries: Int,
   history_length: Int,
   tag_sz: Int,
   counter_sz: Int,
   this_index_sz: Int
   )(implicit p: Parameters) extends BoomBundle()(p)
{
   private val index_sz = log2Up(num_entries)

   // instruction fetch - request prediction
   val if_req_pc = UInt(INPUT, width = xLen)
   // TODO XXX remove this, as it is unused
   val if_req_history = UInt(INPUT, width = history_length)

   // bp2 - send prediction to bpd pipeline
   val bp2_resp = new DecoupledIO(new TageTableResp(fetch_width, history_length, log2Up(num_entries), tag_sz))

   // bp2 - update histories speculatively
   val bp2_update_history = (new ValidIO(new GHistUpdate)).flip
   // TODO: this is painfully special-cased -- move this into an update_csr bundle?
   val bp2_update_csr_evict_bit = Bool(INPUT)

   // commit - update predictor tables (allocate entry)
   val allocate = (new ValidIO(new TageAllocateEntryInfo(fetch_width, index_sz, tag_sz, history_length))).flip
   def AllocateNewEntry(idx: UInt, tag: UInt, executed: UInt, taken: UInt, debug_pc: UInt, debug_hist_ptr: UInt) =
   {
      this.allocate.valid := Bool(true)
      this.allocate.bits.index := idx
      this.allocate.bits.tag :=tag
      this.allocate.bits.executed :=executed
      this.allocate.bits.taken :=taken
      this.allocate.bits.debug_pc := debug_pc
      this.allocate.bits.debug_hist_ptr :=debug_hist_ptr
   }

   // commit - update predictor tables (update counters)
   val update_counters = (new ValidIO(new TageUpdateCountersInfo(fetch_width, index_sz))).flip
   def UpdateCounters(idx: UInt, executed: UInt, taken: UInt) =
   {
      this.update_counters.valid := Bool(true)
      this.update_counters.bits.index := idx
      this.update_counters.bits.executed := executed
      this.update_counters.bits.taken := taken
   }

   // commit - update predictor tables (update u-bits)
   val update_usefulness = (new ValidIO(new TageUpdateUsefulInfo(index_sz))).flip
   def UpdateUsefulness(idx: UInt, inc: Bool) =
   {
      this.update_usefulness.valid := Bool(true)
      this.update_usefulness.bits.index := idx
      this.update_usefulness.bits.inc := inc
   }

   val usefulness_req_idx = UInt(INPUT, index_sz)
   val usefulness_resp = UInt(OUTPUT, 2) // TODO u-bit_sz
   def GetUsefulness(idx: UInt, idx_sz: Int) =
   {
//      this.usefulness_req_idx := idx(this_index_sz-1,0) // TODO CODEREVIEW
      this.usefulness_req_idx := idx(idx_sz-1,0) // TODO CODEREVIEW
      this.usefulness_resp
   }


   // BP2 - speculatively update the spec copy of the CSRs (branch history registers)
//   val spec_csr_update = Valid(new CircularShiftRegisterUpdate).flip
   // Commit - update the commit copy of the CSRs (branch history registers)
   val commit_csr_update = Valid(new CircularShiftRegisterUpdate).flip
   val debug_ghistory_commit_copy= UInt(INPUT, history_length) // TODO REMOVE for debug

   // branch resolution comes from the branch-unit, during the Execute stage.
   val br_resolution = Valid(new BpdUpdate).flip
   // reset CSRs to commit copies during pipeline flush
   val flush = Bool(INPUT)

   def InitializeIo(dummy: Int=0) =
   {
      this.allocate.valid := Bool(false)
      this.update_counters.valid := Bool(false)
      this.update_usefulness.valid := Bool(false)
      this.allocate.bits.index := UInt(0)
      this.allocate.bits.tag := UInt(0)
      this.allocate.bits.executed := UInt(0)
      this.allocate.bits.taken := UInt(0)
      this.allocate.bits.debug_pc := UInt(0)
      this.allocate.bits.debug_hist_ptr := UInt(0)
      this.update_counters.bits.index := UInt(0)
      this.update_counters.bits.executed := UInt(0)
      this.update_counters.bits.taken := UInt(0)
      this.update_usefulness.bits.index := UInt(0)
      this.update_usefulness.bits.inc := Bool(false)
      this.usefulness_req_idx := UInt(0)
   }

   override def cloneType: this.type = new TageTableIo(
      fetch_width, num_entries, history_length, tag_sz, counter_sz, this_index_sz).asInstanceOf[this.type]
}

class TageTableResp(fetch_width: Int, history_length: Int, index_length: Int, tag_sz: Int) extends Bundle
{
   val takens  = UInt(width = fetch_width)  // the actual prediction
   val index   = UInt(width = index_length) // the index of the prediction
   val tag     = UInt(width = tag_sz)       // the tag we computed for the prediction

   // Instead of passing huge histories around, just pass around a CSR of the
   // folded history (circular shift register).
   // This are snapshotted and reset on a misprediction.
   // Two CSRs are used for the tags to manage the scenario of repeating history
   // with the frequency equal to the history_length (it would fold down to
   // 0x0).
   val idx_csr  = UInt(width = index_length)
   val tag_csr1 = UInt(width = tag_sz)
   val tag_csr2 = UInt(width = tag_sz-1)

   override def cloneType: this.type = new TageTableResp(fetch_width, history_length, index_length, tag_sz).asInstanceOf[this.type]
}

class TageIndex(index_sz: Int) extends Bundle
{
   val index = UInt(width = index_sz)
   override def cloneType: this.type = new TageIndex(index_sz).asInstanceOf[this.type]
}

class TageUpdateUsefulInfo(index_sz: Int) extends Bundle
{
   val index = UInt(width = index_sz)
   val inc = Bool()
   override def cloneType: this.type = new TageUpdateUsefulInfo(index_sz).asInstanceOf[this.type]
}

class TageAllocateEntryInfo(fetch_width: Int, index_sz: Int, tag_sz: Int, hist_sz: Int) extends Bundle //TageIndex(index_sz)
{
   val index = UInt(width = index_sz)
   val tag = UInt(width = tag_sz)
   val executed = UInt(width = fetch_width)
   val taken = UInt(width = fetch_width)
   val debug_pc = UInt(width = 32)
   val debug_hist_ptr = UInt(width = hist_sz)
   override def cloneType: this.type = new TageAllocateEntryInfo(fetch_width, index_sz, tag_sz, hist_sz).asInstanceOf[this.type]
}

class TageUpdateCountersInfo(fetch_width: Int, index_sz: Int) extends Bundle //extends TageIndex(index_sz)
{
   val index = UInt(width = index_sz)
   val executed = UInt(width = fetch_width)
   val taken = UInt(width = fetch_width)
   override def cloneType: this.type = new TageUpdateCountersInfo(fetch_width, index_sz).asInstanceOf[this.type]
}

// The CSRs contain the "folded" history. For them to work, we need to pass them
// the latest new bit to add in and the oldest bit to evict out.
class CircularShiftRegisterUpdate extends Bundle
{
   val new_bit = Bool()
   val evict_bit = Bool()
}


// In Chisel3, all Bundle elements in a Vec() must be homogenous (i.e., when
// using a Vec() of TageTableIOs, the sub-fields within the TageTableIOs must
// have the exact same widths (no heterogenous types/widths). Therefore, we must
// track the max_* size of the parameters, and then within the TageTable we must
// mask off extra bits as needed.
class TageTable(
   fetch_width: Int,
   num_entries: Int,
   history_length: Int,
   tag_sz: Int,
   max_num_entries: Int,
   max_history_length: Int,
   max_tag_sz: Int,
   counter_sz: Int,
   ubit_sz: Int,
   id: Int,
   num_tables: Int
   )(implicit p: Parameters) extends BoomModule()(p)
{
   val index_sz = log2Up(num_entries)

   val io = new TageTableIo(fetch_width, max_num_entries, max_history_length, max_tag_sz, counter_sz, this_index_sz = index_sz)

   private val CNTR_MAX = (1 << counter_sz) - 1
   private val CNTR_WEAK_TAKEN = 1 << (counter_sz-1)
   private val CNTR_WEAK_NOTTAKEN = CNTR_WEAK_TAKEN - 1
   private val UBIT_MAX = (1 << ubit_sz) - 1
   private val UBIT_INIT_VALUE = 1

   println("\t    TageTable - "
      + num_entries + " entries, "
      + history_length + " bits of history, "
      + tag_sz + "-bit tags, "
      + counter_sz + "-bit counters (max value=" + CNTR_MAX + ")")

   //------------------------------------------------------------
   // State
   val counter_table = Mem(num_entries, Vec(fetch_width, UInt(width = counter_sz)))
   val tag_table     = Module(new TageTagMemory(num_entries, memwidth = tag_sz))
   val ubit_table    = Mem(num_entries, UInt(width = ubit_sz))
   val debug_pc_table= Mem(num_entries, UInt(width = 32))
   val debug_hist_ptr_table=Mem(num_entries,UInt(width = log2Up(VLHR_LENGTH)))

   //history ghistory
   val idx_csr         = Module(new CircularShiftRegister(index_sz, history_length))
   val tag_csr1        = Module(new CircularShiftRegister(tag_sz  , history_length))
   val tag_csr2        = Module(new CircularShiftRegister(tag_sz-1, history_length))
   val commit_idx_csr  = Module(new CircularShiftRegister(index_sz, history_length))
   val commit_tag_csr1 = Module(new CircularShiftRegister(tag_sz  , history_length))
   val commit_tag_csr2 = Module(new CircularShiftRegister(tag_sz-1, history_length))

   tag_table.io.InitializeIo()
   idx_csr.io.InitializeIo()
   tag_csr1.io.InitializeIo()
   tag_csr2.io.InitializeIo()
   commit_idx_csr.io.InitializeIo()
   commit_tag_csr1.io.InitializeIo()
   commit_tag_csr2.io.InitializeIo()


   //------------------------------------------------------------
   // functions

   //updateHistory()
   //clearUBit() TODO XXX



   private def Fold (input: UInt, compressed_length: Int) =
   {
      val clen = compressed_length
      val hlen = history_length
      if (hlen <= clen)
      {
         input
      }
      else
      {
         var res = UInt(0,clen)
         var remaining = input.toUInt
         for (i <- 0 to hlen-1 by clen)
         {
            val len = if (i + clen > hlen ) (hlen - i) else clen
            require(len > 0)
            res = res(clen-1,0) ^ remaining(len-1,0)
            remaining = remaining >> UInt(len)
         }
         res
      }
   }

   private def IdxHash (addr: UInt) =
   {
      val idx =
         ((addr >> UInt(log2Up(fetch_width*coreInstBytes))) ^
         idx_csr.io.next)

      idx(index_sz-1,0)
   }

   private def TagHash (addr: UInt) =
   {
      // the tag is computed by pc[n:0] ^ CSR1[n:0] ^ (CSR2[n-1:0]<<1).
      val tag_hash =
         (addr >> UInt(log2Up(fetch_width*coreInstBytes))) ^
         tag_csr1.io.next ^
         (tag_csr2.io.next << UInt(1))
      tag_hash(tag_sz-1,0)
   }

   private def IdxHashSimple (addr: UInt, hist: UInt) =
   {
      ((addr >> UInt(log2Up(fetch_width*coreInstBytes))) ^ Fold(hist(history_length-1,0), index_sz))(index_sz-1,0)
   }

   private def TagHashSimple (addr: UInt, hist: UInt) =
   {
      // the tag is computed by pc[n:0] ^ CSR1[n:0] ^ (CSR2[n-1:0]<<1).
      val tag_hash =
         (addr >> UInt(log2Up(fetch_width*coreInstBytes))) ^
         Fold(hist,  index_sz) ^
         (Fold(hist, index_sz-1) << UInt(1))
      tag_hash(tag_sz-1,0)
   }

   private def GetPrediction(cntr: UInt): Bool =
   {
      // return highest-order bit
      (cntr >> UInt(counter_sz-1))(0).toBool
   }

   private def BuildAllocCounterRow(enables: UInt, takens: UInt): Vec[UInt] =
   {
      val counters = for (i <- 0 until fetch_width) yield
      {
         Mux(!enables(i) || !takens(i),
            UInt(CNTR_WEAK_NOTTAKEN),
            UInt(CNTR_WEAK_TAKEN))
      }
      Vec(counters)
   }


   //------------------------------------------------------------
   // Get Prediction

   val stall = !io.bp2_resp.ready

//   val p_idx       = IdxHashSimple(io.if_req_pc, io.if_req_history)
//   val p_tag       = TagHashSimple(io.if_req_pc, io.if_req_history)
   val p_idx       = IdxHash(io.if_req_pc)
   val p_tag       = TagHash(io.if_req_pc)
   val counters    = counter_table(p_idx)
   tag_table.io.s0_r_idx := p_idx
   tag_table.io.stall := stall

   val s2_tag      = tag_table.io.s2_r_out
   val bp2_tag_hit = s2_tag === RegEnable(RegEnable(p_tag, !stall), !stall)

   io.bp2_resp.valid       := bp2_tag_hit
   io.bp2_resp.bits.takens := RegEnable(RegEnable(Vec(counters.map(GetPrediction(_))).toBits, !stall), !stall)
   io.bp2_resp.bits.index  := RegEnable(RegEnable(p_idx, !stall), !stall)(index_sz-1,0)
   io.bp2_resp.bits.tag    := RegEnable(RegEnable(p_tag, !stall), !stall)(tag_sz-1,0)

   io.bp2_resp.bits.idx_csr  := idx_csr.io.value
   io.bp2_resp.bits.tag_csr1 := tag_csr1.io.value
   io.bp2_resp.bits.tag_csr2 := tag_csr2.io.value

   //------------------------------------------------------------
   // Update (Branch Resolution)

   // only update history (CSRs)

   when (io.flush)
   {
      idx_csr.io.rollback (commit_idx_csr.io.value , and_shift=Bool(false))
      tag_csr1.io.rollback(commit_tag_csr1.io.value, and_shift=Bool(false))
      tag_csr2.io.rollback(commit_tag_csr2.io.value, and_shift=Bool(false))
   }
   .elsewhen (io.br_resolution.valid && io.br_resolution.bits.mispredict)
   {
      val resp_info = new TageResp(
            fetch_width = fetch_width,
            num_tables = num_tables,
            max_history_length = max_history_length,
            max_index_sz = log2Up(max_num_entries),
            max_tag_sz = max_tag_sz).fromBits(
         io.br_resolution.bits.info)

      val new_bit = io.br_resolution.bits.taken
      val evict_bit = resp_info.evict_bits(id)

      idx_csr.io.rollback (resp_info.idx_csr (id), and_shift=Bool(true), new_bit, evict_bit)
      tag_csr1.io.rollback(resp_info.tag_csr1(id), and_shift=Bool(true), new_bit, evict_bit)
      tag_csr2.io.rollback(resp_info.tag_csr2(id), and_shift=Bool(true), new_bit, evict_bit)
   }
   .elsewhen (io.bp2_update_history.valid)
   {
      val bp2_taken = io.bp2_update_history.bits.taken
      val bp2_evict = io.bp2_update_csr_evict_bit
      idx_csr.io.shift (bp2_taken, bp2_evict)
      tag_csr1.io.shift(bp2_taken, bp2_evict)
      tag_csr2.io.shift(bp2_taken, bp2_evict)
   }

   //------------------------------------------------------------
   // Update Commit-CSRs (Commit)

   val folded_com_hist = Fold(io.debug_ghistory_commit_copy(history_length-1,0), index_sz)
   when (io.commit_csr_update.valid)
   {
      val com_taken = io.commit_csr_update.bits.new_bit
      val com_evict = io.commit_csr_update.bits.evict_bit
      commit_idx_csr.io.shift (com_taken, com_evict)
      commit_tag_csr1.io.shift(com_taken, com_evict)
      commit_tag_csr2.io.shift(com_taken, com_evict)
   }

// TODO XXX unlease this comparision
//   assert (idx_csr.io.value === Fold(io.if_req_history, index_sz), "[TageTable] idx_csr not matching Fold() value.")
   assert (commit_idx_csr.io.value === folded_com_hist, "[TageTable] idx_csr not matching Fold() value.")


   //------------------------------------------------------------
   // Update (Commit)

   val init_counter_row = BuildAllocCounterRow(io.allocate.bits.executed, io.allocate.bits.taken)
   val a_idx = io.allocate.bits.index(index_sz-1,0)
   when (io.allocate.valid)
   {
      ubit_table(a_idx)    := UInt(UBIT_INIT_VALUE)
      counter_table(a_idx) := init_counter_row
      tag_table.io.write(a_idx, io.allocate.bits.tag(tag_sz-1,0))

      debug_pc_table(a_idx) := io.allocate.bits.debug_pc
      debug_hist_ptr_table(a_idx) := io.allocate.bits.debug_hist_ptr(history_length-1,0)

      when (!(a_idx < UInt(num_entries)))
      {
         printf("[TageTable] out of bounds index on allocation, a_idx: %d, num_en: %d", a_idx, UInt(num_entries))
      }
      assert (a_idx < UInt(num_entries), "[TageTable] out of bounds index on allocation")
      assert (ubit_table(a_idx) === UInt(0), "[TageTable] Tried to allocate a useful entry")
   }

   val u_idx = io.update_counters.bits.index(index_sz-1,0)
   val u_counter_row = counter_table(u_idx)
   val updated_row = Wire(u_counter_row.cloneType)
   updated_row.map(_ := UInt(0))
   when (io.update_counters.valid)
   {
      for (i <- 0 until fetch_width)
      {
         val enable = io.update_counters.bits.executed(i)
         val inc = io.update_counters.bits.taken(i)
         val value = u_counter_row(i)
         updated_row(i) :=
            Mux(enable && inc && value < UInt(CNTR_MAX),
               value + UInt(1),
            Mux(enable && !inc && value > UInt(0),
               value - UInt(1),
               value))
      }
      counter_table(u_idx) := updated_row
   }

   val ub_write_inc = io.update_usefulness.bits.inc
   val ub_write_idx = io.update_usefulness.bits.index(index_sz-1,0)
   when (io.update_usefulness.valid)
   {
      val u = ubit_table(ub_write_idx)
      ubit_table(ub_write_idx) :=
         Mux(ub_write_inc && u < UInt(UBIT_MAX),
            u + UInt(1),
         Mux(!ub_write_inc && u > UInt(0),
            u - UInt(1),
            u))
   }

   val ub_read_idx = io.usefulness_req_idx(index_sz-1,0)
   io.usefulness_resp := RegNext(
      ubit_table(ub_read_idx) |
      Mux(io.allocate.valid && a_idx === ub_read_idx, UInt(UBIT_INIT_VALUE), UInt(0)) |
      Mux(io.update_usefulness.valid && ub_write_inc && ub_write_idx === ub_read_idx, UInt(UBIT_INIT_VALUE), UInt(0)))

   //------------------------------------------------------------
   // Debug/Visualize

   if (DEBUG_PRINTF_TAGE)
   {
      require (num_entries < 64) // for sanity sake, don't allow larger.
      printf("TAGETable: PC: 0x%x history: 0x%x, tag[%d]=0x%x, p_tag=0x%x " + "%c\n",
         io.if_req_pc,
         io.if_req_history(history_length-1,0) + UInt(0,64),
         p_idx,
         s2_tag + UInt(0,64),
         TagHashSimple(io.if_req_pc, io.if_req_history),
         Mux(s2_tag === RegNext(RegNext(p_tag)), Str("H"), Str(" "))
      )

      for (i <- 0 to num_entries-1 by 4)
      {
         val lst = Seq(4,6,8,12,13)
         for (j <- 0 until 4)
         {
            //printf("(%d) [tag=0x%x]", UInt(i+j,8), tag_table(UInt(i+j)) & UInt(0xffff))
            for (k <- 0 until fetch_width)
            {
               printf(" [c=%d]", counter_table(UInt(i+j))(k))
            }
            printf(" [u=%d] " + "PC=0x%x hist=0x%x ",
               ubit_table(UInt(i+j)),
               (debug_pc_table(UInt(i+j)) & UInt(0xff))(11,0),
               debug_hist_ptr_table(UInt(i+j))
               )
         }
         printf("\n")
      }
      printf("\n")

   }

}

