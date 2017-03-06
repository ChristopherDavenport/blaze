package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.Http2Exception._

import scala.collection.Map

/** Receives frames from the `Http20FrameDecoder`
  *
  * Concurrency is not controlled; it is expected that these operations will happen
  * inside t thread safe environment managed by the [[SessionImpl]].
  */
private abstract class SessionFrameHandler[StreamState <: Http2StreamState](
    mySettings: Http2Settings,
    headerDecoder: HeaderDecoder,
    activeStreams: Map[Int, StreamState], // This could be replaced with a method
    sessionFlowControl: SessionFlowControl,
    idManager: StreamIdManager)
  extends HeaderAggregatingFrameHandler(mySettings, headerDecoder) {

  /** Called when a session window update has occurred to allow the session to attempt
    * forward progress on any streams that may have been blocked for flow control reasons.
    *
    * @note if the flow control state is updated before this method is called, so if this
    *       method throws an `Exception`, the state has still been mutated.
    */
  def onSessionFlowUpdate(count: Int): Unit

  /** Called when a stream window update has occurred to allow the session to attempt
    * forward progress on the stream that may have been blocked for flow control reasons.
    *
    * @note if the flow control state is updated before this method is called, so if this
    *       method throws an `Exception`, the state has still been mutated.
    */
  def onStreamFlowUpdate(stream: StreamState, count: Int): Unit

  /** Optionally create and initialize a new inbound stream
    *
    * `None` signals that the stream is to be refused with a RST(REFUSED_STREAM) reply.
    *
    * @param streamId streamId associated with the new stream
    */
  protected def newInboundStream(streamId: Int): Option[StreamState]

  /** A Ping frame has been received, either new or an ping ACK */
  override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result

  // Concrete methods ////////////////////////////////////////////////////////////////////

  override def onCompleteHeadersFrame(streamId: Int, priority: Option[Priority], endStream: Boolean, headers: Headers): Http2Result = {
    activeStreams.get(streamId) match {
      case Some(stream) => stream.invokeInboundHeaders(priority, endStream, headers)
      case None =>
        if (idManager.observeInboundId(streamId)) {
          newInboundStream(streamId) match {
            case Some(head) => head.invokeInboundHeaders(priority, endStream, headers)
            case None =>  // stream rejected
              REFUSED_STREAM.rst(streamId).toError
          }

        } else {
          STREAM_CLOSED.rst(streamId).toError
        }
    }
  }

  override def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: Headers): Http2Result = ???

  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flow: Int): Http2Result = {
    activeStreams.get(streamId) match {
      // the stream will deal with updating the flow windows
      case Some(stream) => stream.invokeInboundData(isLast, data, flow)
      case None =>
        if (!sessionFlowControl.sessionInboundObserved(flow)) {
          val msg = s"data frame for inactive stream (id $streamId) overflowed session flow window. Size: $flow."
          FLOW_CONTROL_ERROR.goaway(msg).toError
        } else if (idManager.isIdleInboundId(streamId)) {
          PROTOCOL_ERROR.goaway(s"DATA on uninitialized stream ($streamId)").toError
        } else {
          // Message for a closed stream: Send a RST_STREAM
          STREAM_CLOSED.rst(streamId).toError
        }
    }
  }

  // TODO: what would priority handling look like?
  override def onPriorityFrame(streamId: Int, priority: Priority): Http2Result = Continue

  // https://tools.ietf.org/html/rfc7540#section-6.4
  override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = {
    if (idManager.isIdleOutboundId(streamId) || idManager.isIdleInboundId(streamId))
      PROTOCOL_ERROR.goaway(s"RST_STREAM for idle stream id $streamId").toError
    else {
      activeStreams.get(streamId).foreach(_.streamReset())
      Continue
    }
  }

//  override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = ???
//
//  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = ???

  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = {
    if (streamId == 0) {
      val result = sessionFlowControl.sessionOutboundAcked(sizeIncrement)
      if (result.success) {
        onSessionFlowUpdate(sizeIncrement)
      }
      result
    }
    else if (idManager.isIdleInboundId(streamId) || idManager.isIdleOutboundId(streamId)) {
      PROTOCOL_ERROR.goaway(s"WINDOW_UPDATE on uninitialized stream ($streamId)").toError
    }
    else activeStreams.get(streamId) match {
      case None => Continue // nop
      case Some(stream) =>
        val result = stream.flowWindow.outboundAcked(sizeIncrement)
        if (result.success) {
          onStreamFlowUpdate(stream, sizeIncrement)
        }
        result
    }
  }
}