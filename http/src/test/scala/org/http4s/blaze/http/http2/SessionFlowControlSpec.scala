package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.Http2Settings.DefaultSettings
import org.specs2.mutable.Specification

class SessionFlowControlSpec extends Specification {

  class TestSessionFlowControl(inbound: Http2Settings, outbound: Http2Settings)
    extends SessionFlowControl(inbound, outbound) {
    var sessionConsumed: Int = 0

    var streamThatConsumed: StreamFlowWindow = null
    var streamConsumed: Int = 0

    override protected def onSessonBytesConsumed(consumed: Int): Unit = {
      this.sessionConsumed = consumed
    }

    override protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit = {
      streamThatConsumed = stream
      streamConsumed = consumed
    }
  }

  def flowControl(): TestSessionFlowControl = {
    val settings = Http2Settings.default()
    flowControl(settings, settings)
  }

  def flowControl(inbound: Http2Settings, outbound: Http2Settings): TestSessionFlowControl = {
    new TestSessionFlowControl(inbound, outbound)
  }

  "SessionFlowControl session inbound window" should {
    "Start with the http2 default flow windows" in {
      val flow = flowControl()
      flow.sessionInboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
      flow.sessionOutboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
    }

    "bytes consumed" in {
      val flow = flowControl()
      flow.sessionUnconsumedBytes must_== 0
      flow.sessionInboundObserved(10) must beTrue
      flow.sessionUnconsumedBytes must_== 10

      flow.sessionConsumed must_== 0
      flow.sessionInboundConsumed(1)

      flow.sessionConsumed must_== 1
      flow.sessionUnconsumedBytes must_== 9
    }
  }

  "SessionFlowControl session inbound window" should {
    "Zero session inbound withdrawals don't deplete the window" in {
      val flow = flowControl()
      flow.sessionInboundObserved(0) must beTrue
      flow.sessionInboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
    }

    "session inbound withdrawals less than the window are successful" in {
      val flow = flowControl()
      flow.sessionInboundObserved(1) must beTrue
      flow.sessionInboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE - 1
    }

    "session inbound withdrawals greater than the window result in false and don't deplete the window" in {
      val flow = flowControl()
      flow.sessionInboundObserved(DefaultSettings.INITIAL_WINDOW_SIZE + 1) must beFalse
      flow.sessionInboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
    }

    "session inbound withdrawals equal than the window are successful" in {
      val flow = flowControl()
      flow.sessionInboundObserved(DefaultSettings.INITIAL_WINDOW_SIZE) must beTrue
      flow.sessionInboundWindow must_== 0
    }

    "session inbound deposits update the window" in {
      val flow = flowControl()
      flow.sessionInboundAcked(1)
      flow.sessionInboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE + 1
    }

    "session inbound deposits update the window to Int.MaxValue" in {
      val flow = flowControl()
      flow.sessionInboundAcked(Int.MaxValue - flow.sessionInboundWindow)
      flow.sessionInboundWindow must_== Int.MaxValue
    }
  }

  "SessionFlowControl session outbound window" should {
    "session outbound deposits update the window" in {
      val flow = flowControl()
      flow.sessionOutboundAcked(1) must_== Continue
      flow.sessionOutboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE + 1
    }

    "session outbound deposits update the window to Int.MaxValue" in {
      val flow = flowControl()
      flow.sessionOutboundAcked(Int.MaxValue - flow.sessionOutboundWindow) must_== Continue
      flow.sessionOutboundWindow must_== Int.MaxValue
    }

    // https://tools.ietf.org/html/rfc7540#section-6.9
    "session outbound deposits of 0 throw Http2Exception with flag FLOW_CONTROL" in {
      val flow = flowControl()
      flow.sessionOutboundAcked(0) must be like {
        case Error(Http2SessionException(code, name)) => code must_== Http2Exception.PROTOCOL_ERROR.code
      }
    }

    // https://tools.ietf.org/html/rfc7540#section-6.9.1
    "session outbound deposits that overflow the window throw Http2Exception with flag FLOW_CONTROL" in {
      val flow = flowControl()
      val overflowBy1 = Int.MaxValue - flow.sessionOutboundWindow + 1
      flow.sessionOutboundAcked(overflowBy1) must be like {
        case Error(Http2SessionException(code, name)) => code must_== Http2Exception.FLOW_CONTROL_ERROR.code
      }
    }
  }

  ////////////////// Streams ////////////////////////////
  "SessionFlowControl.StreamFlowWindow inbound window" should {
    "Start with the config initial flow windows" in {
      val inbound = Http2Settings.default()
      inbound.initialWindowSize = 2
      val outbound = Http2Settings.default()
      outbound.initialWindowSize = 1
      val flow = flowControl(inbound, outbound).newStreamFlowWindow(1)


      flow.inboundWindow must_== 2
      flow.outboundWindow must_== 1
    }

    "bytes consumed" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)

      session.sessionUnconsumedBytes must_== 0
      flow.unconsumedBytes must_== 0

