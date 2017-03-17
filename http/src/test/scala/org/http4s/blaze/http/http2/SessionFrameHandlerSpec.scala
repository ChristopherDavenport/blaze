package org.http4s.blaze.http.http2

import scala.language.reflectiveCalls
import java.nio.ByteBuffer

import org.http4s.blaze.http.Headers
import org.http4s.blaze.http.http2.Http2Exception.ErrorGenerator
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

import scala.collection.mutable

class SessionFrameHandlerSpec extends Specification {

  private class Ctx(isClient: Boolean) {
    val tools = new Http2MockTools(isClient)

    val activeStreams: mutable.HashMap[Int, MockHttp2StreamState] = new mutable.HashMap[Int, MockHttp2StreamState]()

    class MockSessionFrameHandler extends SessionFrameHandler[MockHttp2StreamState](
      tools.mySettings,
      tools.headerDecoder,
      activeStreams,
      tools.flowControl,
      tools.idManager) {
      override protected def newInboundStream(streamId: Int): Option[MockHttp2StreamState] = ???
      override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = ???
      override protected def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result = ???
      override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = ???
      override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = ???
    }
  }

  def connectionError(gen: ErrorGenerator): PartialFunction[Http2Result, MatchResult[_]] = {
    case Error(e: Http2SessionException) => e.code must_== gen.code
  }

  val ConnectionProtoError: PartialFunction[Http2Result, MatchResult[_]] = connectionError(Http2Exception.PROTOCOL_ERROR)

