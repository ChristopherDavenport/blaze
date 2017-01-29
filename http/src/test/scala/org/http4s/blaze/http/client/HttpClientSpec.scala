package org.http4s.blaze.http.client

import org.specs2.mutable._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


class HttpClientSpec extends Specification {

  // TODO: we shouldn't be calling out to external resources for tests
  "HttpClient" should {
    "Make https requests" in {
      val f = basicHttp1Client.GET("https://github.com/", Seq("host" -> "github.com")){ r => r.stringBody().map((_, r.code)) }
      val (body, code) = Await.result(f, 10.seconds)
      println(s"Body: $body")
      code must_== 200
    }
  }
}
