package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.tts.provider.TTSManager
import java.io.File

/** The file-backed tool dispatcher shares one scoped resolver for the selected workspace. */
internal fun scopedFileTools(
    context: Context,
    options: List<LocalToolOption>,
    workspaceCwd: String? = null,
    chatImages: List<String> = emptyList(),
    resolveWorkspacePath: (suspend (String) -> File)? = null,
    modelCanReadImages: Boolean = false,
): List<Tool> = buildList {
    if (options.any { it in listOf(LocalToolOption.Files, LocalToolOption.Archive,
            LocalToolOption.MediaPlayer, LocalToolOption.MediaScanner, LocalToolOption.Download) }) {
        val access = LocalFileAccess(context, workspaceCwd, chatImages, resolveWorkspacePath)
        if (LocalToolOption.Files in options) addAll(fileManagerTools(access, context, modelCanReadImages))
        if (LocalToolOption.Archive in options) addAll(listOf(zipFilesTool(context, access), unzipFileTool(context, access), listZipContentsTool(context, access)))
        if (LocalToolOption.MediaPlayer in options) addAll(listOf(playMediaTool(context, access), stopMediaTool(context), pauseMediaTool(context), resumeMediaTool(context, access), seekMediaTool(context), getMediaStatusTool()))
        if (LocalToolOption.MediaScanner in options) add(mediaScannerTool(context, access))
        if (LocalToolOption.Download in options) addAll(listOf(downloadTool(context, access), writeTextFileTool(access)))
    }
}

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val rootShellManager: RootShellManager,
    private val rootAccessStore: RootAccessStore,
    private val filesManager: FilesManager,
) {
    private val keystoreCrypto by lazy { KeystoreCrypto(AndroidToolKeyStore(context)) }
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val chartDisplayTool by lazy { buildChartDisplayTool() }

    fun getTools(
        options: List<LocalToolOption>, assistantId: String? = null, conversationId: String? = null,
        workspaceCwd: String? = null, chatImages: List<String> = emptyList(),
        resolveWorkspacePath: (suspend (String) -> File)? = null,
        modelCanReadImages: Boolean = false,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        tools.addAll(scopedFileTools(context, options, workspaceCwd, chatImages, resolveWorkspacePath, modelCanReadImages))
        if (LocalToolOption.ExternalStorage in options) tools.addAll(listOf(listStorageVolumesTool(context), listGrantedDirectoriesTool(context), grantDirectoryAccessTool(context)))
        if (LocalToolOption.SystemIntents in options) tools.addAll(systemIntentTools(context))
        if (LocalToolOption.AppLauncher in options) tools.addAll(appLauncherTools(context))
        if (LocalToolOption.Location in options) tools.add(locationTool(context))
        if (LocalToolOption.Contacts in options) { tools.add(listContactsTool(context)); tools.add(searchContactsTool(context)) }
        if (LocalToolOption.CallLog in options) tools.add(callLogTool(context))
        if (LocalToolOption.SmsInbox in options) { tools.add(listSmsInboxTool(context)); tools.add(searchSmsTool(context)) }
        if (LocalToolOption.SmsSend in options) tools.add(smsSendTool(context))
        if (LocalToolOption.CameraPhoto in options) tools.add(cameraPhotoTool(context, filesManager))
        if (LocalToolOption.MicRecorder in options) tools.add(micRecorderTool(context, filesManager))
        if (LocalToolOption.SpeechToText in options) tools.add(speechToTextTool(context))
        if (LocalToolOption.Fingerprint in options) tools.add(fingerprintTool(context))
        if (LocalToolOption.Keystore in options) tools.addAll(keystoreTools(context, keystoreCrypto))
        if (LocalToolOption.Battery in options) tools.add(batteryTool(context))
        if (LocalToolOption.AudioInfo in options) tools.add(audioInfoTool(context))
        if (LocalToolOption.TelephonyInfo in options) tools.add(telephonyInfoTool(context))
        if (LocalToolOption.WifiInfo in options) tools.add(wifiInfoTool(context))
        if (LocalToolOption.Sensors in options) {
            tools.add(listSensorsTool(context))
            tools.add(readSensorTool(context))
        }
        if (LocalToolOption.StorageInfo in options) tools.add(storageTool())
        if (LocalToolOption.Toast in options) tools.add(toastTool(context))
        if (LocalToolOption.Notification in options) tools.add(notificationTool(context, conversationId))
        if (LocalToolOption.Share in options) tools.add(shareTool(context))
        if (LocalToolOption.Torch in options) tools.add(torchTool(context))
        if (LocalToolOption.Vibrate in options) tools.add(vibrateTool(context))
        if (LocalToolOption.Brightness in options) {
            tools.add(getBrightnessTool(context))
            tools.add(setBrightnessTool(context))
        }
        if (LocalToolOption.Volume in options) {
            tools.add(getVolumeTool(context))
            tools.add(setVolumeTool(context))
        }
        if (LocalToolOption.Wallpaper in options) tools.add(setWallpaperTool(context,
            LocalImageSources(File(context.filesDir, "upload"), workspaceCwd, chatImages, resolveWorkspacePath)))
        if (LocalToolOption.Nfc in options) {
            tools.add(nfcStatusTool(context))
            tools.add(nfcReadTagTool(context))
            tools.add(nfcWriteTagTool(context))
        }
        if (options.contains(LocalToolOption.Root)) {
            tools.add(buildRootTool(rootShellManager, rootAccessStore, assistantId))
        }
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.ChartDisplay)) {
            tools.add(chartDisplayTool)
        }
        return tools
    }
}
