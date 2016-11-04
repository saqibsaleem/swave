/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.PipeElem
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class MapStage(f: AnyRef ⇒ AnyRef) extends InOutStage with PipeElem.InOut.Map {

  def pipeElemType: String = "map"
  def pipeElemParams: List[Any] = f :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport): State = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      var funError: Throwable = null
      val mapped = try f(elem) catch { case NonFatal(e) => { funError = e; null } }
      if (funError eq null) {
        out.onNext(mapped)
        stay()
      } else {
        in.cancel()
        stopError(funError, out)
      }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}