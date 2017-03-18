package org.http4s.blaze.http.http2.client

import org.http4s.blaze.http.{HttpClient, HttpClientConfig, HttpClientImpl}
import org.http4s.blaze.http.http2.Http2Settings


object Http2Client {

  lazy val defaultH2Client: HttpClient = newH2Client()

  private[blaze] def newH2Client(): HttpClient = {
    val h2Settings = Http2Settings.default()
    h2Settings.initialWindowSize = 256*1024
    val manager = new Http2ClientSessionManagerImpl(HttpClientConfig.Default, h2Settings)
    new HttpClientImpl(manager)
  }

}
