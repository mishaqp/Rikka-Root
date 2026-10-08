package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable @SerialName("mcp_control") data object McpControl : LocalToolOption()
    @Serializable @SerialName("ssh") data object Ssh : LocalToolOption()
    @Serializable @SerialName("termux") data object Termux : LocalToolOption()
    @Serializable @SerialName("sub_agents") data object SubAgents : LocalToolOption()
    @Serializable @SerialName("cron_jobs") data object CronJobs : LocalToolOption()
    @Serializable @SerialName("reliability") data object Reliability : LocalToolOption()
    @Serializable @SerialName("files") data object Files : LocalToolOption()
    @Serializable @SerialName("external_storage") data object ExternalStorage : LocalToolOption()
    @Serializable @SerialName("archive") data object Archive : LocalToolOption()
    @Serializable @SerialName("media_player") data object MediaPlayer : LocalToolOption()
    @Serializable @SerialName("media_scanner") data object MediaScanner : LocalToolOption()
    @Serializable @SerialName("download") data object Download : LocalToolOption()
    @Serializable @SerialName("system_intents") data object SystemIntents : LocalToolOption()
    @Serializable @SerialName("app_launcher") data object AppLauncher : LocalToolOption()

    @Serializable @SerialName("location") data object Location : LocalToolOption()
    @Serializable @SerialName("contacts") data object Contacts : LocalToolOption()
    @Serializable @SerialName("call_log") data object CallLog : LocalToolOption()
    @Serializable @SerialName("sms_inbox") data object SmsInbox : LocalToolOption()
    @Serializable @SerialName("sms_send") data object SmsSend : LocalToolOption()
    @Serializable @SerialName("camera_photo") data object CameraPhoto : LocalToolOption()
    @Serializable @SerialName("mic_recorder") data object MicRecorder : LocalToolOption()
    @Serializable @SerialName("speech_to_text") data object SpeechToText : LocalToolOption()
    @Serializable @SerialName("fingerprint") data object Fingerprint : LocalToolOption()
    @Serializable @SerialName("keystore") data object Keystore : LocalToolOption()

    @Serializable
    @SerialName("root")
    data object Root : LocalToolOption()

    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("chart_display")
    data object ChartDisplay : LocalToolOption()

    @Serializable @SerialName("battery")
    data object Battery : LocalToolOption()

    @Serializable @SerialName("audio_info")
    data object AudioInfo : LocalToolOption()

    @Serializable @SerialName("telephony_info")
    data object TelephonyInfo : LocalToolOption()

    @Serializable @SerialName("wifi_info")
    data object WifiInfo : LocalToolOption()

    @Serializable @SerialName("sensors")
    data object Sensors : LocalToolOption()

    @Serializable @SerialName("storage_info")
    data object StorageInfo : LocalToolOption()

    @Serializable @SerialName("toast")
    data object Toast : LocalToolOption()

    @Serializable @SerialName("notification")
    data object Notification : LocalToolOption()

    @Serializable @SerialName("share")
    data object Share : LocalToolOption()

    @Serializable @SerialName("torch")
    data object Torch : LocalToolOption()

    @Serializable @SerialName("vibrate")
    data object Vibrate : LocalToolOption()

    @Serializable @SerialName("brightness")
    data object Brightness : LocalToolOption()

    @Serializable @SerialName("volume")
    data object Volume : LocalToolOption()

    @Serializable @SerialName("wallpaper")
    data object Wallpaper : LocalToolOption()

    @Serializable @SerialName("nfc")
    data object Nfc : LocalToolOption()

    @Serializable @SerialName("cost_guards")
    data object CostGuards : LocalToolOption()
}
