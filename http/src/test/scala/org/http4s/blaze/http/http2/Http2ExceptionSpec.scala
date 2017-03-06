package org.http4s.blaze.http.http2

import org.specs2.mutable.Specification

class Http2ExceptionSpec extends Specification {
  import Http2Exception._

  "Http2Exception" should {
    "be a connection error for stream id 0" in {
      PROTOCOL_ERROR.goaway("").isStreamError must beFalse
      PROTOCOL_ERROR.rst(1, "").isStreamError must beTrue
    }
  }
}
