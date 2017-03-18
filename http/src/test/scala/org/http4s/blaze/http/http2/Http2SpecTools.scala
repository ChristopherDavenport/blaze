package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.Http2Exception.ErrorGenerator
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification

trait Http2SpecTools { self: Specification =>
  def connectionError(gen: ErrorGenerator): PartialFunction[Http2Result, MatchResult[_]] = {
    case Error(e: Http2SessionException) => e.code must_== gen.code
  }

  def streamError(stream: Int, gen: ErrorGenerator): PartialFunction[Http2Result, MatchResult[_]] = {
    case Error(e: Http2StreamException) if e.stream == stream => e.code must_== gen.code
  }

  lazy val ConnectionProtoError: PartialFunction[Http2Result, MatchResult[_]] =
    connectionError(Http2Exception.PROTOCOL_ERROR)
}
