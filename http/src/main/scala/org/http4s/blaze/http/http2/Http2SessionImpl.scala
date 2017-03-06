package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.http2.Http2Connection.{ConnectionState, Running}
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
  * TODO: Need a hook for intentionally adding a stream head for the case of the client.
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

  private[this] var started = false

  @volatile
  private[this] var currentState: ConnectionState = Running

  import Http2Connection._

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

  override def ping: Future[Duration] = ???

  // TODO: this has a race condition if called multiple times.
  def drainSession(gracePeriod: Duration, cause: Throwable): Unit = {
    require(gracePeriod.isFinite())

    if (gracePeriod.length == 0) {
      // close now
      shutdownWithError(cause, "drainSession-now")
    } else {
      val now = System.currentTimeMillis()
      val deadline = now + gracePeriod.toMillis
      currentState = Http2Connection.Draining(deadline, cause)
      // Need to set a timer...
      ???
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

  private[this] val sessionExecutor = new SerialExecutionContext(executor) {
    override def reportFailure(cause: Throwable): Unit = {
      // Any uncaught exceptions in the serial executor are fatal to the session
      shutdownWithError(cause, "sessionExecutor")
    }
  }

  /** Get the current state of the `Session` */
  final def state: ConnectionState = currentState

  private object sessionFlowControl extends SessionFlowControl(mySettings, peerSettings) {
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
        stream.inboundAcked(update.stream)
        writeController.writeOutboundData(http2Encoder.streamWindowUpdate(stream.streamId, update.stream))
      }
    }
  }

  private[this] val idManager = StreamIdManager(isClient)

  // TODO: there _must_ be a specialized map with unboxed keys somewhere...
  private[this] val activeStreams = new HashMap[Int, Http2StreamStateBase]

  // This must be instantiated last since it has a dependency of `activeStreams` and `idManager`.
  private[this] val http2Decoder = newHttp2Decoder(new SessionFrameHandlerImpl)

  // TODO: this should probably be passed in the constructor
  private[this] val writeController = new WriteController(64*1024, sessionExecutor) {
    /** Write the outbound data to the pipeline */
    override protected def writeToWire(data: Seq[ByteBuffer]): Future[Unit] = writeBytes(data)
  }

  // TODO: Maybe we should abstract around this such that we don't need to pass in a header encoder/decoder to this class...
  private class SessionFrameHandlerImpl extends SessionFrameHandler[Http2StreamStateBase](
    mySettings, headerDecoder, activeStreams, sessionFlowControl, idManager) {

    override def onSessionFlowUpdate(count: Int): Unit = {
      logger.debug(s"Session flow update: $count")
      // We need to check the streams to see if any can now make forward progress
      activeStreams.values.foreach(_.performStreamWrite(writeController))
    }

    override def onStreamFlowUpdate(stream: Http2StreamStateBase, count: Int): Unit = {
      logger.debug(s"Stream(${stream.streamId}) flow update: $count")
      // We can now check this stream to see if it can make forward progress
      stream.performStreamWrite(writeController)
    }

    override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = {
      // TODO: need to get this setup
      if (!ack) writeController.writeOutboundData(http2Encoder.pingAck(data))
      else logger.debug("Received ping ack")

      Continue
    }

    override protected def newInboundStream(streamId: Int): Option[Http2StreamStateBase] = {
      Http2ConnectionImpl.this.newInboundStream(streamId).map { builder =>
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
      if (!ack) settings.foreach {
        case HEADER_TABLE_SIZE(size)      =>
          peerSettings.headerTableSize = size.toInt
          http2Encoder.setMaxTableSize(size.toInt)

        case ENABLE_PUSH(enabled)         => peerSettings.pushEnabled = enabled != 0
        case MAX_CONCURRENT_STREAMS(max)  => peerSettings.maxInboundStreams = max.toInt
        case INITIAL_WINDOW_SIZE(size)    => peerSettings.initialWindowSize = size.toInt
        case MAX_FRAME_SIZE(size)         => peerSettings.maxFrameSize = size.toInt
        case MAX_HEADER_LIST_SIZE(size)   =>
          peerSettings.maxHeaderListSize = size.toInt // TODO: what should we do about this?

        case unknown =>
          logger.info(s"Received unknown setting: $unknown")
      }
      Continue
    }

    override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = {
      logger.debug {
        val msg = StandardCharsets.UTF_8.decode(debugData).toString
        val errorName = Http2Exception.errorName(errorCode.toInt)
        val errorCodeStr = s"0x${Integer.toHexString(errorCode.toInt)}: $errorName"
        s"Received GOAWAY($errorCodeStr, '$msg'). Last processed stream: $lastStream"
      }
      ???
    }
  }

  ////////////////////////////////////////////////////////////////////////

  private[this] def readLoop(remainder: ByteBuffer): Unit =
    readData().onComplete {
      // This completion is run in the sessionExecutor so its safe to
      // mutate the state of the session.
      case Failure(ex) => shutdownWithError(ex, "readLoop-read")
      case Success(next) =>
        logger.debug(s"Read data: $next")
        // TODO: this should be split into Success and Failure branches
        val data = BufferTools.concatBuffers(remainder, next)

        logger.debug("Handling inbound data.")
        @tailrec
        def go(): Unit = http2Decoder.decodeBuffer(data) match {
          case Halt => // nop
          case Continue => go()
          case BufferUnderflow => readLoop(data)
            // TODO: we need to handle this error, not just log it.
          case Error(ex) =>
            shutdownWithError(ex, "readLoop-decode")
        }
        go()
    }(sessionExecutor) // must be run in the sessionExecutor

  // Must be called from within the session executor
  private def shutdownWithError(ex: Throwable, phase: String): Unit = {
    logger.info(ex)(s"Shutting down due to phase $phase")

    val sessionEx = ex match {
      case ex: Http2SessionException => ex
      case other => ???
    }

    val goawayFrame = Http20FrameSerializer.mkGoAwayFrame(idManager.lastInboundStream, sessionEx)
    writeBytes(goawayFrame).onComplete {
      case _ => sessionTerminated()
    }(Execution.directec)
  }

  ////////////////////////////////////////////////////////////////////////////

  // TODO: need to move much of this to the base class
  private abstract class Http2StreamStateBase
    extends Http2StreamState(writeController, http2Encoder, sessionExecutor) {

    final override protected def maxFrameSize: Int = peerSettings.maxFrameSize

    // deal with stream related errors
    final override protected def onStreamFinished(ex: Option[Http2Exception]): Unit = {
      activeStreams.remove(streamId)
      ex match {
        case None => // nop
        case Some(ex: Http2StreamException) =>
          val rstFrame = Http20FrameSerializer.mkRstStreamFrame(ex.stream, ex.code)
          writeController.writeOutboundData(rstFrame)

        case Some(ex: Http2SessionException) =>
          shutdownWithError(ex, "onStreamFinished")
      }
      // TODO: Need to see if we're draining and if all the streams are closed.
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
      else idManager.takeOutboundId() match {
        case Some(streamId) =>
          lazyStreamId = streamId
          lazyFlowWindow = sessionFlowControl.newStreamFlowWindow(streamId)

          assert(activeStreams.put(streamId, this).isEmpty)

          logger.info(s"Created new OutboundStream with id $streamId. $activeStreamCount streams.")

          super.invokeStreamWrite(msg, p)

        case None =>
          // TODO: failed to acquire a new streamId. Need a special kind of error to signal
          // that we've run out of stream ids.
          ???
      }
    }

    override def name: String = {
      // This is a bit racy since it can be called from anywhere, but it's for diagnotics anyway
      if (lazyStreamId != -1) super.name
      else "UnacquiredStreamState"
    }
  }
}
