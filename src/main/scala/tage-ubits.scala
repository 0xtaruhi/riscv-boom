//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// TAGE U-Bits
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Christopher Celio
// 2016 Nov
//
// Goal:
//    - U-bits provide a "usefulness" metric for each entry in a TAGE predictor.
//    - Only allocate for entries that are "not useful".
//    - Occasionally, degrade entries to prevent unused entries from never leaving.
//
// TODO:
//    - Allow 1-bit and 2-bit implementations.
//    - Allow for changing out zeroing policies.

package boom

import Chisel._


abstract class TageUbitMemory(
   num_entries: Int,
   ubit_sz: Int
   ) extends Module
{
   val index_sz = log2Up(num_entries)
   val io = new Bundle
   {
      // send read addr on cycle 0, get data out on cycle 2.
      val s0_read_idx = UInt(INPUT, width = index_sz)
      val s1_read_out = UInt(OUTPUT, width = ubit_sz)


      val allocate_valid  = Bool(INPUT)
      val allocate_idx = UInt(INPUT, width = index_sz)
      def allocate(idx: UInt) =
      {
         this.allocate_valid  := Bool(true)
         this.allocate_idx := idx
      }

      val update_valid  = Bool(INPUT)
      val update_idx = UInt(INPUT, width = index_sz)
      val update_old_value  = UInt(INPUT, width = ubit_sz)
      val update_inc = Bool(INPUT)
      def update(idx: UInt, old_value: UInt, inc: Bool) =
      {
         this.update_valid  := Bool(true)
         this.update_idx := idx
         this.update_old_value := old_value
         this.update_inc := inc
      }

      val degrade_valid = Bool(INPUT)
      def degrade(dummy: Int=0) =
      {
         this.degrade_valid := Bool(true)
      }

//      // Degrading may take many cycles. Tell the tage-table if we are degrading.
//      val is_degrading = Bool(OUTPUT)
//      def areDegrading(dummy: Int=0) =
//      {
//         this.is_degrading
//      }

      def InitializeIo(dummy: Int=0) =
      {
         this.allocate_valid := Bool(false)
         this.allocate_idx := UInt(0)
         this.update_valid := Bool(false)
         this.update_idx := UInt(0)
         this.update_old_value := UInt(0)
         this.update_inc := Bool(false)
         this.degrade_valid := Bool(false)
//         this.is_degrading := Bool(false)
      }
   }

   val UBIT_MAX = (1 << ubit_sz) - 1
   val UBIT_INIT_VALUE = 1
   require(ubit_sz < 4) // What are you doing? You're wasting bits!
   assert(!(io.allocate_valid && io.update_valid), "[ubits] trying to update and allocate simultaneously.")
}

