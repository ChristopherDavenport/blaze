package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.stages.OneMessageStage
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution, StageTools}

import scala.concurrent.Future

private class Http2TlsServerHandshaker(
    mySettings: Http2Settings,
    nodeBuilder: Int => LeafBuilder[StreamMessage])
  extends TailStage[ByteBuffer] {

  private implicit def ec = Execution.trampoline

  val name: String = getClass.getSimpleName

  override protected def stageStartup(): Unit = synchronized {
      super.stageStartup()
      handshake()
  }

  override protected def stageShutdown(): Unit = super.stageShutdown()

  def handshake(): Unit = {
    logger.debug("Beginning handshake.")
    val settingsBuffer = Http20FrameSerializer.mkSettingsFrame(false, mySettings.toSeq)

    channelWrite(settingsBuffer).flatMap { _ =>
      receivePrelude()
    }.onFailure { case ex =>
      logger.error(ex)("Failed to received prelude")
      sendOutboundCommand(Command.Disconnect)
    }
  }

  private def receivePrelude(): Future[Unit] =
    StageTools.accumulateAtLeast(bits.clientHandshakeString.length, this).flatMap { buf =>
      val prelude = BufferTools.takeSlice(buf, bits.clientHandshakeString.length)
      val preludeString = StandardCharsets.UTF_8.decode(prelude).toString
      if (preludeString == bits.clientHandshakeString) {
        receiveSettings(buf)
      } else {
        val msg = s"Invalid prelude: $preludeString"
        logger.error(msg)
        Future.failed(new Exception(msg))
      }
    }

  private def receiveSettings(acc: ByteBuffer): Future[Unit] = {
    logger.debug("receiving settings")
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
        val settingsBuffer = BufferTools.takeSlice(acc, size)
        SettingsDecoder.decodeSettingsFrame(settingsBuffer) match {
          case Right(settingsFrame) =>
            Future.successful(installHttp2ServerStage(settingsFrame, acc))

          case Left(http2Exception) =>
            val reply = Http20FrameSerializer.mkGoAwayFrame(0, http2Exception)

            val f = channelWrite(reply)
            f.onComplete { _ =>
              sendOutboundCommand(Command.Disconnect)
            }
            f
        }
    }
  }

  // Setup the pipeline with a new Http2ClientStage and start it up, then return it.
  private def installHttp2ServerStage(frame: SettingsFrame, remainder: ByteBuffer): Unit = {
    logger.debug(s"Installing pipeline with settings: $frame")
    val peerSettings = Http2Settings.default()

    import Http2Settings._

    // TODO: maybe we need all these settings in the settings object...
    frame.settings.foreach {
      case HEADER_TABLE_SIZE(size)      => peerSettings.headerTableSize = size.toInt
      case ENABLE_PUSH(enabled)         => peerSettings.pushEnabled = enabled != 0
      case MAX_CONCURRENT_STREAMS(max)  => peerSettings.maxInboundStreams = max.toInt
      case INITIAL_WINDOW_SIZE(size)    => peerSettings.initialWindowSize = size.toInt
      case MAX_FRAME_SIZE(size)         => peerSettings.maxFrameSize = size.toInt
      case MAX_HEADER_LIST_SIZE(size)   => peerSettings.maxHeaderListSize = size.toInt // TODO: what should we do about this?
      case _ => ???
    }

    val stage = Http2ServerConnectionImpl.basic(mySettings, peerSettings, nodeBuilder)

    var newTail = LeafBuilder(stage)

    if (remainder.hasRemaining) {
      newTail = newTail.prepend(new OneMessageStage[ByteBuffer](remainder))
    }

    this.replaceTail(newTail, true)
  }
}
