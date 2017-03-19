package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable
import scala.util.control.NoStackTrace

sealed abstract class Http2Exception(msg: String) extends Exception(msg) with NoStackTrace {

  def code: Int

  def stream: Int

  final def name: String = Http2Exception.errorName(code)

  /** Wrap this exception in an [[Error]] */
  final def toError: Error = Error(this)

  /** Determine if this is a stream or connection error */
  final def isStreamError: Boolean = stream > 0

  /** Convert this exception to a stream exception
    *
    * @note If this is already a stream exception but with a different stream id, the id will be changed
    */
  final def toStreamException(streamId: Int): Http2StreamException = this match {
    case ex: Http2StreamException if ex.stream == streamId => ex
    case ex => new Http2StreamException(streamId, ex.code, ex.getMessage)
  }

  /** Convert this exception to a session exception */
  final def toSessionException(): Http2SessionException = this match {
    case Http2StreamException(_, code, msg) => Http2SessionException(code, msg)
    case ex: Http2SessionException => ex
  }

  /** Was the exception due to refusal by the peer.
    *
    * These exceptions are safe to automatically retry even if the HTTP method
    * is not an idempotent method. See https://tools.ietf.org/html/rfc7540#section-8.1.4
    * for more details.
    */
  final def isRefusedStream: Boolean = code == Http2Exception.REFUSED_STREAM.code

  /** serialize the message as a `ByteBuffer` */
  final def msgBuffer(): ByteBuffer = ByteBuffer.wrap(msg.getBytes(UTF_8))
}

final case class Http2StreamException(stream: Int, code: Int, msg: String) extends Http2Exception(msg)

final case class Http2SessionException(code: Int, msg: String) extends Http2Exception(msg) {
  override def stream: Int = 0
}

///////////////////// HTTP/2.0 Errors //////////////////////////////
object Http2Exception {

  final class ErrorGenerator private[http2](val code: Int, val name: String) {
    /** Create a Http2Exception with stream id 0 */
    def goaway(): Http2Exception = Http2SessionException(code, name)

    /** Create a Http2Exception with stream id 0 */
    def goaway(msg: String): Http2Exception = Http2SessionException(code, name + ": " + msg)

    /** Create a Http2Exception with the requisite stream id */
    def rst(stream: Int): Http2Exception = rst(stream, name)

    /** Create a Http2Exception with the requisite stream id */
    def rst(stream: Int, msg: String): Http2Exception = Http2StreamException(stream, code, msg)

    /** Extract the optional stream id and the exception message */
    def unapply(ex: Http2Exception): Option[(Option[Int], String)] = {
      if (ex.code == code) {
        val stream = if (ex.isStreamError) Some(ex.stream) else None
        Some(stream -> ex.getMessage)
      }
      else None
    }

    def unapply(code: Int): Option[Unit] = {
      if (code == this.code) Some(()) else None
    }

    override val toString: String = s"$name(0x${Integer.toHexString(code)})"
  }

  def errorGenerator(code: Int): ErrorGenerator = exceptionsMap.get(code) match {
    case Some(gen) => gen
    case None => new ErrorGenerator(code, s"UNKNOWN(0x${Integer.toHexString(code)})")
  }

  /** Get the name associated with the error code */
  def errorName(code: Int): String = errorGenerator(code).name

  private[this] val exceptionsMap = new mutable.HashMap[Int, ErrorGenerator]()

  private def mkErrorGen(code: Int, name: String): ErrorGenerator = {
    val g = new ErrorGenerator(code, name)
    exceptionsMap += ((code, g))
    g
  }

  val NO_ERROR                 = mkErrorGen(0x0, "NO_ERROR")
  val PROTOCOL_ERROR           = mkErrorGen(0x1, "PROTOCOL_ERROR")
  val INTERNAL_ERROR           = mkErrorGen(0x2, "INTERNAL_ERROR")
  val FLOW_CONTROL_ERROR       = mkErrorGen(0x3, "FLOW_CONTROL_ERROR")
  val SETTINGS_TIMEOUT         = mkErrorGen(0x4, "SETTINGS_TIMEOUT")
  val STREAM_CLOSED            = mkErrorGen(0x5, "STREAM_CLOSED")
  val FRAME_SIZE_ERROR         = mkErrorGen(0x6, "FRAME_SIZE_ERROR")
  val REFUSED_STREAM           = mkErrorGen(0x7, "REFUSED_STREAM")
  val CANCEL                   = mkErrorGen(0x8, "CANCEL")
  val COMPRESSION_ERROR        = mkErrorGen(0x9, "COMPRESSION_ERROR")
  val CONNECT_ERROR            = mkErrorGen(0xa, "CONNECT_ERROR")
  val ENHANCE_YOUR_CALM        = mkErrorGen(0xb, "ENHANCE_YOUR_CALM")
  val INADEQUATE_SECURITY      = mkErrorGen(0xc, "INADEQUATE_SECURITY")
  val HTTP_1_1_REQUIRED        = mkErrorGen(0xd, "HTTP_1_1_REQUIRED")

}
