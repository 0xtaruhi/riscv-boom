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
//
// TODO:
//    - Allow 1-bit and 2-bit implementations.
//    - Allow for changing out zeroing policies.

package boom

import Chisel._


class TageUbitMemory(
   num_entries: Int,
   ubit_sz: Int
   ) extends Module
{
   private val index_sz = log2Up(num_entries)
   val io = new Bundle
   {
      // send read addr on cycle 0, get data out on cycle 2.
      val s0_r_idx = UInt(INPUT, width = index_sz)
      val s0_r_out = UInt(OUTPUT, width = ubit_sz)

      val allocate_valid  = Bool(INPUT)
      val allocate_idx = UInt(INPUT, width = index_sz)
      def allocate(idx: UInt) =
      {
         this.allocate_valid  := Bool(true)
         this.allocate_idx := idx
      }

      val update_valid  = Bool(INPUT)
      val update_idx = UInt(INPUT, width = index_sz)
      val update_inc = Bool(INPUT)
      def update(idx: UInt, inc: Bool) =
      {
         this.update_valid  := Bool(true)
         this.update_idx := idx
         this.update_inc := inc
      }

      def InitializeIo(dummy: Int=0) =
      {
         this.allocate_valid := Bool(false)
         this.allocate_idx := UInt(0)
         this.update_valid := Bool(false)
         this.update_idx := UInt(0)
         this.update_inc := Bool(false)
      }
   }

   private val UBIT_MAX = (1 << ubit_sz) - 1
   private val UBIT_INIT_VALUE = 1

   //------------------------------------------------------------

//   val smem = SeqMem(num_entries, UInt(width = memwidth))
   val ubit_table = Mem(num_entries, UInt(width = ubit_sz))

   //------------------------------------------------------------

//   val idx = Wire(UInt())
//   val last_idx = RegNext(idx)

//   idx := Mux(io.stall, last_idx, io.s0_r_idx)

//   val r_s1_out = smem.read(idx, !io.stall)
//   val r_s2_out = RegEnable(r_s1_out, !io.stall)
//   io.s2_r_out := r_s2_out
   io.s0_r_out := ubit_table(io.s0_r_idx) |
                  Mux(io.allocate_valid && io.allocate_idx === io.s0_r_idx, UInt(UBIT_INIT_VALUE), UInt(0)) |
                  Mux(io.update_valid && io.update_inc && io.update_idx === io.s0_r_idx, UInt(UBIT_INIT_VALUE), UInt(0))


   when (io.allocate_valid)
   {
      ubit_table(io.allocate_idx) := UInt(UBIT_INIT_VALUE)

      assert (ubit_table(io.allocate_idx) === UInt(0), "[ubits] Tried to allocate a useful entry")
   }
   .elsewhen (io.update_valid)
   {
      val inc = io.update_inc
      val u = ubit_table(io.update_idx)
      ubit_table(io.update_idx) :=
         Mux(inc && u < UInt(UBIT_MAX),
            u + UInt(1),
         Mux(!inc && u > UInt(0),
            u - UInt(1),
            u))

   }

   assert(!(io.allocate_valid && io.update_valid), "[ubits] trying to update and allocate simultaneously.")

}

