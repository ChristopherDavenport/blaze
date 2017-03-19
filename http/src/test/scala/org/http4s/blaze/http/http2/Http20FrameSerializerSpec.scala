package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.Headers
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.util.BufferTools
import org.http4s.blaze.util.BufferTools._

import org.specs2.mutable.Specification


object CodecUtils {
  def mkData(size: Int): ByteBuffer = {
    val s = "The quick brown fox jumps over the lazy dog".getBytes()
    val buff = ByteBuffer.allocate(size)
    while(buff.hasRemaining) buff.put(s, 0, math.min(buff.remaining(), s.length))
    buff.flip()
    buff
  }

  def compare(s1: Seq[ByteBuffer], s2: Seq[ByteBuffer]): Boolean = {
    val b1 = joinBuffers(s1)
    val b2 = joinBuffers(s2)
    b1.equals(b2)
  }

  class TestHttp20FrameDecoder(val handler: Http20FrameHandler) extends Http20FrameDecoder(Http2Settings.default(), handler)

  def decoder(h: Http20FrameHandler, inHeaders: Boolean = false): TestHttp20FrameDecoder =
    new TestHttp20FrameDecoder(h)

  val bonusSize = 10

  def addBonus(buffers: Seq[ByteBuffer]): ByteBuffer = {
    joinBuffers(buffers :+ ByteBuffer.allocate(bonusSize))
  }
}

class Http20FrameSerializerSpec extends Specification {
  import CodecUtils._

  "DATA frame" should {

    def dat = mkData(20)

    def dec(sId: Int, isL: Boolean, padding: Int): TestHttp20FrameDecoder =
      decoder(new MockFrameHandler(false) {
        override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = {
          streamId must_== sId
          isLast must_== isL
          compare(data::Nil, dat::Nil) must beTrue
          padding must_== flowSize

          Continue
        }
    })

    "make round trip" in {
      val buff1 = joinBuffers(Http20FrameSerializer.mkDataFrame(1, true, 0, dat))
      dec(1, true, dat.remaining()).decodeBuffer(buff1) must_== Continue
    }

    "Decode 'END_STREAM flag" in {
      // payload size is buffer + padding + 1 byte
      val buff2 = joinBuffers(Http20FrameSerializer.mkDataFrame(3, false, 100, dat))
      dec(3, false, dat.remaining() + 100).decodeBuffer(buff2) must_== Continue
    }

    "Decode padded buffers" in {
      val buff3 = addBonus(Http20FrameSerializer.mkDataFrame(1, true, 100, dat))
      dec(1, true, dat.remaining() + 100).decodeBuffer(buff3) must_== Continue
      buff3.remaining() must_== bonusSize
    }


  }

  // This doesn't check header compression
  "HEADERS frame" should {
    def dat = mkData(20)

    def dec(sId: Int, pri: Option[Priority], end_h: Boolean,  end_s: Boolean) =
      decoder(new MockFrameHandler(false) {
        override def onHeadersFrame(streamId: Int,
                                    priority: Option[Priority],
                                    end_headers: Boolean,
                                    end_stream: Boolean,
                                    buffer: ByteBuffer): Http2Result = {
          sId must_== streamId
          pri must_== priority
          end_h must_== end_headers
          end_s must_== end_stream
          assert(compare(buffer::Nil, dat::Nil))
          Continue
        }
    })

    "make round trip" in {
      val buff1 = joinBuffers(Http20FrameSerializer.mkHeaderFrame(1, None, true, true, 0, dat))
      dec(1, None, true, true).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0

      val priority = Priority(3, false, 6)

      val buff2 = joinBuffers(Http20FrameSerializer.mkHeaderFrame(2, Some(priority), true, false, 0, dat))
      dec(2, Some(priority), true, false).decodeBuffer(buff2) must_== Continue
      buff2.remaining() must_== 0
    }

    "preserve padding" in {
      val buff = addBonus(Http20FrameSerializer.mkHeaderFrame(1, None, true, true, 0, dat))
      dec(1, None, true, true).decodeBuffer(buff) must_== Continue
      buff.remaining() must_== bonusSize
    }

    "fail on bad stream ID" in {
      Http20FrameSerializer.mkHeaderFrame(0, None, true, true, 0, dat) must throwA[Exception]
    }

    "fail on bad padding" in {
      Http20FrameSerializer.mkHeaderFrame(1, None, true, true, -10, dat) must throwA[Exception]
    }
  }

