package org.http4s.blaze.http

import java.nio.ByteBuffer

import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

/** Representation of a HTTP message body
  *
  * @note The release of resources must be idempotent, meaning that `discard()` may
  *       be called after complete consumption of the body and it may also be called
  *       numerous times.
  */
trait BodyReader {

  /** Throw away this `MessageBody` */
  def discard(): Unit

  /** Get a `Future` which may contain message body data.
    *
    * If no data remains, the `ByteBuffer` will be empty as defined by `ByteBuffer.hasRemaining()`
    */
  def apply(): Future[ByteBuffer]

  /** Examine whether the `MessageBody` may yield additional data.
    *
    * This may be a result of being discarded, failure, or deletion of the data stream.
    *
    * Because `MessageBody` is async it is not, in general, possible to definitively determine
    * if more data remains in the stream. Therefore, the contract of this method is that a return
    * value of `true` guarantees that no more data can be obtained from this `MessageBody`, but a
    * return value of `false` does not guarantee more data.
    */
  def isEmpty: Boolean

  /** Accumulate any remaining data.
    *
    * The remainder of the message body will be accumulated into a single buffer. If no data remains,
    * the `ByteBuffer` will be empty as defined by `ByteBuffer.hasRemaining()`
    *
    * @param max maximum bytes to accumulate before resulting in a failed future with the exception
    *            `MessageBody.BodyReaderOverflowException`.
    */
  def accumulate(max: Int = Int.MaxValue): Future[ByteBuffer] =
    BodyReader.accumulate(max, this)
}

private object BodyReader {

  final class BodyReaderOverflowException(val max: Int, val accumulated: Long)
    extends Exception(s"Message body overflowed. Maximum permitted: $max, accumulated: $accumulated")

  /** The canonical empty [[BodyReader]]
    *
    * This should be the instance you use if you want to signal that the message body is
    * in guaranteed to be empty.
    */
  val EmptyBodyReader: BodyReader = new BodyReader {
    override def discard(): Unit = ()
    override def apply(): Future[ByteBuffer] = BufferTools.emptyFutureBuffer
    override def isEmpty: Boolean = true
  }

  /** Construct a [[BodyReader]] with exactly one chunk of data
    *
    * This method takes ownership if the passed `ByteBuffer`: any changes to the underlying
    * buffer will be visible to the consumer of this `MessageBody` and vise versa.
    *
    * @note if the passed buffer is empty, the `EmptyBodyReader` is returned.
    */
  def singleBuffer(buffer: ByteBuffer): BodyReader = {
    if (!buffer.hasRemaining) EmptyBodyReader
    else new BodyReader {
      private[this] var buff = buffer

      override def discard(): Unit = this.synchronized {
        buff = BufferTools.emptyBuffer
      }

      override def isEmpty: Boolean = this.synchronized { !buff.hasRemaining }

      override def apply(): Future[ByteBuffer] = this.synchronized {
        if (buff.hasRemaining) {
          val b = buff
          buff = BufferTools.emptyBuffer
          Future.successful(b)
        }
        else BufferTools.emptyFutureBuffer
      }
    }
  }

  /** The remainder of the message body will be accumulated into a single buffer. If no data remains,
    * the `ByteBuffer` will be empty as defined by `ByteBuffer.hasRemaining()`
    */
  private def accumulate(max: Int, body: BodyReader): Future[ByteBuffer] = {
    require(max >= 0)

    val acc = new ArrayBuffer[ByteBuffer]
    val p = Promise[ByteBuffer]

    def go(bytes: Long): Unit = {
      body().onComplete {
        case Success(buff) if buff.hasRemaining() =>
          val accumulated = bytes + buff.remaining()
          if (accumulated <= max) {
            acc += buff
            go(accumulated)
          }
          else p.tryFailure(new BodyReaderOverflowException(max, accumulated))

        case Success(_) =>
          p.trySuccess(BufferTools.joinBuffers(acc))

        case f@Failure(_) => p.tryComplete(f)
      }(Execution.trampoline)
    }
    go(0)

    p.future
  }
}