package org.http4s.blaze.examples.http20

import java.io.{InputStream, InputStreamReader}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

import org.http4s.blaze.http.http2.client.Http2Client

import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


object H2ClientExample {

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

  def callLocalhost(tag: Int): Future[String] = {
    Http2Client.defaultH2Client.GET("https://localhost:8443/bigstring") { resp =>
      resp.body.accumulate().map { bytes =>
        println(s"Finished response $tag of size ${bytes.remaining()}")
        StandardCharsets.UTF_8.decode(bytes).toString
      }
    }
  }

  def main(args: Array[String]): Unit = {
    println("Hello, world!")

    val r1 = Await.result(callLocalhost(-1), 5.seconds)

    val fresps = (0 until 1000).map { i =>
      callLocalhost(i).map(i -> _.length)
    }

    val resps = Await.result(Future.sequence(fresps), 50.seconds)

    val chars = resps.foldLeft(0){ case (acc, (i, len)) =>
      acc + len
    }

    println(s"The total body length of ${resps.length} messages: $chars")

//    println(s"First response:\n" + r1)
  }

}