  "PRIORITY frame" should {
    def dec(sId: Int, p: Priority) =
      decoder(new MockFrameHandler(false) {
        override def onPriorityFrame(streamId: Int, priority: Priority): Http2Result = {
          sId must_== streamId
          p must_== priority
          Continue
        }
      })

    "make a round trip" in {
      val buff1 = Http20FrameSerializer.mkPriorityFrame(1, Priority(2, true, 1))
      dec(1, Priority(2, true, 1)).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0

      val buff2 = Http20FrameSerializer.mkPriorityFrame(1, Priority(2, false, 10))
      dec(1, Priority(2, false, 10)).decodeBuffer(buff2) must_== Continue
      buff2.remaining() must_== 0
    }

    "fail on bad priority" in {
      Priority(1, true, 500) must throwA[Exception]
      Priority(1, true, -500) must throwA[Exception]
    }

    "fail on bad streamId" in {
      Http20FrameSerializer.mkPriorityFrame(0, Priority(1, true, 0)) must throwA[Exception]
    }

    "fail on bad stream dependency" in {
      Priority(0, true, 0) must throwA[Exception]
    }
  }

  "RST_STREAM frame" should {
    def dec(sId: Int, c: Int) =
      decoder(new MockFrameHandler(false) {

        override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = {
          sId must_== streamId
          c must_== code
          Continue
        }
      })

    "make round trip" in {
      val buff1 = Http20FrameSerializer.mkRstStreamFrame(1, 0)
      dec(1, 0).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "fail on bad stream Id" in {
      Http20FrameSerializer.mkRstStreamFrame(0, 1) must throwA[Exception]
    }
  }

  "SETTINGS frame" should {
    def dec(a: Boolean, s: Seq[Setting]) =
      decoder(new MockFrameHandler(false) {
        override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
          s must_== settings
          a must_== ack
          Continue
        }
      })

    "make a round trip" in {
      val settings = (0 until 100).map(i => Setting(i, i + 3))

      val buff1 = Http20FrameSerializer.mkSettingsFrame(false, settings)
      dec(false, settings).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "reject settings on ACK" in {
      val settings = (0 until 100).map(i => Setting(i, i + 3))
      Http20FrameSerializer.mkSettingsFrame(true, settings) must throwA[Exception]
    }
  }

  "PING frame" should {
    def dec(d: ByteBuffer, a: Boolean) =
      decoder(new MockHeaderAggregatingFrameHandler {
        override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = {
          ack must_== a
          assert(compare(ByteBuffer.wrap(data)::Nil, d::Nil))
          Continue
        }
      })

    "make a simple round trip" in {
      val data = Array(1,2,3,4,5,6,7,8).map(_.toByte)

      val bs1 = Http20FrameSerializer.mkPingFrame(false, data)
      dec(ByteBuffer.wrap(data), false).decodeBuffer(bs1) must_== Continue

      val bs2 = Http20FrameSerializer.mkPingFrame(true, data)
      dec(ByteBuffer.wrap(data), true).decodeBuffer(bs2) must_== Continue
    }
  }

  "GOAWAY frame" should {
    def dec(sId: Int, err: Long, d: ByteBuffer) =
      decoder(new MockHeaderAggregatingFrameHandler {


        override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = {
          sId must_== lastStream
          err must_== errorCode
          assert(compare(d::Nil, debugData::Nil))
          Continue
        }
      })

    "make a simple round trip" in {
      val bs1 = Http20FrameSerializer.mkGoAwayFrame(1, 0, emptyBuffer)
      dec(1, 0, emptyBuffer).decodeBuffer(joinBuffers(bs1)) must_== Continue
    }

    "make a round trip with data" in {
      def data = mkData(20)

      val bs1 = Http20FrameSerializer.mkGoAwayFrame(1, 0, data)
      dec(1, 0, data).decodeBuffer(joinBuffers(bs1)) must_== Continue
    }
  }

  "WINDOW_UPDATE frame" should {
    def dec(sId: Int, inc: Int) =
      decoder(new MockHeaderAggregatingFrameHandler {
        override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = {
          sId must_== streamId
          inc must_== sizeIncrement
          Continue
        }
      })

    "make a simple round trip" in {
      val bs1 = Http20FrameSerializer.mkWindowUpdateFrame(0, 10)
      dec(0, 10).decodeBuffer(bs1) must_== Continue
    }

    "fail on invalid stream" in {
      Http20FrameSerializer.mkWindowUpdateFrame(-1, 10) must throwA[Exception]
    }

    "fail on invalid window update" in {
      Http20FrameSerializer.mkWindowUpdateFrame(1, 0) must throwA[Exception]
      Http20FrameSerializer.mkWindowUpdateFrame(1, -1) must throwA[Exception]
      println(Integer.MAX_VALUE)
      Http20FrameSerializer.mkWindowUpdateFrame(1, Integer.MAX_VALUE + 1) must throwA[Exception]
    }

  }
}
