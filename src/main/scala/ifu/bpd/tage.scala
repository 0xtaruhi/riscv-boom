package boom.ifu

import chisel3._
import chisel3.util._
import chisel3.experimental.dontTouch

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

import boom.common._
import boom.util.{BoomCoreStringPrefix, MaskLower, WrapInc}

import scala.math.min

class TageMeta(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomFrontendParameters
{
  val provider      = Vec(bankWidth, Valid(UInt(log2Ceil(tageNTables).W)))
  val alt_differs   = Vec(bankWidth, Bool())
  val provider_u    = Vec(bankWidth, UInt(2.W))
  val provider_ctr  = Vec(bankWidth, UInt(3.W))
  val allocate      = Vec(bankWidth, Valid(UInt(log2Ceil(tageNTables).W)))
}



class TageResp extends Bundle {
  val ctr = UInt(3.W)
  val u   = UInt(2.W)
}


class TageTable(val nRows: Int, val tagSz: Int, val histLength: Int)
  (implicit p: Parameters) extends BoomModule()(p)
  with HasBoomFrontendParameters
{
  require(histLength <= globalHistoryLength)

  val nIUMEntries = 2
  val nWrBypassEntries = 2
  val io = IO( new Bundle {
    val f0_req = Input(Valid(new BranchPredictionBankRequest))

    val f3_resp = Output(Vec(bankWidth, Valid(new TageResp)))
    val f3_kill = Input(Bool())

    val update_mask    = Input(Vec(bankWidth, Bool()))
    val update_taken   = Input(Vec(bankWidth, Bool()))
    val update_alloc   = Input(Vec(bankWidth, Bool()))
    val update_old_ctr = Input(Vec(bankWidth, UInt(3.W)))

    val update_pc    = Input(UInt())
    val update_hist  = Input(UInt())

    val update_u_mask = Input(Vec(bankWidth, Bool()))
    val update_u = Input(Vec(bankWidth, UInt(2.W)))
  })

  def compute_folded_hist(hist: UInt, l: Int) = {
    val nChunks = (histLength + l - 1) / l
    val hist_chunks = (0 until nChunks) map {i =>
      hist(min((i+1)*l, histLength)-1, i*l)
    }
    hist_chunks.reduce(_^_)
  }

  def compute_tag_and_hash(unhashed_idx: UInt, hist: UInt) = {
    val idx_history = compute_folded_hist(hist, log2Ceil(nRows))
    val idx = (unhashed_idx ^ idx_history)(log2Ceil(nRows)-1,0)
    val tag_history = compute_folded_hist(hist, tagSz)
    val tag = ((unhashed_idx >> log2Ceil(nRows)) ^ tag_history)(tagSz-1,0)
    (idx, tag)
  }

  def inc_ctr(ctr: UInt, taken: Bool): UInt = {
    Mux(!taken, Mux(ctr === 0.U, 0.U, ctr - 1.U),
                Mux(ctr === 7.U, 7.U, ctr + 1.U))
  }


  val doing_reset = RegInit(true.B)
  val reset_idx = RegInit(0.U(log2Ceil(nRows).W))
  reset_idx := reset_idx + doing_reset
  when (reset_idx === (nRows-1).U) { doing_reset := false.B }


  class TageEntry extends Bundle {
    val valid = Bool() // TODO: Remove this valid bit
    val tag = UInt(tagSz.W)
    val ctr = UInt(3.W)
  }


  val tageEntrySz = 1 + tagSz + 3

  val (s0_hashed_idx, s0_tag) = compute_tag_and_hash(fetchIdx(io.f0_req.bits.pc), io.f0_req.bits.hist)

  val hi_us  = SyncReadMem(nRows, Vec(bankWidth, Bool()))
  val lo_us  = SyncReadMem(nRows, Vec(bankWidth, Bool()))
  val table  = SyncReadMem(nRows, Vec(bankWidth, UInt(tageEntrySz.W)))

  val ium_vals = Reg(Vec(nIUMEntries, Bool()))
  val ium_tags = Reg(Vec(nIUMEntries, UInt(tagSz.W)))
  val ium_idxs = Reg(Vec(nIUMEntries, UInt(log2Ceil(nRows).W)))
  val ium      = Reg(Vec(nIUMEntries, Vec(bankWidth, UInt(3.W))))
  val ium_enq_idx = RegInit(0.U(log2Ceil(nIUMEntries).W))

  val s1_hashed_idx = RegNext(s0_hashed_idx)
  val s1_tag        = RegNext(s0_tag)

  val s1_req       = RegNext(io.f0_req)
  val s1_req_rtage = VecInit(table.read(s0_hashed_idx, io.f0_req.valid).map(_.asTypeOf(new TageEntry)))
  val s1_req_rhius = hi_us.read(s0_hashed_idx, io.f0_req.valid)
  val s1_req_rlous = lo_us.read(s0_hashed_idx, io.f0_req.valid)
  val s1_req_rhits = VecInit(s1_req_rtage.map(e => e.valid && e.tag === s1_tag && !doing_reset))
  val s1_req_iumhits = VecInit((0 until nIUMEntries) map { i =>
    ium_tags(i) === s1_tag && ium_idxs(i) === RegNext(s0_hashed_idx) && !doing_reset && ium_vals(i)
  })
  val s1_req_ium_hit_idx = PriorityEncoder(s1_req_iumhits)

  val s2_req          = RegNext(s1_req)
  val s2_req_ium_hit  = RegNext(s1_req_iumhits.reduce(_||_))
  val s2_req_ium_hit_idx = RegNext(s1_req_ium_hit_idx)
  val s2_req_ium_data = RegNext(ium(s1_req_ium_hit_idx))
  val s2_req_rtage = RegNext(s1_req_rtage)
  val s2_req_rhius = RegNext(s1_req_rhius)
  val s2_req_rlous = RegNext(s1_req_rlous)
  val s2_req_rhits = RegNext(s1_req_rhits)
  val s2_tag       = RegNext(s1_tag)

  for (w <- 0 until bankWidth) {
    // This bit indicates the TAGE table matched here
    io.f3_resp(w).valid    := RegNext(s2_req_rhits(w))
    io.f3_resp(w).bits.u   := RegNext(Cat(s2_req_rhius(w), s2_req_rlous(w)))
    //io.f3_resp(w).bits.ctr := RegNext(Mux(s2_req_ium_hit,  s2_req_ium_data(w), s2_req_rtage(w).ctr))
    io.f3_resp(w).bits.ctr := RegNext(s2_req_rtage(w).ctr)
  }
  when (RegNext(s2_req.valid && s2_req_rhits.reduce(_||_) && !s2_req_ium_hit) && !io.f3_kill) {
    for (w <- 0 until bankWidth) {
      ium(ium_enq_idx)(w) := RegNext(inc_ctr(s2_req_rtage(w).ctr, s2_req_rtage(w).ctr(2)))
    }
    ium_idxs(ium_enq_idx) := RegNext(RegNext(s1_hashed_idx))
    ium_tags(ium_enq_idx) := RegNext(RegNext(s1_tag))
    ium_vals(ium_enq_idx) := true.B
    ium_enq_idx := ium_enq_idx + 1.U
  }

  val clear_u_ctr = RegInit(0.U((log2Ceil(tageUBitPeriod) + log2Ceil(nRows) + 1).W))
  when (doing_reset) { clear_u_ctr := 1.U } .otherwise { clear_u_ctr := clear_u_ctr + 1.U }

  val doing_clear_u = clear_u_ctr(log2Ceil(tageUBitPeriod)-1,0) === 0.U
  val doing_clear_u_hi = doing_clear_u && clear_u_ctr(log2Ceil(tageUBitPeriod) + log2Ceil(nRows)) === 1.U
  val doing_clear_u_lo = doing_clear_u && clear_u_ctr(log2Ceil(tageUBitPeriod) + log2Ceil(nRows)) === 0.U
  val clear_u_idx = clear_u_ctr >> log2Ceil(tageUBitPeriod)

  val (update_idx, update_tag) = compute_tag_and_hash(fetchIdx(io.update_pc), io.update_hist)

  when (io.update_mask.reduce(_||_)) {
    for (w <- 0 until nIUMEntries) {
      when (update_idx === ium_idxs(w) && update_tag === ium_tags(w)) {
        ium_vals(w) := false.B
      }
    }
  }

  val update_wdata = Wire(Vec(bankWidth, new TageEntry))

  table.write(
    Mux(doing_reset, reset_idx                                          , update_idx),
    Mux(doing_reset, VecInit(Seq.fill(bankWidth) { 0.U(tageEntrySz.W) }), VecInit(update_wdata.map(_.asUInt))),
    Mux(doing_reset, ~(0.U(bankWidth.W))                                , io.update_mask.asUInt).asBools
  )

  val update_hi_wdata = Wire(Vec(bankWidth, Bool()))
  hi_us.write(
    Mux(doing_reset, reset_idx, Mux(doing_clear_u_hi, clear_u_idx, update_idx)),
    Mux(doing_reset || doing_clear_u_hi, VecInit((0.U(bankWidth.W)).asBools), update_hi_wdata),
    Mux(doing_reset || doing_clear_u_hi, ~(0.U(bankWidth.W)), io.update_u_mask.asUInt).asBools
  )

  val update_lo_wdata = Wire(Vec(bankWidth, Bool()))
  lo_us.write(
    Mux(doing_reset, reset_idx, Mux(doing_clear_u_lo, clear_u_idx, update_idx)),
    Mux(doing_reset || doing_clear_u_lo, VecInit((0.U(bankWidth.W)).asBools), update_lo_wdata),
    Mux(doing_reset || doing_clear_u_lo, ~(0.U(bankWidth.W)), io.update_u_mask.asUInt).asBools
  )

  val wrbypass_tags    = Reg(Vec(nWrBypassEntries, UInt(tagSz.W)))
  val wrbypass_idxs    = Reg(Vec(nWrBypassEntries, UInt(log2Ceil(nRows).W)))
  val wrbypass         = Reg(Vec(nWrBypassEntries, Vec(bankWidth, UInt(3.W))))
  val wrbypass_enq_idx = RegInit(0.U(log2Ceil(nWrBypassEntries).W))

  val wrbypass_hits    = VecInit((0 until nWrBypassEntries) map { i =>
    !doing_reset &&
    wrbypass_tags(i) === update_tag &&
    wrbypass_idxs(i) === update_idx
  })
  val wrbypass_hit     = wrbypass_hits.reduce(_||_)
  val wrbypass_hit_idx = PriorityEncoder(wrbypass_hits)

  for (w <- 0 until bankWidth) {
    update_wdata(w).ctr   := Mux(io.update_alloc(w),
      Mux(io.update_taken(w), 4.U,
                              3.U
      ),
      Mux(wrbypass_hit,       inc_ctr(wrbypass(wrbypass_hit_idx)(w), io.update_taken(w)),
                              inc_ctr(io.update_old_ctr(w), io.update_taken(w))
      )
    )
    update_wdata(w).valid := true.B
    update_wdata(w).tag   := update_tag

    update_hi_wdata(w)    := io.update_u(w)(1)
    update_lo_wdata(w)    := io.update_u(w)(0)
  }

  when (io.update_mask.reduce(_||_)) {
    when (wrbypass_hits.reduce(_||_)) {
      wrbypass(wrbypass_hit_idx) := VecInit(update_wdata.map(_.ctr))
    } .otherwise {
      wrbypass     (wrbypass_enq_idx) := VecInit(update_wdata.map(_.ctr))
      wrbypass_tags(wrbypass_enq_idx) := update_tag
      wrbypass_idxs(wrbypass_enq_idx) := update_idx
      wrbypass_enq_idx := WrapInc(wrbypass_enq_idx, nWrBypassEntries)
    }
  }



}

class TageBranchPredictorBank(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  val base = Module(new BTBBranchPredictorBank(BoomBTBParams()))
  // val micro = Module(new MicroBTBBranchPredictorBank(
  //   BoomMicroBTBParams(nSets = 64, offsetSz = 13)
  // ))
  val micro = Module(new FAMicroBTBBranchPredictorBank(
    BoomFAMicroBTBParams(nWays = 16, offsetSz = 13)
  ))
  base.io.f1_kill := io.f1_kill
  base.io.f2_kill := io.f2_kill
  base.io.f3_kill := io.f3_kill
  micro.io.f1_kill := io.f1_kill
  micro.io.f2_kill := io.f2_kill
  micro.io.f3_kill := io.f3_kill

  base.io.f0_req := io.f0_req
  base.io.update := io.update
  base.io.update.bits.meta := io.update.bits.meta(base.metaSz-1,0)
  micro.io.f0_req := io.f0_req
  micro.io.update := io.update
  micro.io.update.bits.meta := io.update.bits.meta(base.metaSz+micro.metaSz-1,base.metaSz)

  io.resp.f1 := micro.io.resp.f1
  io.resp.f2 := base.io.resp.f2
  io.resp.f3 := base.io.resp.f3

  val f3_meta = Wire(new TageMeta)

  override val metaSz = base.metaSz + micro.metaSz + f3_meta.asUInt.getWidth
  require(metaSz <= bpdMaxMetaLength)

  def inc_u(u: UInt, alt_differs: Bool, mispredict: Bool): UInt = {
    Mux(!alt_differs, u,
    Mux(mispredict, Mux(u === 0.U, 0.U, u - 1.U),
                    Mux(u === 3.U, 3.U, u + 1.U)))
  }

  val tables = (0 until tageNTables) map { i =>
    Module(new TageTable(tageNSets(i), tageTagSz(i), tageHistoryLength(i)))
  }



  tables.map(_.io.f0_req  := io.f0_req)
  tables.map(_.io.f3_kill := f3_kill)
  val f3_resps = VecInit(tables.map(_.io.f3_resp))

  val s1_update_meta = (s1_update.bits.meta >> (base.metaSz + micro.metaSz)).asTypeOf(new TageMeta)
  val s1_update_mispredict_mask = UIntToOH(s1_update.bits.cfi_idx.bits) &
    Fill(bankWidth, s1_update.bits.cfi_mispredicted)

  val s1_update_mask  = WireInit((0.U).asTypeOf(Vec(tageNTables, Vec(bankWidth, Bool()))))
  val s1_update_u_mask  = WireInit((0.U).asTypeOf(Vec(tageNTables, Vec(bankWidth, UInt(1.W)))))

  val s1_update_taken   = Wire(Vec(tageNTables, Vec(bankWidth, Bool())))
  val s1_update_old_ctr = Wire(Vec(tageNTables, Vec(bankWidth, UInt(3.W))))
  val s1_update_alloc   = Wire(Vec(tageNTables, Vec(bankWidth, Bool())))
  val s1_update_u       = Wire(Vec(tageNTables, Vec(bankWidth, UInt(2.W))))

  s1_update_taken   := DontCare
  s1_update_old_ctr := DontCare
  s1_update_alloc   := DontCare
  s1_update_u       := DontCare


  for (w <- 0 until bankWidth) {
    var altpred = base.io.resp.f3(w).taken
    val final_altpred = WireInit(base.io.resp.f3(w).taken)
    var provided = false.B
    var provider = 0.U
    io.resp.f3(w).taken := base.io.resp.f3(w).taken

    for (i <- 0 until tageNTables) {
      val hit = f3_resps(i)(w).valid
      val ctr = f3_resps(i)(w).bits.ctr
      when (hit) {
        io.resp.f3(w).taken := Mux(ctr === 3.U || ctr === 4.U, altpred, ctr(2))
        final_altpred       := altpred
      }

      provided = provided || hit
      provider = Mux(hit, i.U, provider)
      altpred  = Mux(hit, f3_resps(i)(w).bits.ctr(2), altpred)
    }
    f3_meta.provider(w).valid := provided
    f3_meta.provider(w).bits  := provider
    f3_meta.alt_differs(w)    := final_altpred =/= io.resp.f3(w).taken
    f3_meta.provider_u(w)     := f3_resps(provider)(w).bits.u
    f3_meta.provider_ctr(w)   := f3_resps(provider)(w).bits.ctr

    // Create a mask of tables which did not hit our query, and also contain useless entries
    // and also uses a longer history than the provider
    val allocatable_slots = (
      VecInit(f3_resps.map(r => !r(w).valid && r(w).bits.u === 0.U)).asUInt &
      ~(MaskLower(UIntToOH(provider)) & Fill(tageNTables, provided))
    )
    val alloc_lfsr = random.LFSR(tageNTables)

    val first_entry = PriorityEncoder(allocatable_slots)
    val masked_entry = PriorityEncoder(allocatable_slots & alloc_lfsr)
    val alloc_entry = Mux(allocatable_slots(masked_entry),
      masked_entry,
      first_entry)

    f3_meta.allocate(w).valid := allocatable_slots =/= 0.U
    f3_meta.allocate(w).bits  := alloc_entry

    val update_was_taken = (s1_update.bits.cfi_idx.valid &&
                            (s1_update.bits.cfi_idx.bits === w.U) &&
                            s1_update.bits.cfi_taken)
    when (s1_update.bits.br_mask(w) && s1_update.valid) {
      when (s1_update_meta.provider(w).valid) {
        val provider = s1_update_meta.provider(w).bits

        s1_update_mask(provider)(w) := true.B
        s1_update_u_mask(provider)(w) := true.B

        val new_u = inc_u(s1_update_meta.provider_u(w),
                          s1_update_meta.alt_differs(w),
                          s1_update_mispredict_mask(w))
        s1_update_u      (provider)(w) := new_u
        s1_update_taken  (provider)(w) := update_was_taken
        s1_update_old_ctr(provider)(w) := s1_update_meta.provider_ctr(w)
        s1_update_alloc  (provider)(w) := false.B

      }
    }
  }
  when (s1_update.valid && s1_update.bits.cfi_mispredicted && s1_update.bits.cfi_idx.valid) {
    val idx = s1_update.bits.cfi_idx.bits
    val allocate = s1_update_meta.allocate(idx)
    when (allocate.valid) {
      s1_update_mask (allocate.bits)(idx) := true.B
      s1_update_taken(allocate.bits)(idx) := s1_update.bits.cfi_taken
      s1_update_alloc(allocate.bits)(idx) := true.B

      s1_update_u_mask(allocate.bits)(idx) := true.B
      s1_update_u     (allocate.bits)(idx) := 0.U

    } .otherwise {
      val provider = s1_update_meta.provider(idx)
      val decr_mask = Mux(provider.valid, ~MaskLower(UIntToOH(provider.bits)), 0.U)

      for (i <- 0 until tageNTables) {
        when (decr_mask(i)) {
          s1_update_u_mask(i)(idx) := true.B
          s1_update_u     (i)(idx) := 0.U
        }
      }
    }

  }


  for (i <- 0 until tageNTables) {
    for (w <- 0 until bankWidth) {
      tables(i).io.update_mask(w)    := RegNext(s1_update_mask(i)(w))
      tables(i).io.update_taken(w)   := RegNext(s1_update_taken(i)(w))
      tables(i).io.update_alloc(w)   := RegNext(s1_update_alloc(i)(w))
      tables(i).io.update_old_ctr(w) := RegNext(s1_update_old_ctr(i)(w))

      tables(i).io.update_u_mask(w) := RegNext(s1_update_u_mask(i)(w))
      tables(i).io.update_u(w)      := RegNext(s1_update_u(i)(w))
    }
    tables(i).io.update_pc    := RegNext(s1_update.bits.pc)
    tables(i).io.update_hist  := RegNext(s1_update.bits.hist)
  }


  io.f3_meta := Cat(f3_meta.asUInt, micro.io.f3_meta(micro.metaSz-1,0), base.io.f3_meta(base.metaSz-1, 0))
}
