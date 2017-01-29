package org.http4s.blaze.http

import org.http4s.blaze.http.http1x.BasicHttp1SessionManager


package object client {

  /** Basic implementation of a HTTP/1.1 client.
    *
    * This client doesn't do any session pooling, so one request = one socket connection.
    */
  lazy val basicHttp1Client: HttpClient = {
    val pool = new BasicHttp1SessionManager(HttpClientConfig.Default)
    new HttpClientImpl(pool)
  }

}
