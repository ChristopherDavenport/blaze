package org.http4s.blaze.http.http2;

trait StreamFlowWindow {

  /** The flow control manager of the session this stream belongs to */
  def session: SessionFlowControl

  /** Id of the associated stream */
  def streamId: Int

  /** Get the number of stream inbound bytes that haven't been consumed */
  def unconsumedBytes: Int

  /** Get the remaining bytes in the stream outbound window */
  def outboundWindow: Int

  /** Signal that a stream window update was received for `count` bytes */
  def outboundAcked(count: Int): MaybeError

  /** Request to withdraw bytes from the outbound window of the stream
    * and the session.
    *
    * @param request maximum bytes to withdraw
    * @return actual bytes withdrawn from the window
    */
  def outboundReceived(request: Int): Int

  /** Get the remaining bytes in the streams inbound window */
  def inboundWindow: Int

  /** Attempts to withdraw `count` bytes from the inbound window of both the stream and the session.
    *
    * If there are sufficient bytes in the stream and session flow windows, they are subtracted,
    * otherwise the window is unmodified.
    *
    * @return `true` if withdraw was successful, `false` otherwise.
    */
  def inboundObserved(count: Int): Boolean

  /** Signal that `count` bytes have been consumed by the stream
    *
    * @note The consumed bytes are also counted for the session flow window.
    */
  def inboundConsumed(count: Int): Unit

  /** Signal that a stream window update was sent for `count` bytes */
  def inboundAcked(count: Int): Unit
}
