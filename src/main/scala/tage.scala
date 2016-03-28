//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// TAGE-based Branch Predictor
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Christopher Celio
// 2016 Feb 26

// Terminology:
//    - provider
//       The table that provides the prediction.
//    - alternate
//       The table that would have provided the prediction if the provider had
//       missed.
//    - CSR
//       Circular Shift Register. Useful for folding very long histories in on
//       itself.  Please ignore the fact that CSR refers to "Control/Status
//       Register" elsewhere in BOOM.

// TODO:
//    - add very-long histories (VLH)
//    - make predictor sequential (first show it works, then make it sequential)
//    - alt-pred tracking (choosing between +2 tables, sometimes using alt pred if u is low)
//    - u-bit handling, clearing (count failed allocations?)
//    - SRAM handling
//    - banking
//    - lower required parameters, arguments to bundles and objects
//    - able to allocate >1 tables
//    - useful-ness port count (updating when provided prediction, separate from decrementing if no alloc
//    - brpredictor seems to couple fetch-width and commit-width :(
//    - do ALL the tags need to be tracked? can we compute alloc_id during prediction?
//       - no, maintain commit-copy of CSRs, pass in committed Fetch_pC to recompute
//    stats we want to track:
//       - how often no BTB hit and no table hit
//       - how often entries are used
//       - how often allocation fails
//       - how often we reset the useful-ness bits
// SCHEMES
//    - u-bit incremented only if alt-pred available?
//    - change when we use alt-pred instead of first-pred
//    - frequency of clearing u-bits
//    - 1 or 2-bit u-bits? (almost certainly 2-bits)
//    - 2 or 3 bit counters
//
// DEBUGGING:
//    - is the BROB giving us correct information? Are jumps polluting ghist_commit, etc.?

package boom

import Chisel._
import Node._
import cde.{Parameters, Field}

import rocket.Str

case object TageKey extends Field[TageParameters]

case class TageParameters(
   enabled: Boolean = true,
   // 12kB predictor
   num_tables: Int = 4,
   table_sizes: Seq[Int] = Seq(4096,4096,2048,2048),
   history_lengths: Seq[Int] = Seq(5,16,44,130),
   tag_sizes: Seq[Int] = Seq(10,10,10,11))

class TageResp(
   fetch_width: Int,
   num_tables: Int,
   max_history_length: Int,
   max_index_sz: Int,
   max_tag_sz: Int)
   extends Bundle
{
   val provider_hit = Bool() // did tage make a prediction?
   val provider_id = UInt(width = 5) // which table is providing the prediction?
   val provider_predicted_takens = Bits(width = fetch_width)
   val alt_hit = Bool()  // an alternate table made a prediction too
   val alt_id = UInt(width = 5)  // which table is the alternative?
   val alt_predicted_takens = Bits(width = fetch_width)
   val tags = Vec(num_tables, Bits(width = max_tag_sz))

   val history = Bits(width = max_history_length) // stored in snapshots (dealloc after Execute)
   val indexes = Vec(num_tables, Bits(width = max_index_sz)) // needed to update predictor at Commit

   val br_pc = UInt(width=64)

   override def cloneType: this.type =
      new TageResp(
         fetch_width,
         num_tables,
         max_history_length,
         max_index_sz,
         max_tag_sz).asInstanceOf[this.type]
}

// provide information to the BpdResp bundle how many bits a TageResp needs
object TageBrPredictor
{
   def GetRespInfoSize(p: Parameters): Int =
   {
      import rocket.FetchWidth
      val dummy = new TageResp(
         fetch_width = p(FetchWidth),
         num_tables = p(TageKey).num_tables,
         max_history_length = p(TageKey).history_lengths.max,
         max_index_sz = log2Up(p(TageKey).table_sizes.max),
         max_tag_sz = p(TageKey).tag_sizes.max
         )
      dummy.getWidth
   }
}

//--------------------------------------------------------------------------
//--------------------------------------------------------------------------

