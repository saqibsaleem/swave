/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.fanout

import swave.core.Stage
import swave.core.impl.Outport
import swave.core.impl.stages.FanOutStage

private[core] final class FanOutRoundRobinStage(eagerCancel: Boolean) extends FanOutStage {

  def kind = Stage.Kind.FanOut.RoundRobin(eagerCancel)

  type OutportCtx = FanOutStage.SimpleOutportContext

  protected def createOutportCtx(out: Outport, tail: OutportCtx): OutportCtx =
    new FanOutStage.SimpleOutportContext(out, tail)
}
