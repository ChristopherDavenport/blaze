package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.Headers
import org.http4s.blaze.http.http2.Http2Connection.{Draining, _}
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.{BufferTools, Execution, SerialExecutionContext}

import scala.annotation.tailrec
import scala.collection.mutable.HashMap
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/** Representation of the http2 session.
  *
  * TODO: need to be more consistent with checking the state of the session with all operations.
  *       The streamId must be lazy
  */
private abstract class Http2ConnectionImpl(
    isClient: Boolean,
    mySettings: Http2Settings, // the settings of this side
    peerSettings: Http2Settings, // the settings of their side
    http2Encoder: Http2FrameEncoder,
    headerDecoder: HeaderDecoder,
    flowStrategy: FlowStrategy,
    executor: ExecutionContext)
  extends Http2Connection {

  private[this] case class PingState(startedSystemTimeMs: Long, continuation: Promise[Duration])

  @volatile
  private[this] var currentState: ConnectionState = Running
  private[this] var started = false
  private[this] var currentPing: Option[PingState] = None

  private[this] def isClosing: Boolean = state.isInstanceOf[Closing]

  // The sessionExecutor is responsible for serializing all mutations of the session state,
  // to include its interactions with the socket.
  private[this] implicit val sessionExecutor = new SerialExecutionContext(executor) {
    override def reportFailure(cause: Throwable): Unit = {
      // Any uncaught exceptions in the serial executor are fatal to the session
      sessionExecutor_shutdownWithError(Some(cause), "sessionExecutor")
    }
  }

  // TODO: What about peer information?
  // Need to be able to create new stream pipelines
  protected def newInboundStream(streamId: Int): Option[LeafBuilder[StreamMessage]]

  // Need to be able to write data to the pipeline
  protected def writeBytes(data: Seq[ByteBuffer]): Future[Unit]

  // Need to be able to read data
  protected def readData(): Future[ByteBuffer]

  // Called when the session has completed all its operations
  protected def sessionTerminated(): Unit

  // TODO: awkward...
  // Create a new decoder wrapping this Http2FrameHandler
  protected def newHttp2Decoder(handler: Http20FrameHandler): Http20FrameDecoder

  // Start the session. This entails starting the read loop
  def startSession(): Unit = synchronized {
    if (started) throw new IllegalStateException(s"Session already started")
    started = true
    logger.debug(s"starting session with peer settings $peerSettings")
    readLoop(BufferTools.emptyBuffer)
  }

  override def quality: Double = {
    if (isClosing || !idManager.unusedOutboundStreams) 0.0
    else 1.0 - activeStreamCount.toDouble/peerSettings.maxInboundStreams.toDouble
  }

  override def ping: Future[Duration] = {
    val p = Promise[Duration]
    sessionExecutor.execute(new Runnable {
      override def run(): Unit = sessionExecutor_ping(p)
    })
    p.future
  }

  // Must be called from within the sessionExecutor
  private[this] def sessionExecutor_ping(p: Promise[Duration]): Unit = currentPing match {
    case Some(_) => p.tryFailure(new IllegalStateException("Ping already in progress"))
    case None =>
      val time = System.currentTimeMillis
      currentPing = Some(PingState(time, p))
      val data = new Array[Byte](8)
      ByteBuffer.wrap(data).putLong(time)
      val pingFrame = http2Encoder.pingFrame(data)
      writeController.writeOutboundData(pingFrame)
  }

  override def drainSession(gracePeriod: Duration): Unit = {
    require(gracePeriod.isFinite())
    sessionExecutor.execute(new Runnable {
      override def run(): Unit = sessionExecutor_drainSession(gracePeriod)
    })
  }

  // must be called from within the sessionExecutor
  private[this] def sessionExecutor_drainSession(gracePeriod: Duration): Unit = {
    val deadline = System.currentTimeMillis + gracePeriod.toMillis
    state match {
      case Closed(_) => () // nop already under control
      case Draining(d, _) if d < deadline => ()
      case _ =>
        // Start draining: send a GOAWAY and set a timer to shutdown
        val noError = Http2Exception.NO_ERROR.goaway()
        val someNoError = Some(noError)
        currentState = Http2Connection.Draining(deadline, someNoError)

        val frame = Http20FrameSerializer.mkGoAwayFrame(idManager.lastInboundStream, noError)
        writeController.writeOutboundData(frame)

        // Set a timer to force closed the session after the expiration
        val work = new Runnable {
          def run(): Unit = sessionExecutor_shutdownWithError(someNoError, s"drainSession($gracePeriod)")
        }
        Execution.scheduler.schedule(work, sessionExecutor, gracePeriod)
    }
  }

  /** Acquire a new `HeadStage[StreamMessage]` that will be a part of this `Session`.
    *
    * @note The resulting stage acquires new stream id's lazily to comply with protocol
    *       requirements, and thus is not assigned a valid stream id until the first
    *       `HeadersFrame` has been sent, which may fail.
    */
  final def newOutboundStream(): HeadStage[StreamMessage] = new OutboundStreamState

  final protected def activeStreamCount: Int = activeStreams.size

  // We need to get a logger somehow...
  protected val logger: org.log4s.Logger

  /** Get the current state of the `Session` */
  final def state: ConnectionState = currentState

  private[this] val sessionFlowControl = new SessionFlowControl(mySettings, peerSettings) {
    override protected def onSessonBytesConsumed(consumed: Int): Unit = {
      val update = flowStrategy.checkSession(this)
      if (update > 0) {
        sessionInboundAcked(update)
        writeController.writeOutboundData(http2Encoder.sessionWindowUpdate(update))
      }
    }

    override protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit = {
      val update = flowStrategy.checkStream(this, stream)
      if (update.session > 0) {
        sessionInboundAcked(update.session)
        writeController.writeOutboundData(http2Encoder.sessionWindowUpdate(update.session))
      }

      if (update.stream > 0) {
        stream.streamInboundAcked(update.stream)
        writeController.writeOutboundData(http2Encoder.streamWindowUpdate(stream.streamId, update.stream))
      }
    }
  }

  private[this] val idManager = StreamIdManager(isClient)

  // TODO: there _must_ be a specialized map with unboxed keys somewhere...
  private[this] val activeStreams = new HashMap[Int, Http2StreamStateBase]

  // This must be instantiated last since it has a dependency of `activeStreams` and `idManager`.
  private[this] val http2Decoder = newHttp2Decoder(new SessionFrameHandlerImpl)

  // TODO: thread the write buffer size in as a parameter
  private[this] val writeController = new WriteController(64*1024) {
    /** Write the outbound data to the pipeline */
    override protected def writeToWire(data: Seq[ByteBuffer]): Unit = {
      writeBytes(data).onComplete {
        case Success(_) =>
          // Finish the write cycle, and then see if we need to shutdown the connection.
          // If the connection state is already `Closed`, it means we were flushing a
          // GOAWAY and its our responsibility to signal that the session is now closed.
          writeSuccessful()
          currentState match {
            case Draining(_, ex) if !awaitingWriteFlush && activeStreams.isEmpty =>
              sessionExecutor_shutdownWithError(ex, "writeController.finish draining")

            case Closed(_) if !awaitingWriteFlush =>
              sessionTerminated()

            case _ => ()
          }

        case Failure(t) =>
          if (state.isInstanceOf[Closed]) sessionTerminated()
          else sessionExecutor_shutdownWithError(Some(t), "writeToWire")
      }
    }
  }

  // TODO: Maybe we should abstract around this such that we don't need to pass in a header encoder/decoder to this class...
  private class SessionFrameHandlerImpl extends SessionFrameHandler[Http2StreamStateBase](
    mySettings, headerDecoder, activeStreams, sessionFlowControl, idManager) {

    override protected def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result = {
      // TODO: support push promises
      val frame = Http20FrameSerializer.mkRstStreamFrame(promisedId, Http2Exception.REFUSED_STREAM.code)
      writeController.writeOutboundData(frame)
      Continue
    }

    override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = {
      if (!ack) writeController.writeOutboundData(http2Encoder.pingAck(data))
      else currentPing match {
        case None => // nop
        case Some(PingState(sent, continuation)) =>
          currentPing = None

          if (ByteBuffer.wrap(data).getLong != sent) { // data guaranteed to be 8 bytes
            continuation.tryFailure(new Exception("Received ping response with unknown data."))
          } else {
            val duration = Duration.fromNanos((System.currentTimeMillis - sent) * 1000000)
            continuation.trySuccess(duration)
          }
      }

      Continue
    }

    override protected def newInboundStream(streamId: Int): Option[Http2StreamStateBase] = {
      if (isClosing) None
      else Http2ConnectionImpl.this.newInboundStream(streamId).map { builder =>
        val streamFlowWindow = sessionFlowControl.newStreamFlowWindow(streamId)
        logger.debug(s"Created new InboundStream with id $streamId. $activeStreamCount streams.")
        val head = new InboundStreamState(streamId, streamFlowWindow)
        activeStreams.put(streamId, head)
        builder.base(head)
        head.sendInboundCommand(Command.Connected)
        head
      }
    }

    override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
      import Http2Settings._
      // TODO: we don't consider the uncertainty between us sending a SETTINGS frame and its ack
      if (ack) Continue
      else {
        var result: Http2Result = Continue

        def initialWindowSizeChange(newSize: Int): Unit = {
          // https://tools.ietf.org/html/rfc7540#section-6.9.2
          // a receiver MUST adjust the size of all stream flow-control windows that
          // it maintains by the difference between the new value and the old value.
          val diff = newSize - peerSettings.initialWindowSize
          if (diff != 0) {
            peerSettings.initialWindowSize = newSize

            activeStreams.values.forall { stream =>
              val e = stream.flowWindow.peerSettingsInitialWindowChange(diff)
              if (e != Continue && result == Continue) {
                result = e
                false
              } else {
                stream.outboundFlowWindowChanged()
                true
              }
            }
          }
        }

        val it = settings.iterator
        while (it.hasNext && result == Continue) it.next() match {
          case i@InvalidSetting(ex)         =>
            logger.debug(ex)(s"Received invalid setting $i")
            result = ex.toError

          case HEADER_TABLE_SIZE(size)      =>
            peerSettings.headerTableSize = size.toInt
            http2Encoder.setMaxTableSize(size.toInt)

          case ENABLE_PUSH(enabled)         => peerSettings.pushEnabled = enabled != 0
          case MAX_CONCURRENT_STREAMS(max)  => peerSettings.maxInboundStreams = max.toInt
          case INITIAL_WINDOW_SIZE(size)    => initialWindowSizeChange(size)
          case MAX_FRAME_SIZE(size)         => peerSettings.maxFrameSize = size
          case MAX_HEADER_LIST_SIZE(size)   =>
            peerSettings.maxHeaderListSize = size // TODO: what should we do about this?

          case unknown =>
            logger.info(s"Received unknown setting: $unknown")
        }

        if (result == Continue) {
          // ack the SETTINGS frame on success, otherwise we are tearing down the connection anyway so no need
          writeController.writeOutboundData(Http20FrameSerializer.mkSettingsFrame(true, Nil))
        }

        result
      }
    }

    override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = {
      val message = StandardCharsets.UTF_8.decode(debugData).toString
      logger.debug {
        val errorName = Http2Exception.errorName(errorCode.toInt)
        val errorCodeStr = s"0x${Integer.toHexString(errorCode.toInt)}: $errorName"
        s"Received GOAWAY($errorCodeStr, '$message'). Last processed stream: $lastStream"
      }

      val unhandledStreams = activeStreams.filterKeys { id =>
        idManager.isOutboundId(id) && id > lastStream
      }

      unhandledStreams.foreach { case (id, stream) =>
        // We remove the stream first so that we don't send a RST back to
        // the peer, since they have discarded the stream anyway.
        activeStreams.remove(id)
        val ex = Http2Exception.REFUSED_STREAM.rst(id, message)
        stream.closeWithError(Some(ex))
      }

      if (activeStreams.isEmpty) {
        sessionExecutor_shutdownWithError(None, "onGoAwayFrame")
      } else {
        // Need to wait for the streams to close down
        currentState = Draining(Long.MaxValue, None)
      }

      Continue
    }
  }

  ////////////////////////////////////////////////////////////////////////

  private[this] def readLoop(remainder: ByteBuffer): Unit =
    // the continuation must be run in the sessionExecutor
    readData().onComplete {
      // This completion is run in the sessionExecutor so its safe to
      // mutate the state of the session.
      case Failure(ex) => sessionExecutor_shutdownWithError(Some(ex), "readLoop-read")
      case Success(next) =>
        logger.debug(s"Read data: $next")
        val data = BufferTools.concatBuffers(remainder, next)

        logger.debug("Handling inbound data.")
        @tailrec
        def go(): Unit = http2Decoder.decodeBuffer(data) match {
          case Halt => () // nop
          case Continue => go()
          case BufferUnderflow => readLoop(data)
          case Error(ex) => sessionExecutor_shutdownWithError(Some(ex), "readLoop-decode")
        }
        go()
    }

  // Must be called from within the session executor
  private[this] def sessionExecutor_shutdownWithError(ex: Option[Throwable], phase: String): Unit = {
    // This should be the only way to set the state to `Closed`, making this idempotent
    if (!currentState.isInstanceOf[Closed]) {
      // TODO: make sure things like EOF are filtered out

      currentState = Closed(ex)
      ex match {
        case None => sessionTerminated()  // If we're not writing any more data, we need to terminate the session
        case Some(ex) =>
          val http2SessionError = ex match {
            case ex: Http2Exception =>
              logger.debug(ex)(s"Shutting down with HTTP/2 session in phase $phase")
              ex

            case other =>
              logger.warn(other)(s"Shutting down HTTP/2 with unhandled exception in phase $phase")
              Http2Exception.INTERNAL_ERROR.goaway("Unhandled internal exception")
          }

          val streams = activeStreams.values.toVector
          for (stream <- streams) {
            activeStreams.remove(stream.streamId)
            stream.closeWithError(Some(ex))
          }

          val goawayFrame = Http20FrameSerializer.mkGoAwayFrame(idManager.lastInboundStream, http2SessionError)

          writeController.writeOutboundData(goawayFrame)
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////

  // TODO: need to move much of this to the base class
  private abstract class Http2StreamStateBase
    extends Http2StreamState(writeController, http2Encoder, sessionExecutor) {

    final override protected def maxFrameSize: Int = peerSettings.maxFrameSize

    // Remove closed streams from the session and deal with stream related errors.
    // Note: if the stream has already been removed from the active streams, no message are written to the peer.
    final override protected def onStreamFinished(ex: Option[Http2Exception]): Unit = {
      if (activeStreams.remove(streamId).isDefined) ex match {
        case None => // nop
        case Some(ex: Http2StreamException) =>
          val rstFrame = Http20FrameSerializer.mkRstStreamFrame(ex.stream, ex.code)
          writeController.writeOutboundData(rstFrame)

        case Some(ex: Http2SessionException) =>
          sessionExecutor_shutdownWithError(Some(ex), "onStreamFinished")
      }

      // Check to see if we were draining and have finished, and if so, are all the streams closed.
      currentState match {
        case Draining(_, ex) if activeStreams.isEmpty && !writeController.awaitingWriteFlush =>
          sessionExecutor_shutdownWithError(ex, "onStreamFinished")

        case _ => ()
      }
    }
  }

  private class InboundStreamState(
      val streamId: Int,
      val flowWindow: StreamFlowWindow)
    extends Http2StreamStateBase

  /** The trick with the outbound stream is that we need to lazily acquire a streamId
    * since it is possible to acquire a stream but delay writing the headers, which
    * signal the start of the stream.
    */
  private class OutboundStreamState extends Http2StreamStateBase {
    // We don't initialized the streamId and flowWindow until we've sent the first frame. As
    // with most things in this file, these should only be modified from within the session executor
    private[this] var lazyStreamId: Int = -1
    private[this] var lazyFlowWindow: StreamFlowWindow = null

    override def streamId: Int = {
      if (lazyStreamId != -1) lazyStreamId
      else throw new IllegalStateException("Stream uninitialized")
    }

    override def flowWindow: StreamFlowWindow = {
      if (lazyFlowWindow != null) lazyFlowWindow
      else throw new IllegalStateException("Stream uninitialized")
    }

    // We need to establish whether the stream has been initialized yet and try to acquire a new ID if not
    override protected def invokeStreamWrite(msg: StreamMessage, p: Promise[Unit]): Unit = {
      if (lazyStreamId != -1) super.invokeStreamWrite(msg, p)
      else if (state.isInstanceOf[Closing]) {
        // Before we initialized the stream, we began to drain or were closed.
        val ex = Http2Exception.REFUSED_STREAM.goaway("Session closed before stream was initialized")
        p.tryFailure(ex)
      }
      else idManager.takeOutboundId() match {
        case Some(streamId) =>
          lazyStreamId = streamId
          lazyFlowWindow = sessionFlowControl.newStreamFlowWindow(streamId)
          assert(activeStreams.put(streamId, this).isEmpty)
          logger.debug(s"Created new OutboundStream with id $streamId. $activeStreamCount streams.")
          super.invokeStreamWrite(msg, p)

        case None =>
          // Out of stream IDs. We need to switch to draining
          // TODO: what should we do here? Transition to draining?
          val ex = Http2Exception.REFUSED_STREAM.rst(0, "Session is out of outbound stream IDs")
          p.tryFailure(ex)
      }
    }

    override def name: String = {
      // This is a bit racy since it can be called from anywhere, but it's for diagnostics anyway
      if (lazyStreamId != -1) super.name
      else "UnaquiredStreamState"
    }
  }

}
