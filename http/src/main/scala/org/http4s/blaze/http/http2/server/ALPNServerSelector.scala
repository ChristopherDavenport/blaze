package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import java.util
import javax.net.ssl.SSLEngine

import org.eclipse.jetty.alpn.ALPN
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage, Command => Cmd}
import org.http4s.blaze.util.Execution.trampoline

import scala.util.{Failure, Success}

/** Dynamically inject an appropriate pipeline using ALPN
  *
  * @param engine the `SSLEngine` in use for the connection
  * @param selector selects the preferred protocol from the seq of supported clients. May get an empty sequence.
  * @param builder builds the appropriate pipeline based on the
  */
class ALPNServerSelector(engine: SSLEngine,
                         selector: Seq[String] => String,
                         builder: String => LeafBuilder[ByteBuffer]) extends TailStage[ByteBuffer] {

  ALPN.put(engine, new ServerProvider)

  @volatile
  private var selected: Option[String] = None

  override def name: String = "PipelineSelector"

  override protected def stageStartup(): Unit = {
    // This shouldn't complete until the handshake is done and ALPN has been run.
    channelWrite(Nil).onComplete {
      case Success(_)       => selectPipeline()
      case Failure(Cmd.EOF) => // NOOP
      case Failure(t)       =>
        logger.error(t)(s"$name failed to startup")
        sendOutboundCommand(Cmd.Error(t))
    }(trampoline)
  }

  private def selectPipeline(): Unit = {
    try {
      val b = builder(selected.getOrElse(selector(Nil)))
      this.replaceTail(b, true)
    } catch {
      case t: Throwable =>
        logger.error(t)("Failure building pipeline")
        sendOutboundCommand(Cmd.Error(t))
    }
  }

  private class ServerProvider extends ALPN.ServerProvider {
    import scala.collection.JavaConverters._

    override def select(protocols: util.List[String]): String = {
      logger.debug("Available protocols: " + protocols)
      val s = selector(protocols.asScala)
      selected = Some(s)
      s
    }

    override def unsupported() {
      logger.debug(s"Unsupported protocols, defaulting to $selected")
    }
  }
}