  "SessionFrameHandler" >> {

    "on HEADERS frame" >> {
      class HandlerCtx(isClient: Boolean) extends Ctx(isClient) {
        val handler = new MockSessionFrameHandler {
          var observedStreamId: Option[Int] = None
          override protected def newInboundStream(streamId: Int): Option[MockHttp2StreamState] = {
            observedStreamId = Some(streamId)
            None
          }
        }
      }

      "result in a protocol for stream ID 0" >> {
        val ctx = new HandlerCtx(false)
        ctx.handler.onCompleteHeadersFrame(0, None, true, Nil) must beLike(ConnectionProtoError)
        ctx.handler.observedStreamId must beNone
      }

      "result in a PROTOCOL_ERROR for idle outbound stream" >> {
        val ctx = new HandlerCtx(false)
        ctx.handler.onCompleteHeadersFrame(2, None, true, Nil) must beLike(ConnectionProtoError)
        ctx.handler.observedStreamId must beNone
      }

      "result in a Http2StreamException with code STREAM_CLOSED for closed outbound stream" >> {
        val ctx = new HandlerCtx(false)
        val Some(id) = ctx.tools.idManager.takeOutboundId()
        ctx.handler.onCompleteHeadersFrame(id, None, true, Nil) must beLike { case Error(err: Http2StreamException) =>
          err.code must_== Http2Exception.STREAM_CLOSED.code
        }
        ctx.handler.observedStreamId must beNone
      }

      "initiate a new stream for idle inbound stream (server)" >> {
        val ctx = new HandlerCtx(false)
        ctx.handler.onCompleteHeadersFrame(1, None, true, Nil) must beLike { case Error(err) =>
          err.code must_== Http2Exception.REFUSED_STREAM.code
        }
        ctx.handler.observedStreamId must beSome(1)
        ctx.tools.idManager.lastInboundStream must_== 1
      }
    }

    "on PUSH_PROMISE frame" >> {
      case class PushPromise(streamId: Int, promisedId: Int, headers: Headers)
      class HandlerCtx(isClient: Boolean) extends Ctx(isClient) {
        val handler = new MockSessionFrameHandler {
          var result: Option[PushPromise] = None
          override protected def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result = {
            result = Some(PushPromise(streamId, promisedId, headers))
            Continue
          }
        }
      }

      "connection PROTOCOL_ERROR" >> {
        "disabled by settings results" >> {
          val ctx = new HandlerCtx(true)
          ctx.tools.mySettings.pushEnabled = false
          ctx.handler.onCompletePushPromiseFrame(1, 2, Nil) must beLike(ConnectionProtoError)
        }

        "associated stream is idle" >> {
          val ctx = new HandlerCtx(true)
          ctx.handler.onCompletePushPromiseFrame(1, 2, Nil) must beLike(ConnectionProtoError)
        }

        "promised id is not in idle state" >> {
          val ctx = new HandlerCtx(true)
          val Some(id) = ctx.tools.idManager.takeOutboundId()
          ctx.tools.idManager.observeInboundId(id + 1)
          ctx.handler.onCompletePushPromiseFrame(id, id + 1, Nil) must beLike(ConnectionProtoError)
        }

        "received by server" >> {
          val ctx = new HandlerCtx(isClient = false)
          val Some(id) = ctx.tools.idManager.takeOutboundId()
          ctx.handler.onCompletePushPromiseFrame(id, id + 1, Nil) must beLike(ConnectionProtoError)
        }
      }

      "accept for stream in open state" >> {
        val ctx = new HandlerCtx(true)
        val Some(id) = ctx.tools.idManager.takeOutboundId()
        ctx.handler.onCompletePushPromiseFrame(id, id + 1, Nil) must_== Continue
        ctx.handler.result must beSome(PushPromise(id, id + 1, Nil))
      }
    }

    "on DATA frame" >> {
      def bytes(i: Int): ByteBuffer = ByteBuffer.wrap(new Array(i))
      class HandlerCtx(isClient: Boolean) extends Ctx(isClient) {
        val handler = new MockSessionFrameHandler
      }

      val FlowControlError = connectionError(Http2Exception.FLOW_CONTROL_ERROR)

      "connection PROTOCOL_ERROR" >> {
        "idle stream" >> {
          val ctx = new HandlerCtx(true)
          ctx.handler.onDataFrame(1, true, bytes(10), 10) must beLike(ConnectionProtoError)
          ctx.handler.onDataFrame(2, true, bytes(10), 10) must beLike(ConnectionProtoError)
        }

        "session flow window overflow" >> {
          val ctx = new HandlerCtx(true)
          ctx.tools.idManager.observeInboundId(1)
          assert(ctx.tools.flowControl.sessionInboundObserved(ctx.tools.flowControl.sessionInboundWindow))
          ctx.handler.onDataFrame(1, true, bytes(10), 10) must beLike(FlowControlError)
        }

        "stream flow window overflow" >> {
          val ctx = new HandlerCtx(true)
          ctx.tools.idManager.observeInboundId(2)
          ctx.activeStreams.put(2, ctx.tools.newStream(2))
          ctx.tools.flowControl.sessionInboundAcked(100) // give some space in the session
          val w = ctx.activeStreams(2).flowWindow
          assert(w.inboundObserved(w.inboundWindow))
          assert(w.inboundWindow == 0)
          ctx.handler.onDataFrame(2, true, bytes(10), 10) must beLike(FlowControlError)
        }
      }

      "stream STREAM_CLOSED on closed stream" >> {
        "session flow window overflow" >> {
          val ctx = new HandlerCtx(true)
          ctx.tools.idManager.observeInboundId(2)
          val w = ctx.tools.flowControl.sessionInboundWindow
          ctx.handler.onDataFrame(2, true, bytes(10), 10) must beLike {
            case Error(e: Http2StreamException) => e.code must_== Http2Exception.STREAM_CLOSED.code
          }
          // Still count the flow bytes against the session
          ctx.tools.flowControl.sessionInboundWindow must_== w - 10
        }
      }

      "give data to a open stream with available flow window" >> {
        val ctx = new HandlerCtx(true)
        ctx.tools.idManager.observeInboundId(2)
        val stream = ctx.tools.newStream(2)
        ctx.activeStreams.put(2, stream)
        val sessionWindow = ctx.tools.flowControl.sessionInboundWindow

        val w = ctx.activeStreams(2).flowWindow
        val streamWindow = w.inboundWindow
        ctx.handler.onDataFrame(2, true, bytes(10), 10) must_== Continue

        ctx.tools.flowControl.sessionInboundWindow must_== sessionWindow - 10
        w.inboundWindow must_== streamWindow - 10
      }
    }
  }
}
