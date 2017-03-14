package org.http4s.blaze.http.http2

/** Type that can be polled for the ability to write bytes */
private trait WriteListener {

  /** Possibly add data to the `WriteController`
    *
    * @note this method will be called by the `WriteController` from within
    *       the sessions serial executor.
    */
  // TODO: this could be made less inverted by returning the bytes that need to be written instead of writing directly to the WriteController
  def performStreamWrite(controller: WriteController): Unit
}