      flow.inboundObserved(10) must beTrue

      session.sessionUnconsumedBytes must_== 10
      flow.unconsumedBytes must_== 10

      session.sessionConsumed must_== 0
      session.streamConsumed must_== 0
      flow.inboundConsumed(1)

      (session.streamThatConsumed eq flow) must beTrue
      session.sessionConsumed must_== 1
      session.streamConsumed must_== 1

      session.sessionUnconsumedBytes must_== 9
      flow.unconsumedBytes must_== 9
    }
  }

  "SessionFlowControl.StreamFlowWindow inbound window" should {
    "zero inbound withdrawals don't deplete the window" in {
      val flow = flowControl().newStreamFlowWindow(1)
      flow.inboundObserved(0) must beTrue
      flow.inboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
    }

    "inbound withdrawals less than the window are successful" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)
      flow.inboundObserved(1) must beTrue

      flow.inboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE - 1
      session.sessionInboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE - 1
    }

    "inbound withdrawals greater than the window result in false and don't deplete the window" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)
      flow.inboundObserved(DefaultSettings.INITIAL_WINDOW_SIZE + 1) must beFalse

      flow.inboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
      session.sessionInboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
    }

    "inbound withdrawals equal than the window are successful" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)

      flow.inboundObserved(DefaultSettings.INITIAL_WINDOW_SIZE) must beTrue
      flow.inboundWindow must_== 0
      session.sessionInboundWindow must_== 0
    }

    "inbound deposits update the window" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)

      flow.inboundAcked(1)
      flow.inboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE + 1
      session.sessionInboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
    }

    "inbound deposits update the window to Int.MaxValue" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)

      flow.inboundAcked(Int.MaxValue - flow.inboundWindow)
      session.sessionInboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
    }
  }

  "SessionFlowControlStreamFlowWindow outbound window" should {
    "deposits update the window" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)

      flow.outboundAcked(1) must_== Continue

      flow.outboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE + 1
      session.sessionOutboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
    }

    "outbound deposits update the window to Int.MaxValue" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)

      flow.outboundAcked(Int.MaxValue - flow.outboundWindow) must_== Continue

      flow.outboundWindow must_== Int.MaxValue
      session.sessionOutboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
    }

    // https://tools.ietf.org/html/rfc7540#section-6.9
    "outbound deposits of 0 throw Http2Exception with flag FLOW_CONTROL" in {
      val flow = flowControl().newStreamFlowWindow(1)

      flow.outboundAcked(0) must be like {
        case Error(Http2SessionException(code, name)) => code must_== Http2Exception.PROTOCOL_ERROR.code
      }
    }

    // https://tools.ietf.org/html/rfc7540#section-6.9.1
    "outbound deposits that overflow the window throw Http2Exception with flag FLOW_CONTROL" in {
      val flow = flowControl().newStreamFlowWindow(1)

      val overflowBy1 = Int.MaxValue - flow.outboundWindow + 1
      flow.outboundAcked(overflowBy1) must be like {
        case Error(Http2SessionException(code, name)) => code must_== Http2Exception.FLOW_CONTROL_ERROR.code
      }
    }

    "outbound withdrawal of 0 don't effect the windows" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)

      flow.outboundReceived(0) must_== 0
      flow.outboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
      session.sessionOutboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
    }

    "outbound withdrawals are accounted for" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)

      flow.outboundReceived(DefaultSettings.INITIAL_WINDOW_SIZE) must_== DefaultSettings.INITIAL_WINDOW_SIZE
      flow.outboundWindow must_== 0
      session.sessionOutboundWindow must_== 0
    }

    "outbound withdrawals that exceed the window" in {
      val session = flowControl()
      val flow = session.newStreamFlowWindow(1)

      flow.outboundReceived(DefaultSettings.INITIAL_WINDOW_SIZE + 1) must_== DefaultSettings.INITIAL_WINDOW_SIZE
      flow.outboundWindow must_== 0
      session.sessionOutboundWindow must_== 0
    }

    "outbound withdrawals that exceed the window consume the max from stream or session" in {
      val config = Http2Settings.default()
      config.initialWindowSize = 1
      val session = flowControl(config, config)
      val flow = session.newStreamFlowWindow(1)

      flow.outboundReceived(10) must_== 1
      flow.outboundWindow must_== 0
      session.sessionOutboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE - 1
    }

    "outbound withdrawals from multiple streams" in {
      val session = flowControl()
      val flow1 = session.newStreamFlowWindow(1)
      val flow2 = session.newStreamFlowWindow(2)

      flow1.outboundReceived(10) must_== 10
      flow1.outboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE - 10
      flow2.outboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE
      session.sessionOutboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE - 10

      flow2.outboundReceived(20) must_== 20
      flow2.outboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE - 20
      flow1.outboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE - 10
      session.sessionOutboundWindow must_== DefaultSettings.INITIAL_WINDOW_SIZE - 30
    }
  }
}
