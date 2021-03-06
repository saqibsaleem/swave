/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.collection.mutable
import swave.core.impl.stages.spout.SubSpoutStage
import swave.core.impl.{Inport, Outport}
import swave.core.impl.stages.InOutStage
import swave.core.macros._
import swave.core._

// format: OFF
@StageImplementation
private[core] final class PrefixAndTailStage(prefixSize: Int, prefixBuilder: mutable.Builder[Any, AnyRef])
  extends InOutStage {

  requireArg(prefixSize > 0, "`prefixSize` must be > 0")

  def kind = Stage.Kind.InOut.PrefixAndTail(prefixSize)

  connectInOutAndSealWith { (in, out) ⇒
    region.impl.registerForXStart(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(prefixSize.toLong)
        assemblingPrefix(prefixSize.toLong, false)
      })

    /**
      * @param pending       number of prefix elements already requested from upstream but not yet received, > 0
      * @param mainRequested true if the main downstream has already requested at least one element
      */
    def assemblingPrefix(pending: Long, mainRequested: Boolean): State = {
      requireState(pending > 0)
      state(
        request = (_, _) ⇒ assemblingPrefix(pending, true),
        cancel = stopCancelF(in),

        onNext = (elem, _) ⇒ {
          prefixBuilder += elem
          if (pending == 1) {
            if (mainRequested) emit()
            else awaitingDemand()
          } else assemblingPrefix(pending - 1, mainRequested)
        },

        onComplete = _ ⇒ handleOnComplete(),
        onError = stopErrorF(out))
    }

    /**
      * Prefix fully received, awaiting demand from the main downstream.
      */
    def awaitingDemand(): State = state(
      request = (_, _) => emit(),
      cancel = stopCancelF(in),
      onComplete = _ ⇒ handleOnComplete(),
      onError = stopErrorF(out))

    def emit() = {
      val sub = new SubSpoutStage(this)
      emitPrefixWith(new Spout(sub))
      sub.xEvent(SubSpoutStage.EnableSubStreamStartTimeout)
      out.onComplete()
      draining(in, sub)
    }

    def handleOnComplete() = {
      emitPrefixWith(Spout.empty[AnyRef])
      stopComplete(out)
    }

    def emitPrefixWith(spout: Spout[_]) = {
      val prefix = prefixBuilder.result()
      prefixBuilder.clear()
      out.onNext(prefix -> spout)
    }

    awaitingXStart()
  }

  /**
    * Simply forwarding elements from upstream to the tail sub downstream.
    *
    * @param in  the active upstream
    * @param sub the active tail sub downstream
    */
  def draining(in: Inport, sub: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(sub),
    onComplete = stopCompleteF(sub),
    onError = stopErrorF(sub))
}
