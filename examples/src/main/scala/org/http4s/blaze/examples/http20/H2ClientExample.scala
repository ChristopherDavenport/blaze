package org.http4s.blaze.examples.http20

import java.io.{InputStream, InputStreamReader}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

import org.http4s.blaze.http.HttpClient
import org.http4s.blaze.http.http2.client.Http2Client
import org.http4s.blaze.util.Execution

import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
//import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


object H2ClientExample {

  implicit val ec = Execution.trampoline

  val h2Clients: Array[HttpClient] = Array.tabulate(3){_ => Http2Client.newH2Client() }

  private def gunzipString(data: ByteBuffer): String = {
    val is = new InputStream {
      override def read(): Int = {
        if (data.hasRemaining) data.get() & 0xff
        else -1
      }
    }
    val reader = new InputStreamReader(new GZIPInputStream(is))

    val acc = new StringBuilder

    @tailrec
    def go(): Unit = {
      val c = reader.read()
      if (c != -1) {
        acc += c.asInstanceOf[Char]
        go()
      }
    }

    go()
    acc.result()
  }

  def callGoogle(tag: Int): Future[String] = {
    Http2Client.defaultH2Client.GET("https://www.google.com/") { resp =>
//      println(s"Response: $resp")
      resp.body.accumulate().map { bytes =>
        println(s"Finished response $tag")
        StandardCharsets.UTF_8.decode(bytes).toString
      }
    }
  }

  def callTwitter(tag: Int): Future[String] = {
    Http2Client.defaultH2Client.GET("https://twitter.com/") { resp =>
//      println(s"Response: $resp")
      resp.body.accumulate().map { bytes =>
        println(s"Finished response $tag of size ${bytes.remaining()}")
        gunzipString(bytes)
      }
    }
  }

  def callLocalhost(tag: Int): Future[Int] = {
    val dest = "bigstring"
    h2Clients(tag % h2Clients.length).GET(s"https://localhost:8443/$dest") { resp =>
      resp.body.accumulate().map { bytes =>
        //println(s"Finished response $tag of size ${bytes.remaining()}")
        bytes.remaining
      }
    }
  }

  def main(args: Array[String]): Unit = {
    println("Hello, world!")

    Await.result(Future.sequence((0 until h2Clients.length).map(callLocalhost)), 5.seconds)

    def fresps(i: Int) = (h2Clients.length until i).map { i =>
      callLocalhost(i).map(i -> _)
    }

    Await.result(Future.sequence(fresps(100)), 500.seconds)
    val start = System.currentTimeMillis
    val resps = Await.result(Future.sequence(fresps(500)), 500.seconds)
    val duration = System.currentTimeMillis - start

    val chars = resps.foldLeft(0){ case (acc, (i, len)) =>
      acc + len
    }

    println(s"The total body length of ${resps.length} messages: $chars. Took $duration millis")

//    println(s"First response:\n" + r1)
  }

}
