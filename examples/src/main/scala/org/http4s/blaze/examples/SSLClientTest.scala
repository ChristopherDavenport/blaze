package org.http4s.blaze.examples

import scala.concurrent.Await
import scala.concurrent.duration._
import org.http4s.blaze.http.client
import org.http4s.blaze.util.Execution

object SSLClientTest {

  implicit def ec = Execution.trampoline

  def main(args: Array[String]) {
    val f = client.basicHttp1Client.GET("https://www.google.com/"){ r => r.stringBody().map((r -> _)) }

    val (r, body) = Await.result(f, 10.seconds)

    println(r)
    println(body)
  }
}
