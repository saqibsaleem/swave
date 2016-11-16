/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}

class CouplingSpec extends FreeSpec with Matchers {

  "the examples in the `couplings` chapter should work as expected" - {

    "fibonacci" in {
      //#fibonacci
      import swave.core._
      implicit val env = StreamEnv()

      val c = Coupling[Int]

      def fibonacciNumbers =
        Spout(0, 1)
          .concat(c.out)
          .fanOutBroadcast(eagerCancel = true)
            .sub.buffer(2, Buffer.RequestStrategy.Always).sliding(2).map(_.sum).to(c.in)
            .subContinue

      fibonacciNumbers
        .take(8)
        .drainToList(limit = 100)
        .value.get.get shouldEqual List(0, 1, 1, 2, 3, 5, 8, 13)
      //#fibonacci
    }
  }
}