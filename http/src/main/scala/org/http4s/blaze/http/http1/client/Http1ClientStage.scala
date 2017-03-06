package org.http4s.blaze.http.http1.client

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.HttpClientSession.{ReleaseableResponse, Status}
import org.http4s.blaze.http._
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.pipeline.{Command, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

// TODO: we're totally logging all sorts of headers in here, which is bad for security
private class Http1ClientStage(config: HttpClientConfig)
  extends TailStage[ByteBuffer] with Http1ClientSession {
  import Http1ClientStage._

  def name: String = "Http1ClientStage"

  @volatile private var state: State = Unconnected

  private[this] implicit def ec = Execution.trampoline
  private[this] val codec = new Http1ClientCodec(config)
  private[this] val stageLock: Object = codec  // No need for another object to lock on...

  // the dispatchId identifies each dispatch so that if the reader of the response is stored
  // and attempted to use later, the `dispatchId` will be different and the read call can return
  // an error as opposed to corrupting the session state.
  private[this] var dispatchId = 0L

  // ---------------------------------------------------------------------------------

  override def status: Status = {
    val s = stageLock.synchronized { state }
    s match {
      case Running(true, true) => HttpClientSession.Ready
      case Running(_, _) | Unconnected => HttpClientSession.Busy
      case Closed(_) => HttpClientSession.Closed
    }
  }

  /** Prepares the stage for a new dispatch.
    *
    * @return `true` if the stage is now ready, `false` otherwise.
    */
  def prepareForDispatch(): Boolean = stageLock.synchronized {
    if (status == HttpClientSession.Ready) {
      codec.reset()
      dispatchId += 1
      true
    }
    else false
  }

  // TODO: we should put this on a timer...
  override def close(within: Duration): Unit = stageLock.synchronized {
    state match {
      case Closed(_) => // nop
      case _ =>
        stageShutdown()
        sendOutboundCommand(Command.Disconnect)
    }
  }

  // Entry method which, on startup, sends the request and attempts to parse the response
  override protected def stageStartup(): Unit = stageLock.synchronized {
    if (state == Unconnected) {
      super.stageStartup()
      state = Running(true, true)
    }
    else illegalState("stageStartup", state)
  }

  override protected def stageShutdown(): Unit = stageLock.synchronized {
    super.stageShutdown()
    if (!state.isInstanceOf[Closed]) {
      state = Closed(EOF)
    }
  }

  override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = stageLock.synchronized {
    state match {
      case r@Running(true, true) =>
        logger.debug("Initiating dispatch cycle")

        r.readChannelClear = false  // we are no longer idle, and the read/write channels
        r.writeChannelClear = false // must be considered contaminated.

        // Remember which dispatch we are in
        val thisDispatchId = dispatchId

        // Write the request to the wire
        launchWriteRequest(request).onComplete {
          case Success(_) => stageLock.synchronized {
            state match {
              case r@Running(_, _) if dispatchId == thisDispatchId =>
                logger.debug(s"Successfully finished writing request $request")
                r.writeChannelClear = true

              case _ => // nop
            }
          }

          case Failure(ex) =>
            logger.debug(ex)(s"Failed to write request $request")
            handleError("initial request write", ex)
        }

        val p = Promise[ReleaseableResponse]
        receiveResponse(BufferTools.emptyBuffer, p)
        p.future

      case state => Future(illegalState("initial dispatch", state))
    }
  }

  private def receiveResponse(buffer: ByteBuffer, p: Promise[ReleaseableResponse]): Unit = stageLock.synchronized {
    logger.debug(s"Receiving response. $buffer, :\n${bufferAsString(buffer)}\n")
    if (!buffer.hasRemaining) channelReadThenReceive(p)
    else if (!codec.preludeComplete && codec.parsePrelude(buffer)) {
      val prelude = codec.getResponsePrelude
      logger.debug(s"Finished getting prelude: $prelude")
      val body = getBody(buffer)
      val response = new ClientResponse(prelude.code, prelude.status, prelude.headers, body) with ReleaseableResponse {
        override def release(): Unit = body.discard()
      }
      p.success(response)
    }
    else {
      logger.debug(s"State: $buffer, ${codec.preludeComplete}, ${codec.responseLineComplete}, ${codec.headersComplete}")
      channelReadThenReceive(p)
    }
  }

  private def channelReadThenReceive(p: Promise[ReleaseableResponse]): Unit = {
    state match {
      case Running(_, false) => channelRead().onComplete {
        case Success(buffer) => receiveResponse(buffer, p)
        case Failure(ex) =>
          handleError("channelReadThenReceive", ex)
          p.tryFailure(ex)
      }

      case Closed(ex) => p.tryFailure(ex)
      case state => illegalState("channelReadThenReceive", state)
    }
  }

  // MessageBody //////////////////////////////////////////////////////////////////////////

  private class ClientBodyReader(private[this] var buffer: ByteBuffer) extends BodyReader {
    // Acquired on creation! What a good deal.
    private[this] val myDispatchId = dispatchId
    private[this] var closedException: Throwable = null

    // must be called from within the stages `lock`
    private def validDispatch: Boolean = myDispatchId == dispatchId

    override def discard(): Unit = stageLock.synchronized {
      if (closedException == null) {
        closedException = EOF

        if (validDispatch) {
          // We need to try and burn through any remaining buffer
          // to see if we can put the parser in a sane state to perform
          // another dispatch, otherwise we need to kill the session.
          while (buffer.hasRemaining && !codec.contentComplete()) {
            val _ = codec.parseData(buffer)
          }

          state match {
            case r@Running(_, _) => r.readChannelClear = codec.contentComplete()
            case _ => // nop
          }
        }
      }
    }

    override def isEmpty: Boolean = stageLock.synchronized {
      closedException != null || !validDispatch
    }

    override def apply(): Future[ByteBuffer] = {
      val p = Promise[ByteBuffer]
      parseBody(p)
      p.future
    }

    // Must be called from with this and the stages `lock`
    private def parseBody(p: Promise[ByteBuffer]): Unit = stageLock.synchronized {
      if (closedException == EOF) p.success(BufferTools.emptyBuffer)
      else if (closedException != null) p.failure(closedException)
      else {
        logger.debug(
          s"ParseBody[$buffer, chunking: ${codec.isChunked}, " +
          s"complete: ${codec.contentComplete()}, buffer: $buffer, state: $state]")

        if (!validDispatch) p.failure(EOF)
        else state match {
          case Closed(ex) => p.failure(ex)
          case Running(_, false) =>
            if (codec.contentComplete()) {
              discard()
              assert(!buffer.hasRemaining)  // That would be pretty wild if we still had some data laying around
              // When do we consider the dispatch complete? After we receive the whole body? After we have received
              // the whole body and our whole request body has been sent?
              p.success(BufferTools.emptyBuffer)
            }
            else if (!buffer.hasRemaining) readAndParseBody(p)
            else {
              val out = codec.parseData(buffer)
              if (!out.hasRemaining && !codec.contentComplete) readAndParseBody(p)
              else p.success(out)
            }

          case state => illegalState("parseBody", state)
        }
      }
    }

    private def readAndParseBody(p: Promise[ByteBuffer]): Unit = {
      channelRead().onComplete { result =>
        stageLock.synchronized { result match {
          case Success(b) =>
            buffer = b
            parseBody(p)

          case Failure(ex) =>
            closedException = ex
            p.failure(ex)
        }}
      }
      p.future
    }
  }

  // MessageBody //////////////////////////////////////////////////////////////////////////

  // Must be called from within the lock
  private def getBody(buffer: ByteBuffer): BodyReader = {
    if (codec.contentComplete()) BodyReader.EmptyBodyReader
    else {
      logger.debug("Content is not complete. Getting body reader.")
      new ClientBodyReader(buffer)
    }
  }

  /* Must be called from within the lock

     The returned Future is satisfied once the entire request has been written
     to the wire. Any errors that occur during the write process, including those
     that come from the request body, will result in the failed Future.
   */
  private def launchWriteRequest(request: HttpRequest): Future[Unit] = {
    val requestBuffer = encodeRequestPrelude(request)
    val encoder = Http1BodyEncoder(request)
    val p = Promise[Unit]

    def writeLoop(): Unit = {
      request.body().onComplete {
        case Success(b) if !b.hasRemaining =>
          val last = encoder.finish()
          if (last.hasRemaining) p.completeWith(channelWrite(last))
          else p.success(())

        case Success(b) =>
          channelWrite(encoder.encode(b)).onComplete {
            case Success(_) => writeLoop()
            case Failure(err) =>
              // Ensure we close any resources associated with the body
              request.body.discard()
              p.tryFailure(err)
          }
        case Failure(err) =>  p.tryFailure(err)
      }
    }

    channelWrite(requestBuffer)
      .onComplete {
        case Success(_) => writeLoop()
        case Failure(e) =>
          request.body.discard()
          p.tryFailure(e)
      }

    p.future
}

  // Generally shuts things down. These may be normal errors
  private def handleError(phase: String, err: Throwable): Unit = stageLock.synchronized {
    logger.debug(err)(s"Phase $phase resulted in an error. Current state: $state")
    state = Closed(err)
    stageShutdown()
  }

  private def illegalState(phase: String, state: State): Nothing = {
    val ex = new IllegalStateException(s"Found illegal state $state in phase $phase")
    handleError(phase, ex)
    throw ex
  }
}

private object Http1ClientStage {

  sealed trait State
  sealed trait ClosedState extends State

  case object Unconnected extends ClosedState // similar to closed, but can transition to idle
  case class Running(var writeChannelClear: Boolean, var readChannelClear: Boolean) extends State
  case class Closed(reason: Throwable) extends ClosedState

  // Doesn't attempt to do any request validation, just encodes
  // the request line and the headers and the trailing "\r\n"
  def encodeRequestPrelude(request: HttpRequest): ByteBuffer = {

    val sb = new StringBuilder(256)

    sb.append(request.method).append(' ').append(request.uri).append(' ').append("HTTP/1.1\r\n")

    request.headers.foreach { case (k, v) =>
      sb.append(k)
      if (v.length > 0) sb.append(": ").append(v)
      sb.append("\r\n")
    }

    sb.append("\r\n")

    ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1))
  }

  def bufferAsString(buffer: ByteBuffer): String = {
    val b = buffer.duplicate()
    StandardCharsets.UTF_8.decode(b).toString
  }
}