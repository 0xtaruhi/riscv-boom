
package object BOOM extends 
   BOOM.constants.BOOMDebugConstants with
   BOOM.constants.LoadStoreUnitConstants with
   BOOM.constants.BrPredConstants with
   BOOM.constants.ScalarOpConstants with
   BOOM.constants.ExcCauseConstants with 
   BOOM.constants.InterruptConstants with
   BOOM.constants.RISCVConstants 
{
   val START_ADDR = 0x2000
}
