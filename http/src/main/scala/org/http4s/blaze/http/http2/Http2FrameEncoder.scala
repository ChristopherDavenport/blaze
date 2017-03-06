package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools

import scala.collection.immutable.VectorBuilder

/** A more humane interface for writing HTTP messages.
  */
final class Http2FrameEncoder(
    peerSettings: Http2Settings,
    headerEncoder: HeaderEncoder) {

  // Just a shortcut
  private def maxFrameSize: Int = peerSettings.maxFrameSize

  // Encoder side
  def setMaxTableSize(size: Int): Unit =
    headerEncoder.setMaxTableSize(size)

  def sessionWindowUpdate(size: Int): ByteBuffer =
    streamWindowUpdate(0, size)

  def streamWindowUpdate(streamId: Int, size: Int): ByteBuffer =
    Http20FrameSerializer.mkWindowUpdateFrame(streamId, size)

  def pingFrame(data: Array[Byte]): ByteBuffer =
    Http20FrameSerializer.mkPingFrame(false, data)

  def pingAck(data: Array[Byte]): ByteBuffer =
    Http20FrameSerializer.mkPingFrame(true, data)

  def dataFrame(streamId: Int, data: ByteBuffer, isLast: Boolean): Seq[ByteBuffer] = {
    val limit = maxFrameSize
    if (data.remaining <= limit) Http20FrameSerializer.mkDataFrame(streamId, data, isLast, 0)
    else { // need to fragment
      val acc = new VectorBuilder[ByteBuffer]
      while(data.hasRemaining) {
        val thisData = BufferTools.takeSlice(data, math.min(data.remaining, limit))
        val thisLast = isLast && !data.hasRemaining
        acc ++= Http20FrameSerializer.mkDataFrame(streamId, thisData, thisLast, 0 /*PADDING*/)
      }
      acc.result()
    }
  }

  def headerFrame(streamId: Int,
                  headers: Seq[(String, String)],
                  priority: Option[Priority],
                  eos: Boolean): Seq[ByteBuffer] = {
    val rawHeaders = headerEncoder.encodeHeaders(headers)

    val maxHeadersHeaderSize = 5 // priority(4) + weight(1), padding = 0
    val limit = maxFrameSize

    if (rawHeaders.remaining() + maxHeadersHeaderSize > limit) {
      // need to fragment
      val acc = new VectorBuilder[ByteBuffer]

      val headersBuf = BufferTools.takeSlice(rawHeaders, limit - maxHeadersHeaderSize)
      acc ++= Http20FrameSerializer.mkHeaderFrame(streamId, headersBuf, priority, false /*END_HEADERS */, eos, 0 /*padding*/)

      while(rawHeaders.hasRemaining) {
        val size = math.min(limit, rawHeaders.remaining)
        val continueBuf = BufferTools.takeSlice(rawHeaders, size)
        val endHeaders = rawHeaders.hasRemaining
        acc ++= Http20FrameSerializer.mkContinuationFrame(streamId, endHeaders, continueBuf)
      }
      acc.result()
    } else {

      Http20FrameSerializer.mkHeaderFrame(streamId, rawHeaders, priority, true /*END_HEADERS */, eos, 0 /*padding*/)
    }
  }
}
