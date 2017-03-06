package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.log4s.getLogger

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/** Gracefully coordinate writes
  *
  * The `WriteController`s job is to direct outbound data in both a fair, efficient, and
  * thread safe manner. All calls to the `WriteControler` are expected to come from within
  * the session executor.
  *
  * @param highWaterMark number of bytes that will trigger a flush.
  * @param sessionExecutor serial executor that belongs to the session.
  */
private abstract class WriteController(highWaterMark: Int, sessionExecutor: ExecutionContext) {

  final protected val logger = getLogger

  // TODO: this should be on only one who needs to worry about the Encoder

  private[this] var inWriteCycle = false
  private[this] var pendingWrites = new ArrayBuffer[ByteBuffer](4)
  private[this] var pendingWriteBytes: Int = 0

  private[this] val interestedStreams = new java.util.ArrayDeque[WriteListener]

  /** Write the outbound data to the pipeline */
  protected def writeToWire(data: Seq[ByteBuffer]): Future[Unit]

  /** Queue data for the wire
    *
    * The data may be stored in a buffer if a write is currently in progress.
    */
  final def writeOutboundData(data: Seq[ByteBuffer]): Unit = {
    data.foreach(accBuffer)
    maybeWrite()
  }

  /** Queue data for the wire
    *
    * The data may be stored in a buffer if a write is currently in progress.
    */
  final def writeOutboundData(data: ByteBuffer): Unit = {
    accBuffer(data)
    maybeWrite()
  }

  private[this] def accBuffer(data: ByteBuffer): Unit = {
    if (data.hasRemaining) {
      pendingWrites += data
      pendingWriteBytes += data.remaining()
    }
  }

  /** Register a listener to be invoked when the pipeline is ready to perform write operations */
  final def registerWriteInterest(stream: WriteListener): Unit = {
    interestedStreams.add(stream)
    maybeWrite()
  }

  private[this] def pendingInterests: Boolean = !pendingWrites.isEmpty || !interestedStreams.isEmpty

  private[this] def maybeWrite(): Unit = {
    if (!inWriteCycle) doWrite()
  }

  // The meat and potatoes
  private[this] def doWrite(): Unit = {
    inWriteCycle = true

    // Accumulate bytes until we run out of interests or have exceeded the high-water mark
    while(!interestedStreams.isEmpty && pendingWriteBytes < highWaterMark) {
      try {
        interestedStreams.poll().performStreamWrite(this)
      } catch { case NonFatal(t) =>
        logger.error(t)(s"Unhandled exception performing stream write operation")
      }
    }

    if (pendingWriteBytes > 0) {
      // We have some data waiting to go out. Take it and make a new accumulation buffer
      val out = pendingWrites

      pendingWrites = new ArrayBuffer[ByteBuffer](out.length + 4)
      pendingWriteBytes = 0

      writeToWire(out).onComplete {
        case Success(_) =>
          // See if we need to continue the loop or can terminate
          if (pendingInterests) {
            inWriteCycle = true
            doWrite()
          }
          else {
            inWriteCycle = false
          }
        case Failure(ex) => ???
      }(sessionExecutor)
    } else {
      // Didn't write anything, so the cycle is finished
      inWriteCycle = false
    }
  }
}
