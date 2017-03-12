package org.http4s.blaze.examples

import org.http4s.blaze.http.HttpClient

import scala.concurrent.Await
import scala.concurrent.duration._
import org.http4s.blaze.util.Execution

object SSLClientTest {

  implicit def ec = Execution.trampoline

  def main(args: Array[String]) {
    val f = HttpClient.basicHttp1Client.GET("https://www.google.com/"){ r => r.stringBody().map((r -> _)) }

    val (r, body) = Await.result(f, 10.seconds)

    println(r)
    println(body)
  }
}
