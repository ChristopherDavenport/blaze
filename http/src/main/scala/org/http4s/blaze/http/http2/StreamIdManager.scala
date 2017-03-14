package org.http4s.blaze.http.http2

private object StreamIdManager {

  /** Create a new [[StreamIdManager]] */
  def apply(isClient: Boolean): StreamIdManager
    = create(isClient, 0)

  // Exposed internally for testing overflow behavior
  private[http2] def create(isClient: Boolean, startingId: Int): StreamIdManager = {
    require(startingId % 2 == 0)

    val nextInbound = startingId + (if (isClient) 2 else 1)
    val nextOutbound = startingId + (if (isClient) 1 else 2)
    new StreamIdManager(isClient, nextInbound, nextOutbound)
  }
}

/** Tool for tracking stream ids */
private final class StreamIdManager private(
    isClient: Boolean,
    private var nextInbound: Int,
    private var nextOutbound: Int) {

  /** Get the last inbound stream to be observed, or 0 if no streams have been processed */
  def lastInboundStream: Int = math.max(0, nextInbound - 2)

  /** Determine if the stream id is an inbound stream id */
  def isInboundId(id: Int): Boolean = {
    require(id >= 0)
    // For the client, inbound streams will be even. All non-session stream ids are > 0
    id > 0 && (id % 2 == 0) == isClient
  }

  /** Determine if the stream id is an outbound stream id */
  def isOutboundId(id: Int): Boolean =
    !isInboundId(id)

  /** Determine if the stream id is both an inbound id and is idle */
  def isIdleInboundId(id: Int): Boolean = {
    isInboundId(id) && id >= nextInbound
  }

  /** Determine if the stream id is both an outbound id and is idle */
  def isIdleOutboundId(id: Int): Boolean = {
    isOutboundId(id) &&
      id >= nextOutbound &&
      nextOutbound > 0 // make sure we didn't overflow
  }

  /** Determine if the client ID is valid based on the stream history */
  def validateNewInboundId(id: Int): Boolean = {
    if (isInboundId(id) && id >= nextInbound) {
      nextInbound = id + 2
      true
    }
    else false
  }

  /** Mark the stream id non-idle, and any idle inbound streams with lower ids
    *
    * If the stream id is an inbound stream id and is idle then the specified it
    * and all inbound id's preceding it are marked as non-idle.
    *
    * @param id stream id to observe
    * @return `true` if observed, `false` otherwise.
    */
  def observeInboundId(id: Int): Boolean = {
    if (!isIdleInboundId(id)) false
    else {
      nextInbound = id + 2
      true
    }
  }

  /** Acquire the next outbound stream id
    *
    * @return the next streamId wrapped in `Some` if it exists, `None` otherwise.
    */
  def takeOutboundId(): Option[Int] = {
    // Returns `None` if the stream id overflows, which is when a signed Int overflows
    if (unusedOutboundStreams) {
      val result = Some(nextOutbound)
      nextOutbound += 2
      result
    } else None
  }

  def unusedOutboundStreams: Boolean = nextOutbound > 0
}