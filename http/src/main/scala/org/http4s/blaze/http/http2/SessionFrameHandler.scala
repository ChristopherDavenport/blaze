package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.Http2Exception._
import org.log4s.Logger

import scala.collection.mutable.Map

/** Receives frames from the `Http20FrameDecoder`
  *
  * Concurrency is not controlled by this type; it is expected that thread safety
  * will be managed by the [[Http2ConnectionImpl]].
  */
private abstract class SessionFrameHandler[StreamState <: Http2StreamState](
    mySettings: Http2Settings,
    headerDecoder: HeaderDecoder,
    activeStreams: Map[Int, StreamState],
    sessionFlowControl: SessionFlowControl,
    idManager: StreamIdManager)
  extends HeaderAggregatingFrameHandler(mySettings, headerDecoder) {

  protected val logger: Logger

  /** Optionally create and initialize a new inbound stream
    *
    * `None` signals that the stream is to be refused with a RST(REFUSED_STREAM) reply.
    *
    * @param streamId streamId associated with the new stream
    */
  protected def newInboundStream(streamId: Int): Option[StreamState]

  /** A Ping frame has been received, either new or an ping ACK */
  override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result

  /** Handle a valid and complete PUSH_PROMISE frame */
  protected def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result

  // Concrete methods ////////////////////////////////////////////////////////////////////

  override def onCompleteHeadersFrame(streamId: Int, priority: Option[Priority], endStream: Boolean, headers: Headers): Http2Result = {
    activeStreams.get(streamId) match {
      case Some(stream) => stream.invokeInboundHeaders(priority, endStream, headers)
      case None =>
        if (streamId == 0) {
          PROTOCOL_ERROR.goaway(s"Illegal stream ID for headers frame: 0").toError
        } else if (idManager.observeInboundId(streamId)) {
          newInboundStream(streamId) match {
            case Some(head) => head.invokeInboundHeaders(priority, endStream, headers)
            case None =>  // stream rejected
              REFUSED_STREAM.rst(streamId).toError
          }
        } else if (idManager.isIdleOutboundId(streamId)) {
          PROTOCOL_ERROR.goaway(s"Received HEADERS from on idle outbound stream id $streamId").toError
        } else {
          STREAM_CLOSED.rst(streamId).toError
        }
    }
  }

  // See https://tools.ietf.org/html/rfc7540#section-6.6 and section-8.2 for the list of rules
  override def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: Headers): Http2Result = {
    if (!idManager.isClient)
      PROTOCOL_ERROR.goaway(s"Server received PUSH_PROMISE frame for stream $streamId").toError
    else if (!mySettings.pushEnabled)
      PROTOCOL_ERROR.goaway("Received PUSH_PROMISE frame then they are disallowed").toError
    else if (idManager.isIdleOutboundId(streamId))
      PROTOCOL_ERROR.goaway(s"Received PUSH_PROMISE for associated to an idle stream ($streamId)").toError
    else if (!idManager.isInboundId(promisedId))
      PROTOCOL_ERROR.goaway(s"Received PUSH_PROMISE frame with illegal stream id: $promisedId").toError
    else if (!idManager.observeInboundId(promisedId))
      PROTOCOL_ERROR.goaway("Received PUSH_PROMISE frame on non-idle stream").toError
    else handlePushPromise(streamId, promisedId, headers)
  }

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
      // We remove it from the active streams first so that we don't send our own RST_STREAM
      // frame as a response. https://tools.ietf.org/html/rfc7540#section-5.4.2
      activeStreams
        .remove(streamId)
        .foreach(_.closeWithError(Some(Http2Exception.errorGenerator(code).rst(streamId))))

      Continue
    }
  }

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

  private[this] def onSessionFlowUpdate(count: Int): Unit = {
    logger.debug(s"Session flow update: $count")
    // We need to check the streams to see if any can now make forward progress
    activeStreams.values.foreach(_.outboundFlowAcked())
  }

  private[this] def onStreamFlowUpdate(stream: StreamState, count: Int): Unit = {
    logger.debug(s"Stream(${stream.streamId}) flow update: $count")
    // We can now check this stream to see if it can make forward progress
    stream.outboundFlowAcked()
  }
}