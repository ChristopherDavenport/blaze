package org.http4s.blaze.http.http2

/** Type that can be polled for the ability to write bytes */
private trait WriteListener {

  /** Possibly add data to the `WriteController`
    *
    * @note this method will be called by the `WriteController` from within
    *       the sessions serial executor.
    */
  def performStreamWrite(controller: WriteController): Unit

}