class TageBrPredictor(
   fetch_width: Int,
   num_tables: Int,
   table_sizes: Seq[Int],
   history_lengths: Seq[Int],
   tag_sizes: Seq[Int]
   )(implicit p: Parameters)
   extends BrPredictor(
      fetch_width    = fetch_width,
      history_length = history_lengths.max)(p)
{
   val counter_sz = 2
   val ubit_sz = 2
   val size_in_bits = (for (i <- 0 until num_tables) yield
   {
      val entry_sz_in_bits = tag_sizes(i) + ubit_sz + (counter_sz*fetch_width)
      table_sizes(i) * entry_sz_in_bits
   }).reduce(_+_)

   println ("\tBuilding " + (size_in_bits/8/1024.0) + " kB TAGE Predictor ("
      + (size_in_bits/1024) + " Kbits) (max history length: " + history_lengths.max + " bits)")
   require (num_tables == table_sizes.size)
   require (num_tables == history_lengths.size)
   require (num_tables == tag_sizes.size)
   // require (log2Up(num_tables) <= TageResp.provider_id.getWidth()) TODO implement this check
   require (coreInstBytes == 4)

   //------------------------------------------------------------
   //------------------------------------------------------------

   private val MAX_TABLE_ID = num_tables-1

   //------------------------------------------------------------
   //------------------------------------------------------------

   def GetProviderTableId(hits:IndexedSeq[Bool]): UInt =
   {
      // return the id of the highest table with a hit
      PriorityMux(hits.reverse, (num_tables-1 to 0 by -1).map(UInt(_)))
   }

   def GetAlternateTableId(hits:IndexedSeq[Bool]): (Bool, UInt) =
   {
      // return the id of the 2nd highest table with a hit
      // also returns whether a 2nd hit was found (PopCount(hits) > 1)
      val alt_id = Wire(init=UInt(0))
      var found_first = Bool(false)
      var found_second = Bool(false)
      for (i <- num_tables-1 to 0 by -1)
      {
         when (found_first && !found_second)
         {
            alt_id := UInt(i)
         }
         found_second = (hits(i) && found_first) | found_second
         found_first = hits(i) | found_first
      }
      assert ((PopCount(hits) > UInt(1)) ^ !found_second,
         "[Tage] GetAltId has a disagreement on finding a second hit.")
      (found_second, alt_id)
   }

   //------------------------------------------------------------
   //------------------------------------------------------------

   val stall = !io.resp.ready

   //------------------------------------------------------------
   //------------------------------------------------------------

   val tables = for (i <- 0 until num_tables) yield
   {
      val table = Module(new TageTable(
         fetch_width    = fetch_width,
         id             = i,
         num_entries    = table_sizes(i),
         history_length = history_lengths(i),
         tag_sz         = tag_sizes(i),
         counter_sz     = counter_sz,
         ubit_sz        = ubit_sz))
      table.io.InitializeIo()

      // send prediction request
      table.io.if_req_pc := io.req_pc
      table.io.if_req_history := this.ghistory

      // update during mispredict
      table.io.bp2_update_history <> io.hist_update_spec

      // check that the user ordered his TAGE tables properly
      if (i > 0) require(history_lengths(i) > history_lengths(i-1))

      table
   }

   // get prediction (priority to last table)
   val valids = tables.map{ _.io.bp2_resp.valid }
   val predictions = tables.map{ _.io.bp2_resp.bits }
   tables.map{ _.io.bp2_resp.ready := io.resp.ready }
   val best_prediction_valid = valids.reduce(_|_)
   val best_prediction_bits = PriorityMux(valids.reverse, predictions.reverse)

   val resp_info = Wire(new TageResp(
      fetch_width = fetch_width,
      num_tables = num_tables,
      max_history_length = history_lengths.max,
      max_index_sz = log2Up(table_sizes.max),
      max_tag_sz = tag_sizes.max))

   io.resp.valid             := best_prediction_valid
   io.resp.bits.takens       := best_prediction_bits.takens
   io.resp.bits.history := RegEnable(RegEnable(this.ghistory, !stall), !stall)
   resp_info.indexes := Vec(predictions.map(_.index))
   resp_info.provider_hit := io.resp.valid
   resp_info.provider_id := GetProviderTableId(valids)
   resp_info.provider_predicted_takens := best_prediction_bits.takens

   val (p_alt_hit, p_alt_id) = GetAlternateTableId(valids)
   resp_info.alt_hit := p_alt_hit
   resp_info.alt_id  := p_alt_id
   resp_info.alt_predicted_takens := Vec(predictions.map(_.takens))(p_alt_id)
   resp_info.br_pc := RegEnable(RegEnable(io.req_pc, !stall), !stall)

   println("tags len: " + resp_info.tags.length + ", predictions len: " + predictions.map(_.tag).length)
   resp_info.tags := Vec(predictions.map(_.tag))


   io.resp.bits.info := resp_info.toBits

   if (DEBUG_PRINTF_TAGE)
   {
      printf("\n0x%x Prediction Hits Array, Provider: %d\n",
         Vec(valids).toBits, resp_info.provider_id)
   }
   when (io.resp.valid)
   {
      if (DEBUG_PRINTF_TAGE)
      {
         printf(red + "prediction made hit: PC 0x%x, ghistory=0x%x" + end + "\n"
            , RegEnable(RegEnable(io.req_pc, !stall), !stall)
            , io.resp.bits.history
         )
      }
   }
   .otherwise
   {
      if (DEBUG_PRINTF_TAGE)
      {
         printf("\n")
      }
   }

   //------------------------------------------------------------
   //------------------------------------------------------------
   // update predictor during commit


   val tables_io = Vec(tables.map(_.io))

   // provide some randomization to the allocation process
   val rand = Reg(init=UInt(0,2))
   rand := rand + UInt(1)

   when (commit.valid && commit.bits.executed.reduce(_|_))
   {
      val correct = !commit.bits.mispredicted.reduce(_|_)
      val info = new TageResp(
         fetch_width = fetch_width,
         num_tables = num_tables,
         max_history_length = history_lengths.max,
         max_index_sz = log2Up(table_sizes.max),
         max_tag_sz = tag_sizes.max
      ).fromBits(commit.bits.info.info)
      val takens = commit.bits.taken.toBits
      val executed = commit.bits.executed.toBits

      val provider_id = info.provider_id
      val alt_id      = info.alt_id

      // TODO verify this behavior/logic is correct (re: toBits/Vec conversion)
      val alt_agrees = info.alt_hit &&
         (info.provider_predicted_takens & executed) === (info.alt_predicted_takens & executed)

      assert (provider_id < UInt(num_tables) || !info.provider_hit,
         "[Tage] provider_id is out-of-bounds.")

      // no matter what happens, update table that made a prediction
      when (info.provider_hit)
      {
         tables_io(provider_id).UpdateCounters(info.indexes(provider_id), executed, takens)
         when (!alt_agrees)
         {
            tables_io(provider_id).UpdateUsefulness(info.indexes(provider_id), correct)
         }
      }

      if (DEBUG_PRINTF_TAGE)
      {
         printf("Committing and updating predictor: PC: 0x%x HIST: 0x%x correct=%d predhit: %d, exe=%d takens=%d agree=%d althit: %d prov_id: %d -[",
            info.br_pc, info.history, correct, info.provider_hit,
            executed, takens, alt_agrees, info.alt_hit, provider_id)
         info.indexes.map{printf("%d ", _)}
         printf("]\n")
      }

      when (!correct && (provider_id < UInt(MAX_TABLE_ID) || !info.provider_hit))
      {
         // try to allocate a new entry

         // if provider not T_max, try to allocate an entry on T_k (i < k < max).
         // - only allocate one entry.
         // - a) if find an entry u_k that == 0, then allocate T_k
         // - b) ELSE decrement u_counters from Tj where (i<j<=max), or just (i<j<max).
         //    b.i) randomize r, where i<=(i+r)<k<=max, to prevent ping-ponging
         //       where new allocations simply over-write once another before the u-bit
         //       can be strengthened.


         val r_temp = Mux(rand === UInt(3), UInt(2),
                      Mux(rand === UInt(2), UInt(1),
                                            UInt(0)))
         val r = Mux((Cat(UInt(0),provider_id) + r_temp) >= UInt(MAX_TABLE_ID),
                  UInt(0),
                  r_temp)

         if (DEBUG_PRINTF_TAGE)
         {
            printf("Trying to allocate an entry.... hit=%d, provider=%d base_id=%d",
               info.provider_hit, provider_id, Cat(UInt(0),provider_id) + r)
            for (i <- 0 until num_tables)
            {
               printf(" - Table_%d[%d],", UInt(i), info.indexes(i))
            }

            printf("\n")
         }

         // find lowest alloc_idx where u_bits === 0
         val can_allocates = Range(0, num_tables).map{ i =>
            tables(i).io.GetUsefulness(info.indexes(i)) === Bits(0) &&
            ((UInt(i) > (Cat(UInt(0),provider_id) + r)) || !info.provider_hit)
         }

         val alloc_id = PriorityEncoder(can_allocates)
         when (can_allocates.reduce(_|_))
         {
            tables_io(alloc_id).AllocateNewEntry(
               info.indexes(alloc_id),
               info.tags(alloc_id),
               executed,
               takens,
               info.br_pc,
               info.history)

            if (DEBUG_PRINTF_TAGE)
            {
               printf("Allocating on Table[%d] at index:%d tag=0x%x  exe=%d taken=%d, provider=%d, can_allocates=0x%x\n\n",
                  alloc_id, info.indexes(alloc_id), info.tags(alloc_id), executed, takens, provider_id, Vec(can_allocates).toBits)
            }
         }
         .otherwise
         {
            //decrementUBits for tables[provider_id+1: T_max]
            // TODO break this out, such that there's only one call to UpdateUseful
            for (i <- 0 until num_tables)
            {
               when ((UInt(i) > provider_id) || !info.provider_hit)
               {
                  tables(i).io.UpdateUsefulness(info.indexes(i), inc = Bool(false))
                  if (DEBUG_PRINTF_TAGE)
                  {
                     printf("   Decrementing useful bit for Table_%d[%d] --", UInt(i), info.indexes(i))
                  }
               }
            }
            if (DEBUG_PRINTF_TAGE)
            {
               printf("Failed allocation  takens=%d, can_allocates=0x%x\n",
                  takens, Vec(can_allocates).toBits)
            }
         }
      }
      .otherwise
      {
         if (DEBUG_PRINTF_TAGE)
         {
            printf("\n")
         }
      }
   }
   .otherwise
   {
      if (DEBUG_PRINTF_TAGE)
      {
         printf("\n\n")
      }
   }
}

