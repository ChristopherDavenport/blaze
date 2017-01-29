package org.http4s.blaze.http.http1x

import java.nio.ByteBuffer

import org.http4s.blaze.http.HttpResponsePrelude
import org.http4s.blaze.http.client.HttpClientConfig
import org.http4s.blaze.http.parser.Http1ClientParser
import org.http4s.blaze.util.BufferTools


final class Http1ClientCodec(config: HttpClientConfig)
  extends Http1ClientParser(config.maxRequestLine, config.maxHeaderLength, 1024, Int.MaxValue, config.lenientParser) {

  private[this] val headers = Vector.newBuilder[(String, String)]
  private[this] var code: Int = -1
  private[this] var reason = ""
  private[this] var scheme = ""
  private[this] var majorVersion = -1
  private[this] var minorVersion = -1

  protected def submitResponseLine(code: Int, reason: String, scheme: String, majorversion: Int, minorversion: Int): Unit = {
    this.code = code
    this.reason = reason
    this.scheme = scheme
    this.majorVersion = majorversion
    this.minorVersion = minorversion
  }

  protected def headerComplete(name: String, value: String): Boolean = {
    headers += name -> value
    false
  }

  def getResponsePrelude: HttpResponsePrelude = HttpResponsePrelude(this.code, this.reason, headers.result())

  def preludeComplete(): Boolean = headersComplete

  /** Parse the response prelude, which consists of the repsonse line and headers
    *
    * Returns `true` once the prelude is complete.
    */
  def parsePrelude(buffer: ByteBuffer): Boolean = {
    if (!responseLineComplete && !parseResponseLine(buffer)) false
    else if (!headersComplete && !parseHeaders(buffer)) false
    else true
  }

  /** Parse the body of the response
    *
    * If The content is complete or more input is required, an empty buffer is returned.
    */
  def parseData(buffer: ByteBuffer): ByteBuffer = {
    if (contentComplete()) BufferTools.emptyBuffer
    else parseContent(buffer) match {
      case null => BufferTools.emptyBuffer
      case other => other
    }
  }
}
