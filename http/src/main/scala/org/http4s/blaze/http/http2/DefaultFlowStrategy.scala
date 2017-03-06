package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.FlowStrategy.Increment

private class DefaultFlowStrategy(mySettings: Http2Settings) extends FlowStrategy {

  override def checkSession(session: SessionFlowControl): Int = {
    check(mySettings.initialWindowSize, session.sessionInboundWindow, session.sessionUnconsumedBytes)
  }

  override def checkStream(session: SessionFlowControl, stream: StreamFlowWindow): Increment = {
    val sess = checkSession(session)
    val stre = check(mySettings.initialWindowSize, stream.inboundWindow, stream.unconsumedBytes)
    Increment(sess, stre)
  }

  private def check(initialWindow: Int, currentWindow: Int, uncomsumed: Int): Int = {
    val unacked = initialWindow - currentWindow
    val unackedConsumed = unacked - uncomsumed
    if (unackedConsumed > initialWindow / 2) {
      // time to ack
      unackedConsumed
    } else {
      0
    }
  }
}