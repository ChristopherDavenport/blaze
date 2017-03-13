package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.util

import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.pipeline.Command.{EOF, OutboundCommand}
import org.http4s.blaze.pipeline.HeadStage
import org.http4s.blaze.util.{BufferTools, SerialExecutionContext}

import scala.concurrent.{Future, Promise}

private abstract class Http2StreamState(
    writeController: WriteController,
    http2FrameEncoder: Http2FrameEncoder,
    sessionExecutor: SerialExecutionContext)
  extends HeadStage[StreamMessage] with WriteListener {

  // Can potentially be lazy, such as in an outbound stream
  def streamId: Int

  // Can potentially be lazy, such as in an outbound stream
  def flowWindow: StreamFlowWindow

  /** Deals with stream related errors */
  protected def onStreamFinished(ex: Option[Http2Exception]): Unit

  protected def maxFrameSize: Int

  override def name: String = s"Http2Stream($streamId)"

  // State associated with the streams inbound data flow
  private[this] val pendingInboundMessages = new util.ArrayDeque[StreamMessage]
  private[this] var pendingRead: Promise[StreamMessage] = null

  // State associated with the streams outbound data flow
  private[this] var writePromise: Promise[Unit] = null
  private[this] var pendingOutboundFrame: StreamMessage = null


  // Determines if we can receive and send messages
  // WARNING: this should only be set to true in the `closeWithError` handler and
  //          only handled within the session executor
  private[this] var streamIsClosed = false

  // Similar to the state of halfClosedLocal
  // we can no longer send frames other than WINDOW_UPDATE, PRIORITY, and RST_STREAM
  private[this] var sentEOS: Boolean = false

  // Similar to the state of halfClosedRemote
  // peer can no longer send frames other than WINDOW_UPDATE, PRIORITY, and RST_STREAM
  private[this] var receivedEOS: Boolean = false

  override def readRequest(size: Int): Future[StreamMessage] = {
    val p = Promise[StreamMessage]

    sessionExecutor.execute(new Runnable {
      def run(): Unit = {
        if (pendingRead != null) p.failure(new IllegalStateException()) // TODO: should fail the stream, send RST, etc.
        else pendingInboundMessages.poll() match {
          case null if receivedEOS => p.tryFailure(EOF)
          case null => pendingRead = p
          case msg =>
            flowWindow.inboundConsumed(msg.flowBytes)
            p.trySuccess(msg)
        }
      }
    })

    p.future
  }

  override def writeRequest(msg: StreamMessage): Future[Unit] = {
    val p = Promise[Unit]

    // Move the work into the session executor
    sessionExecutor.execute(new Runnable {
      override def run(): Unit = invokeStreamWrite(msg, p)
    })

    p.future
  }

  // Invoke methods are intended to only be called from within the context of the session
  protected def invokeStreamWrite(msg: StreamMessage, p: Promise[Unit]): Unit = {
    if (sentEOS) {
      p.tryFailure(new IllegalStateException(s"Stream($streamId) already closed"))
    } else if (writePromise != null) {
      p.tryFailure(new IllegalStateException(s"Already a pending write on this stream($streamId)"))
    }
    else if (streamIsClosed) {
      p.tryFailure(EOF)
    } else {
      sentEOS = msg.endStream
      pendingOutboundFrame = msg
      writePromise = p
      writeController.registerWriteInterest(this)
    }
  }

  /** Must be called by the [[WriteController]] from within the session executor
    *
    * @return number of flow bytes written
    */
  def performStreamWrite(controller: WriteController): Unit = {
    // Nothing waiting to go out, so return fast
    if (writePromise == null) return

    pendingOutboundFrame match {
      case HeadersFrame(priority, eos, hs) =>
        val data = http2FrameEncoder.headerFrame(streamId, hs, priority, eos)
        controller.writeOutboundData(data)
        writePromise.trySuccess(())
        pendingOutboundFrame = null
        writePromise = null

      case DataFrame(eos, data) =>
        val requested = math.min(maxFrameSize, data.remaining)
        val allowedBytes = flowWindow.outboundReceived(requested)

        logger.debug(s"Allowed: $allowedBytes, data: $pendingOutboundFrame")

        if (allowedBytes == pendingOutboundFrame.flowBytes) {
          val buffers = http2FrameEncoder.dataFrame(streamId, data, eos)
          writeController.writeOutboundData(buffers)

          // There isn't a race here since all manipulations of the vars are from within the actor
          pendingOutboundFrame = null
          writePromise.trySuccess(())
          writePromise = null
        } else {
          // We take a chunk, and then reregister ourselves
          val slice = BufferTools.takeSlice(data, allowedBytes)
          val buffers = http2FrameEncoder.dataFrame(streamId, slice, false)
          writeController.writeOutboundData(buffers)

          if (allowedBytes == maxFrameSize) {
            // We were (probably) not limited by the flow window so
            // signal interest in another write cycle.
            writeController.registerWriteInterest(this)
          }
        }
    }
  }

  override def outboundCommand(cmd: OutboundCommand): Unit =
    sessionExecutor.execute(new Runnable {
      def run(): Unit = cmd match {
        case Command.Flush | Command.Connect =>
          () // nop

        case Command.Disconnect =>
          closeWithError(None)

        case Command.Error(ex: Http2StreamException) =>
          // Since the pipeline doesn't actually know what streamId it is
          // associated with its our job to populate it with the real stream id.
          closeWithError(Some(ex.copy(stream = streamId)))

        case Command.Error(ex) =>
          closeWithError(Some(ex))
      }
    })

  ///////////////////// Inbound messages ///////////////////////////////

  final def invokeInboundData(eos: Boolean, data: ByteBuffer, flowBytes: Int): MaybeError = {
    // https://tools.ietf.org/html/rfc7540#section-5.1 section 'closed'
    if (receivedEOS) {
      closeWithError(None) // the GOAWAY will be sent by the FrameHandler
      STREAM_CLOSED.goaway(s"Stream($streamId received DATA frame after EOS").toError
    } else if (streamIsClosed) {
      STREAM_CLOSED.rst(streamId).toError
    } else if (flowWindow.inboundObserved(flowBytes)) {
      receivedEOS = eos
      val consumed = if (queueMessage(DataFrame(eos, data))) flowBytes else flowBytes - data.remaining()
      flowWindow.inboundConsumed(consumed)
      Continue
    }
    else {
      // Inbound flow window violated. Technically, if it was a stream overflow,
      // this could be a stream error, but we don't care and just kill the session.
      Http2Exception.FLOW_CONTROL_ERROR.goaway(s"stream($streamId) flow control error").toError
    }
  }

  final def invokeInboundHeaders(priority: Option[Priority], eos: Boolean, headers: Seq[(String,String)]): MaybeError = {
    // https://tools.ietf.org/html/rfc7540#section-5.1 section 'closed'
    if (receivedEOS) {
      closeWithError(None) // the GOAWAY will be sent by the FrameHandler
      STREAM_CLOSED.goaway(s"Stream($streamId received DATA frame after EOS").toError
    } else if (streamIsClosed) {
      STREAM_CLOSED.rst(streamId).toError
    } else {
      receivedEOS = eos
      queueMessage(HeadersFrame(priority, eos, headers))
      Continue
    }
  }

  //////////////////////////////////////////////////////////////////////

  // Shuts down the stream and calls `onStreamFinished` with any potential errors.
  // WARNING: this must be called from within the session executor.
  // TODO: should add a way to signal that we don't want to send the error to the peer
  def closeWithError(t: Option[Throwable]): Unit = {
    if (!streamIsClosed) {
      streamIsClosed = true
      clearDataChannels(t match {
        case Some(ex) => ex
        case None => EOF
      })

      val http2Ex = t match {
        // Gotta make sure both sides agree that this stream is closed
        case None if !(sentEOS && receivedEOS) => Some (STREAM_CLOSED.rst(streamId))
        case None => None
        case Some(t: Http2Exception) => Some(t)
        case Some(other) =>
          logger.warn(other)(s"Unknown error in stream($streamId)")
          Some(INTERNAL_ERROR.rst(streamId, "Unhandled error in stream pipeline"))
      }

      onStreamFinished(http2Ex)
    }
  }

  // handle the inbound message.
  // Returns `true` if the message was handled by a stream. Otherwise, it was queued and returns `false`.
  private[this] def queueMessage(msg: StreamMessage): Boolean = {
    if (pendingRead == null) {
      pendingInboundMessages.offer(msg)
      false
    } else {
      pendingRead.trySuccess(msg)
      pendingRead = null
      true
    }
  }

  private[this] def clearDataChannels(ex: Throwable): Unit = {
    // Clear the read channel
    if (pendingRead == null) {
      var pendingBytes = 0
      while(!pendingInboundMessages.isEmpty) {
        pendingBytes += pendingInboundMessages.poll().flowBytes
      }

      flowWindow.session.sessionInboundConsumed(pendingBytes)
    } else {
      val p = pendingRead
      pendingRead = null
      p.tryFailure(ex)
    }

    // clear the write channel
    if (writePromise != null) {
      pendingOutboundFrame = null
      val p = writePromise
      writePromise = null
      p.tryFailure(ex)
    }
  }
}
