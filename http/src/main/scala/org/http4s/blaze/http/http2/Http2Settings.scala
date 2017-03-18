package org.http4s.blaze.http.http2

import scala.collection.mutable

object Http2Settings {

  type SettingValue = Int

  // TODO: We don't need the whole SettingKey here...
  final case class Setting(code: Int, value: SettingValue) {

    /** Get the SettingKey associated with this setting */
    def key: SettingKey = settingKey(code)

    /** Get the human readable name of this setting */
    def name: String = key.name

    override def toString = s"$name(0x${Integer.toHexString(code)}, $value"
  }

  /** Create a new [[Http2Settings]] initialized with the protocol defaults */
  def default(): Http2Settings = new Http2Settings(
    headerTableSize = DefaultSettings.HEADER_TABLE_SIZE,
    initialWindowSize = DefaultSettings.INITIAL_WINDOW_SIZE,
    pushEnabled = DefaultSettings.ENABLE_PUSH, // initially enabled
    maxInboundStreams = DefaultSettings.MAX_CONCURRENT_STREAMS, // initially unbounded
    maxFrameSize = DefaultSettings.MAX_FRAME_SIZE,
    maxHeaderListSize = DefaultSettings.MAX_HEADER_LIST_SIZE
  )

  private def settingKey(id: Int): SettingKey =
    settingsMap.getOrElse(id, SettingKey(id, s"UNKNOWN_SETTING(0x${Integer.toHexString(id)})"))

  private val settingsMap = new mutable.HashMap[Int, SettingKey]()

  private def makeKey(code: Int, name: String): SettingKey = {
    val k = SettingKey(code, name)
    settingsMap += code -> k
    k
  }

  /** Helper for extracting invalid settings
    *
    * @see https://tools.ietf.org/html/rfc7540#section-6.5.2
    */
  object InvalidSetting {
    def unapply(setting: Setting): Option[Http2Exception] = setting match {
      case MAX_FRAME_SIZE(size) if 16384 > size || size > 16777215 =>
        Some(Http2Exception.PROTOCOL_ERROR.goaway(s"Invalid MAX_FRAME_SIZE: $size"))

      case Setting(code, value) if value < 0 =>
        val ex = Http2Exception.PROTOCOL_ERROR.goaway(s"Integer overflow for setting ${setting.name}: ${value}")
        Some(ex)

      case _ => None
    }
  }

  final case class SettingKey(code: Int, name: String) {
    override def toString = name

    /** Create a new `Setting` with the provided value */
    def apply(value: SettingValue): Setting = Setting(code, value)

    /** Extract the value from the key */
    def unapply(setting: Setting): Option[SettingValue] =
      if (setting.code == code) Some(setting.value)
      else None
  }

  val HEADER_TABLE_SIZE      = makeKey(0x1, "SETTINGS_HEADER_TABLE_SIZE")
  val ENABLE_PUSH            = makeKey(0x2, "SETTINGS_ENABLE_PUSH")
  val MAX_CONCURRENT_STREAMS = makeKey(0x3, "SETTINGS_MAX_CONCURRENT_STREAMS")
  val INITIAL_WINDOW_SIZE    = makeKey(0x4, "SETTINGS_INITIAL_WINDOW_SIZE")
  val MAX_FRAME_SIZE         = makeKey(0x5, "SETTINGS_MAX_FRAME_SIZE")
  val MAX_HEADER_LIST_SIZE   = makeKey(0x6, "SETTINGS_MAX_HEADER_LIST_SIZE")

  object DefaultSettings {
    val HEADER_TABLE_SIZE = 4096                                  // Section 6.5.2
    val ENABLE_PUSH = true // 1                                   // Section 6.5.2
    val MAX_CONCURRENT_STREAMS = Integer.MAX_VALUE // (infinite)  // Section 6.5.2
    val INITIAL_WINDOW_SIZE = 65535                               // Section 6.5.2   2^16
    val MAX_FRAME_SIZE = 16384                                    // Section 6.5.2   2^14
    val MAX_HEADER_LIST_SIZE = Integer.MAX_VALUE //(infinite)     // Section 6.5.2
  }
}

/** A bundle of HTTP2 settings */
final class Http2Settings private(
    var headerTableSize: Int,
    var initialWindowSize: Int,
    var pushEnabled: Boolean,
    var maxInboundStreams: Int,
    var maxFrameSize: Int,
    var maxHeaderListSize: Int) { // initially unbounded
  import Http2Settings._

  // Have we received a GOAWAY frame?
  var receivedGoAway = false

  def toSeq: Seq[Setting] = Seq(
    HEADER_TABLE_SIZE(headerTableSize),
    ENABLE_PUSH(if (pushEnabled) 1 else 0),
    MAX_CONCURRENT_STREAMS(maxInboundStreams),
    INITIAL_WINDOW_SIZE(initialWindowSize),
    MAX_FRAME_SIZE(maxFrameSize),
    MAX_HEADER_LIST_SIZE(maxHeaderListSize)
  )

  override def toString: String = {
    s"Http2Settings($toSeq, GOAWAY: $receivedGoAway)"
  }
}