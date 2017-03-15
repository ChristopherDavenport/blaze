package org.http4s.blaze.http.http2

import org.http4s.blaze.util.{Execution, SerialExecutionContext}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

class MockFlowControl(mySettings: Http2Settings, peerSettings: Http2Settings) extends SessionFlowControl(mySettings, peerSettings) {
  sealed trait Operation
  case class SessionConsumed(bytes: Int) extends Operation
  case class StreamConsumed(stream: StreamFlowWindow, consumed: Int) extends Operation

  val observedOps = new ListBuffer[Operation]

  override protected def onSessonBytesConsumed(consumed: Int): Unit = {
    observedOps += SessionConsumed(consumed)
  }

  override protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit = {
    observedOps += StreamConsumed(stream, consumed)
  }
}

private class MockWriteListener extends WriteListener {
  val observedInterests = new ListBuffer[WriteInterest]
  override def registerWriteInterest(interest: WriteInterest): Unit = {
    observedInterests += interest
  }
}

private class MockHttp2StreamState(writeListener: WriteListener, frameEncoder: Http2FrameEncoder, sessionExecutor: ExecutionContext)
  extends Http2StreamState(writeListener, frameEncoder, sessionExecutor) {

  override def streamId: Int = ???

  override def flowWindow: StreamFlowWindow = ???

  /** Deals with stream related errors */
  override protected def onStreamFinished(ex: Option[Http2Exception]): Unit = ???

  override protected def maxFrameSize: Int = ???
}

private class Http2MockTools(isClient: Boolean) {

  lazy val mySettings: Http2Settings = Http2Settings.default()

  lazy val peerSettings: Http2Settings = Http2Settings.default()

  lazy val flowControl: MockFlowControl = new MockFlowControl(mySettings, peerSettings)

  lazy val sessionExecutor: ExecutionContext = new SerialExecutionContext(Execution.directec)

  lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(peerSettings.headerTableSize)

  lazy val headerDecoder: HeaderDecoder = new HeaderDecoder(mySettings.maxHeaderListSize, mySettings.headerTableSize)

  lazy val frameEncoder: Http2FrameEncoder = new Http2FrameEncoder(peerSettings, headerEncoder)

  lazy val newWriteListener: MockWriteListener = new MockWriteListener

  lazy val idManager: StreamIdManager = StreamIdManager(isClient)
}
