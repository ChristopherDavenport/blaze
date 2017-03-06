package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.http.{Http2ClientSession, HttpClientSession, HttpRequest}
import org.http4s.blaze.http.HttpClientSession.{ReleaseableResponse, Status}
import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}


private class Http2ClientConnectionImpl(
    mySettings: Http2Settings, // the settings of this side
    peerSettings: Http2Settings, // the settings of their side
    http2Encoder: Http2FrameEncoder,
    headerDecoder: HeaderDecoder,
    flowStrategy: FlowStrategy,
    executor: ExecutionContext)
  extends Http2ConnectionImpl(
    true,
    mySettings,
    peerSettings,
    http2Encoder,
    headerDecoder,
    flowStrategy,
    executor
  ) with Http2ClientSession
  with Http2ClientConnection
  with TailStage[ByteBuffer] {

  override protected def stageStartup(): Unit = {
    super.stageStartup()
    startSession()
  }

  override protected def sessionTerminated(): Unit =
    sendOutboundCommand(Command.Disconnect)

  override def name: String = "Http2ClientSessionImpl"

  override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = {
    logger.debug(s"Dispatching request: $request")
    val tail = new Http2ClientStage(request, executor)
    val head = newOutboundStream()
    LeafBuilder(tail).base(head)
    head.sendInboundCommand(Command.Connected)

    tail.result
  }

  /** Get the status of session */
  override def status: Status = {
    if (state == Http2Connection.Running) {
      if (activeStreamCount < peerSettings.maxInboundStreams) {
        HttpClientSession.Ready
      } else {
        HttpClientSession.Busy
      }
    } else {
      HttpClientSession.Closed
    }
  }

  // TODO: this could be more interesting
  override def quality: Double =
    1.0 - activeStreamCount.toDouble/peerSettings.maxInboundStreams.toDouble

  /** Close the session.
    *
    * This will generally entail closing the socket connection.
    */
  override def close(within: Duration): Unit = ???

  ///////////////////////////////////////////

  override protected def newInboundStream(streamId: Int): Option[LeafBuilder[StreamMessage]] = None

  // Need to be able to write data to the pipeline
  override protected def writeBytes(data: Seq[ByteBuffer]): Future[Unit] = channelWrite(data)

  override protected def readData(): Future[ByteBuffer] = channelRead()

  override protected def newHttp2Decoder(handler: Http20FrameHandler): Http20FrameDecoder =
    new Http20FrameDecoder(mySettings, handler)
}