//--------------------------------------------------------------------------
//--------------------------------------------------------------------------
// This version implements the u-bits in sequential memory -- can be placed into SRAM.
// However, degrading the u-bits takes many cycles.
class TageUbitMemorySeqMem(
   num_entries: Int,
   ubit_sz: Int
   ) extends TageUbitMemory(num_entries, ubit_sz)
{
   require(false)
   // we don't currently support >1 ubit_sz versions until we finish supporting
   // the clearing of the u-bits. As this takes many cycles, we need to add
   // support for the ubit table to tell TAGE to not start counting
   // failed_allocs until we finish the degrading.

   //------------------------------------------------------------

   val ubit_table = SeqMem(num_entries, UInt(width = ubit_sz))

   // maintain an async copy purely for assertions
   val debug_ubit_table = Mem(num_entries, UInt(width = ubit_sz))
   val debug_valids     = Reg(init=Vec.fill(num_entries){Bool(false)})

   //------------------------------------------------------------
   // Manage clearing u-bits over time
   // (to prevent entries from never leaving the predictor).

//   val CLEAR_FREQUENCY = (1<<20) // 1M cycles
//   val clear_timer = util.WideCounter(log2Up(CLEAR_FREQUENCY))
   val clear_idx = Reg(init=UInt(0, index_sz))

   val s_reset :: s_sleep :: s_clear :: Nil = Enum(UInt(),3)
   val state = Reg(init = s_reset)
//   val trigger = (clear_timer === UInt(0))
   val trigger = io.degrade_valid
   assert (!(io.degrade_valid && state === s_clear), "[ubits] degrade_valid high while we're already degrading.")

   val finished = (clear_idx === UInt((1 << index_sz)-1)) && !(io.allocate_valid || io.update_valid)

   when (state === s_clear && !(io.allocate_valid || io.update_valid))
   {
      clear_idx := clear_idx + UInt(1)
   }

   switch (state)
   {
      is (s_reset)
      {
         state := s_sleep
      }
      is (s_sleep)
      {
         when (trigger)
         {
            state := s_clear
         }
      }
      is (s_clear)
      {
         when (finished)
         {
            state := s_sleep
         }
      }
   }

   //------------------------------------------------------------

//   val idx = Wire(UInt())
//   val last_idx = RegNext(idx)

//   idx := Mux(io.stall, last_idx, io.s0_r_idx)

//   val r_s1_out = smem.read(idx, !io.stall)
//   val r_s2_out = RegEnable(r_s1_out, !io.stall)
//   io.s2_r_out := r_s2_out
   // TODO add a read_enable (only reads on commit.valid within TAGE)
   val s1_out = ubit_table.read(io.s0_read_idx, Bool(true))
   io.s1_read_out :=
      s1_out |
      RegNext(
         Mux(io.allocate_valid && io.allocate_idx === io.s0_read_idx, UInt(UBIT_INIT_VALUE), UInt(0)) |
         Mux(io.update_valid && io.update_inc && io.update_idx === io.s0_read_idx, UInt(UBIT_INIT_VALUE), UInt(0)))


   //------------------------------------------------------------
   // Compute update values.
   val inc = io.update_inc
   val u = io.update_old_value
   val next_u =
      Mux(inc && u < UInt(UBIT_MAX),
         u + UInt(1),
      Mux(!inc && u > UInt(0),
         u - UInt(1),
         u))

   // Perform write.
   val w_en   = io.allocate_valid || io.update_valid || (state === s_clear)
   val w_addr = Mux(io.allocate_valid, io.allocate_idx,
                Mux(io.update_valid,   io.update_idx,
                                       clear_idx))
   val w_data = Mux(io.allocate_valid, UInt(UBIT_INIT_VALUE),
                Mux(io.update_valid,   next_u,
                                       UInt(0)))
   when (w_en)
   {
      ubit_table(w_addr) := w_data
      debug_ubit_table(w_addr) := w_data
   }

   //------------------------------------------------------------
   when (io.allocate_valid)
   {
      debug_valids(io.allocate_idx) := Bool(true)
   }

   val r_debug_allocate_value = RegNext(debug_ubit_table(io.allocate_idx))
   when (RegNext(io.allocate_valid && debug_valids(io.allocate_idx)))
   {
      assert(r_debug_allocate_value === UInt(0), "[ubits] Tried to allocate a useful entry")
   }
}

//--------------------------------------------------------------------------
//--------------------------------------------------------------------------
// This version implements the u-bits as a single (very long) register.
// However, this allows us to degrade the u-bits in a single cycle.
// The other constraint is we only support a ubit_sz of 1.
class TageUbitMemoryFlipFlop(
   num_entries: Int,
   ubit_sz: Int=1
   ) extends TageUbitMemory(num_entries, ubit_sz)
{
   require(ubit_sz == 1)
   require(UBIT_MAX == 1)

   //------------------------------------------------------------

   val ubit_table = Reg(UInt(width = num_entries))


   //------------------------------------------------------------

   val s1_out = RegNext(ubit_table(io.s0_read_idx))
   io.s1_read_out :=
      (s1_out ||
      RegNext(
         (io.allocate_valid && io.allocate_idx === io.s0_read_idx) ||
         (io.update_valid && io.update_inc && io.update_idx === io.s0_read_idx))).asUInt


   //------------------------------------------------------------
   // Compute update values.
   val inc = io.update_inc
   val u = io.update_old_value.toBool
   val next_u =
      Mux(inc && !u,
         UInt(1),
      Mux(!inc && u,
         UInt(0),
         u))
   require(u.getWidth==1)

   // Perform write.
   val w_en   = io.allocate_valid || io.update_valid
   val w_addr = Mux(io.allocate_valid, io.allocate_idx, io.update_idx)
   val w_data = Mux(io.allocate_valid, UInt(UBIT_INIT_VALUE), next_u)
   when (w_en)
   {
      ubit_table := ubit_table.bitSet(w_addr, w_data.toBool)
   }
   require (w_data.getWidth == 1)

   //------------------------------------------------------------
   when (io.degrade_valid)
   {
      ubit_table := UInt(0)
   }


   val r_debug_allocate_value = RegNext(ubit_table(io.allocate_idx))
   when (RegNext(io.allocate_valid))
   {
      assert(r_debug_allocate_value === UInt(0), "[ubits] Tried to allocate a useful entry")
   }
}

