package org.http4s.blaze.http

import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration._


class BodyReaderSpec extends Specification {

  private def await[T](v: Awaitable[T]): T = Await.result(v, 2.seconds)

  "EmptyMessageBody" should {

    def empty = BodyReader.EmptyBodyReader

    "Be empty" in {
      val e = empty

      e.isEmpty must beTrue
      await(e()).remaining() must_== 0
      e.isEmpty must beTrue
    }

    "Return empty ByteBuffers" in {
      val e = empty
      await(e()).remaining() must_== 0
      // try it a second time, just to be sure
      await(e()).remaining() must_== 0
    }

    "`discard()` is a no-op" in {
      val e = empty
      await(e()).remaining() must_== 0

      e.discard()
      await(e()).remaining() must_== 0
    }
  }

  "singleBuffer" should {
    "return the EmptyBuffer if the passed ByteBuffer is empty" in {
      (BodyReader.singleBuffer(BufferTools.emptyBuffer) eq BodyReader.EmptyBodyReader) must beTrue
    }

    "have one chunk" in {
      val data = ByteBuffer.wrap(Array[Byte](1,2,3))
      val body = BodyReader.singleBuffer(data)

      body.isEmpty must beFalse
      (await(body()) eq data) must beTrue

      body.isEmpty must beTrue
    }
  }

  "BodyReader.accumulate" should {
    val data = ByteBuffer.wrap(Array[Byte](1,2,3))

    "be fine without an overflow" in {
      val body = BodyReader.singleBuffer(data)
      await(body.accumulate(3)).remaining() must_== 3
    }

    "return a BodyReaderOverflowException on overflow" in {
      val body = BodyReader.singleBuffer(data)
      await(body.accumulate(2)) must throwA[BodyReader.BodyReaderOverflowException]
    }
  }
}
