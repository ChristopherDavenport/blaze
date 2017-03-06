package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2._
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.pipeline.stages.OneMessageStage
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.{ExecutionContext, Future, Promise}

/** Stage capable of performing the client HTTP2 handshake
  * and returning a `ClientSession` which is ready to dispatch
  * requests.
  *
  * The handshake goes like this:
  * - Send the handshake preface
  * - Send the initial settings frame
  * - Receive the initial settings frame
  * - Ack the initial settings frame
  * - Finish constructing the connection
  *
  * @param mySettings settings to transmit to the server while performing the handshake
  */
private class Http2TlsClientHandshaker(
    mySettings: Http2Settings,
    flowStrategy: FlowStrategy,
    executor: ExecutionContext)
  extends TailStage[ByteBuffer] {

  private implicit def ec = Execution.trampoline

  def name: String = getClass.getSimpleName

  private[this] val session = Promise[Http2ClientConnection]

  def clientSession: Future[Http2ClientConnection] = session.future

  override protected def stageStartup(): Unit = {
    logger.debug(s"Http2ClientHandshaker initiating handshake")
    session.completeWith(handshake())
  }

  override protected def stageShutdown(): Unit = super.stageShutdown()

  /** Performs a handshake and, if successful, installs a `ClientSession`
    * in the pipeline and removes this stage.
    */
  private def handshake(): Future[Http2ClientConnection] = {
    val settingsBuffer = Http20FrameSerializer.mkSettingsFrame(false, mySettings.toSeq)
    channelWrite(Seq(bits.getHandshakeBuffer(), settingsBuffer)).flatMap { _ =>
      logger.debug("Receiving settings")
      receiveSettings(BufferTools.emptyBuffer)
    }
  }

  private def receiveSettings(acc: ByteBuffer): Future[Http2ClientConnection] = {
    Http20FrameDecoder.getFrameSize(acc) match {
      case -1 =>
        channelRead().flatMap { buff =>
          receiveSettings(BufferTools.concatBuffers(acc, buff))
        }

        // still need more data
      case size if acc.remaining() < size =>
        channelRead().flatMap { buff =>
          receiveSettings(BufferTools.concatBuffers(acc, buff))
        }

      case size =>  // we have at least a settings frame
        logger.debug(s"Need $size bytes, have ${acc.remaining}")
        val settingsBuffer = BufferTools.takeSlice(acc, size)
        SettingsDecoder.decodeSettingsFrame(settingsBuffer) match {
          case Right(settingsFrame) =>
            logger.debug(s"Received settings frame: $settingsFrame")
            Future.successful(installHttp2ClientStage(settingsFrame, acc))

          case Left(http2Exception) =>
            val reply = Http20FrameSerializer.mkGoAwayFrame(0, http2Exception)

            // Its too bad we can't do some ensure style stuff...
            val p = Promise[Http2ClientConnection]
            channelWrite(reply).onComplete { _ =>
              sendOutboundCommand(Command.Disconnect)
              p.tryFailure(http2Exception)
            }

            p.future
        }
    }
  }

  // Setup the pipeline with a new Http2ClientStage and start it up, then return it.
  private def installHttp2ClientStage(frame: SettingsFrame, remainder: ByteBuffer): Http2ClientConnection = {
    val peerSettings = SettingsDecoder.settingsFromFrame(frame.settings)
    val http2Encoder = new Http2FrameEncoder(peerSettings, new HeaderEncoder(peerSettings.headerTableSize))
    val stage = new Http2ClientConnectionImpl(
      mySettings,
      peerSettings,
      http2Encoder,
      new HeaderDecoder(mySettings.maxHeaderListSize, mySettings.headerTableSize),
      flowStrategy,
      executor
    )
    var newTail = LeafBuilder(stage)

    if (remainder.hasRemaining) {
      newTail = newTail.prepend(new OneMessageStage[ByteBuffer](remainder))
    }

    this.replaceTail(newTail, true)
    stage
  }
}

private object Http2TlsClientHandshaker {

  val DefaultClientSettings: Seq[Setting] = Vector(
    Http2Settings.ENABLE_PUSH(0) /*false*/
  )
}
