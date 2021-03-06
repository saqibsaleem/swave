/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.flatten

import scala.util.control.NonFatal
import swave.core.impl._
import swave.core.impl.stages.InOutStage
import swave.core.impl.stages.drain.SubDrainStage
import swave.core.impl.util.InportAnyRefList
import swave.core.macros._
import swave.core.util._
import swave.core.{Stage, Streamable}

// format: OFF
@StageImplementation(fullInterceptions = true)
private[core] final class FlattenConcatStage(streamable: Streamable.Aux[Any, AnyRef], parallelism: Int)
  extends InOutStage {

  requireArg(parallelism > 0, "`parallelism` must be > 0")

  def kind = Stage.Kind.Flatten.Concat(parallelism)

  connectInOutAndSealWith { (in, out) ⇒
    region.impl.registerForXStart(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(parallelism.toLong)
        active(InportAnyRefList.empty, 0)
      })

    /**
     * Main upstream is active.
     *
     * @param subs        subs from which we are awaiting an onSubscribe (value == null) or to which we are already
     *                    subscribed (value != null), may be empty
     * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
     */
    def active(subs: InportAnyRefList, remaining: Long): State = {
      requireState(remaining >= 0)
      state(
        onSubscribe = sub ⇒ {
          markAsSubscribed(subs find_! sub)
          try {
            region.sealAndStart(sub.stageImpl)
            if ((subs.in eq sub) && remaining > 0) sub.request(remaining)
            active(subs, remaining)
          } catch {
            case NonFatal(e) =>
              in.cancel()
              cancelAll(subs)
              stopError(e, out)
          }
        },

        request = (n, _) ⇒ {
          if (isSubscribed(subs)) subs.in.request(n.toLong)
          active(subs, remaining ⊹ n)
        },

        cancel = _ ⇒ {
          cancelAll(subs)
          stopCancel(in)
        },

        onNext = (elem, from) ⇒ {
          if (from ne in) {
            out.onNext(elem)
            active(subs, remaining - 1)
          } else active(subs :+ subscribeSubDrain(elem), remaining)
        },

        onComplete = from ⇒ {
          if (from ne in) {
            in.request(1) // a sub completed, so we immediately request the next one
            requireState(subs.nonEmpty)
            val newSubs =
              if (subs.in eq from) {
                // if the current sub completed
                if (isSubscribed(subs.tail) && remaining > 0) // and we have the next one ready
                  subs.tail.in.request(remaining) // retarget demand
                subs.tail
              } else subs remove_! from // if a non-current sub completed we simply remove it from the list
            active(newSubs, remaining)
          } else if (subs.nonEmpty) activeUpstreamCompleted(out, subs, remaining)
          else stopComplete(out)
        },

        onError = (e, from) ⇒ {
          if (from ne in) in.cancel()
          cancelAll(subs, except = from)
          stopError(e, out)
        })
    }

    def subscribeSubDrain(elem: AnyRef): SubDrainStage = {
      val sub = new SubDrainStage(this)
      streamable(elem).inport.subscribe()(sub)
      sub
    }

    awaitingXStart()
  }

  /**
   * Main upstream completed.
   *
   * @param subs        subs from which we are awaiting an onSubscribe (value == null) or to which we are already
   *                    subscribed (value != null), non-empty
   * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
   */
  def activeUpstreamCompleted(out: Outport, subs: InportAnyRefList, remaining: Long): State = {
    requireState(subs.nonEmpty && remaining >= 0)
    state(
      onSubscribe = sub ⇒ {
        markAsSubscribed(subs find_! sub)
        try {
          region.sealAndStart(sub.stageImpl)
          if ((subs.in eq sub) && remaining > 0) sub.request(remaining)
          activeUpstreamCompleted(out, subs, remaining)
        } catch {
          case NonFatal(e) =>
            cancelAll(subs)
            stopError(e, out)
        }
      },

      request = (n, _) ⇒ {
        if (isSubscribed(subs)) subs.in.request(n.toLong)
        activeUpstreamCompleted(out, subs, remaining ⊹ n)
      },

      cancel = _ ⇒ stopCancel(subs),

      onNext = (elem, _) ⇒ {
        out.onNext(elem)
        activeUpstreamCompleted(out, subs, remaining - 1)
      },

      onComplete = from ⇒ {
        val newSubs =
          if (subs.in eq from) {
            // if the current sub completed
            if (isSubscribed(subs.tail) && remaining > 0) // and we have the next one ready
              subs.tail.in.request(remaining) // retarget demand
            subs.tail
          } else subs remove_! from // if a non-current sub completed we simply remove it from the list
        if (newSubs.isEmpty) stopComplete(out)
        else activeUpstreamCompleted(out, newSubs, remaining)
      },

      onError = (e, from) ⇒ {
        cancelAll(subs, except = from)
        stopError(e, out)
      })
  }

  private def markAsSubscribed(subs: InportAnyRefList) = subs.value = this
  private def isSubscribed(subs: InportAnyRefList) = subs.nonEmpty && (subs.value ne null)
}