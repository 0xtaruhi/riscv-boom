//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------

package boom
import Chisel._
import cde.{Parameters, Config, Knob, CDEMatchError}
import rocket._

class DefaultBOOMConfig extends Config (
   topDefinitions = {
      (pname,site,here) => pname match {

         // Top-Level
         case XLen => 64
         case FPUKey => Some(FPUConfig(
            divSqrt = false,
            sfmaLatency = 4,
            dfmaLatency = 4))
         case CoreInstBits => 32
         case UseCompressed => false

         // Uarch Performance Counters
         case NPerfEvents => 37
         case NPerfCounters => Knob("PERF_COUNTERS")

         // Superscalar Widths
         case FetchWidth => Knob("FETCH_WIDTH")
//         case DecodeWidth => here(FetchWidth)
//         case DispatchWidth => here(DecodeWidth)
         case RetireWidth => here(FetchWidth)

         // Data Structure Sizes
//         case NumRobEntries => Knob("ROB_ENTRIES")
//         case NumIssueSlotEntries => Knob("ISSUE_ENTRIES")
//         case NumLsuEntries=> Knob("LSU_ENTRIES")
//         case NumPhysRegisters => Knob("PHYS_REGISTERS")
//         case MaxBrCount => Knob("MAX_BR_COUNT")

         // Front-end
         case EnableBTB => true // for now, only gates off updates to BTB
         case EnableBTBContainsBranches => true // don't send branches to BTB (but let jumps be predicted)
         case BtbKey => BtbParameters(nEntries = 32, nRAS = 4, updatesOutOfOrder = true)

         case BoomKey => BoomCoreParams(
            numRobEntries = 48,
            issueParams = Seq(
               IssueParams(issueWidth=1, numEntries=20, iqType=IQT_MEM.litValue),
               IssueParams(issueWidth=2, numEntries=16, iqType=IQT_INT.litValue),
               IssueParams(issueWidth=1, numEntries=10, iqType=IQT_FP.litValue)),
            numIntPhysRegisters = 70,
            numFpPhysRegisters = 64,
            numLsuEntries = 16,
            maxBrCount = 8,
            enableBranchPredictor = true,
            gshare = Some(GShareParameters(enabled = true, history_length=13))
         )
      }
   },
   knobValues = {
      case "NTiles" => 1
      case "FETCH_WIDTH" => 2
//      case "ISSUE_WIDTH" => 3
//      case "ROB_ENTRIES" => 48
//      case "ISSUE_ENTRIES" => 20
//      case "LSU_ENTRIES" => 16
//      case "PHYS_REGISTERS" => 110
//      case "MAX_BR_COUNT" => 8
//      case "AGE_PRIORITY_ISSUE" => true
      case "L1D_MSHRS" => 2
      case "L1D_WAYS" => 4
      case "L1D_SETS" => 64
      case "L1I_WAYS" => 4
      case "L1I_SETS" => 64
      case "PERF_COUNTERS" => 6
  }
)

class WithNPerfCounters(n: Int) extends Config(
  knobValues = {case "PERF_COUNTERS" => n; case _ => throw new CDEMatchError })

