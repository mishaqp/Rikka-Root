package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.localPermissionGrantCommand
import me.rerere.rikkahub.data.ai.tools.local.localToolRuntimePermissions
import me.rerere.rikkahub.data.ai.tools.local.missingLocalToolPermissions
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

private data class PersonalToolSetting(val option: LocalToolOption, val title: Int, val description: Int)
private val personalToolSettings = listOf(
    PersonalToolSetting(LocalToolOption.Location, R.string.personal_tools_location_title, R.string.personal_tools_location_desc),
    PersonalToolSetting(LocalToolOption.Contacts, R.string.personal_tools_contacts_title, R.string.personal_tools_contacts_desc),
    PersonalToolSetting(LocalToolOption.CallLog, R.string.personal_tools_call_log_title, R.string.personal_tools_call_log_desc),
    PersonalToolSetting(LocalToolOption.SmsInbox, R.string.personal_tools_sms_inbox_title, R.string.personal_tools_sms_inbox_desc),
    PersonalToolSetting(LocalToolOption.SmsSend, R.string.personal_tools_sms_send_title, R.string.personal_tools_sms_send_desc),
    PersonalToolSetting(LocalToolOption.CameraPhoto, R.string.personal_tools_camera_title, R.string.personal_tools_camera_desc),
    PersonalToolSetting(LocalToolOption.MicRecorder, R.string.personal_tools_mic_title, R.string.personal_tools_mic_desc),
    PersonalToolSetting(LocalToolOption.SpeechToText, R.string.personal_tools_speech_title, R.string.personal_tools_speech_desc),
    PersonalToolSetting(LocalToolOption.Fingerprint, R.string.personal_tools_fingerprint_title, R.string.personal_tools_fingerprint_desc),
    PersonalToolSetting(LocalToolOption.Keystore, R.string.personal_tools_keystore_title, R.string.personal_tools_keystore_desc),
)

@Composable
internal fun PersonalLocalToolSettings(assistant: Assistant, onUpdate: (Assistant) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val root = koinInject<RootShellManager>()
    val scope = rememberCoroutineScope()
    val latestAssistant by rememberUpdatedState(assistant)
    val latestUpdate by rememberUpdatedState(onUpdate)
    var pendingIndex by rememberSaveable(assistant.id.toString()) { mutableStateOf<Int?>(null) }
    var showRationale by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var deniedIndex by rememberSaveable(assistant.id.toString()) { mutableStateOf<Int?>(null) }
    var rootBusy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val missing = remember(context, revision) { personalToolSettings.map { missingLocalToolPermissions(context, it.option) } }
    val supported = remember(context) { personalToolSettings.map { setting ->
        val pm = context.packageManager
        when (setting.option) {
            LocalToolOption.Location -> pm.hasSystemFeature(PackageManager.FEATURE_LOCATION)
            LocalToolOption.CallLog -> pm.hasSystemFeature(if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_CALLING else PackageManager.FEATURE_TELEPHONY)
            LocalToolOption.SmsInbox, LocalToolOption.SmsSend -> pm.hasSystemFeature(if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY)
            LocalToolOption.CameraPhoto -> pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            LocalToolOption.MicRecorder, LocalToolOption.SpeechToText -> pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            else -> true
        }
    } }
    fun setEnabled(option: LocalToolOption, enabled: Boolean) {
        val current = latestAssistant
        latestUpdate(current.copy(localTools = if (enabled) (current.localTools + option).distinct() else current.localTools - option))
    }
    fun finishRequest() {
        val index = pendingIndex ?: return
        pendingIndex = null; revision++
        val setting = personalToolSettings[index]
        // Approximate location is sufficient. Re-read Android, including partial grants/revocation.
        if (missingLocalToolPermissions(context, setting.option).isEmpty()) {
            deniedIndex = null; setEnabled(setting.option, true)
        } else {
            deniedIndex = index; setEnabled(setting.option, false)
            toaster.show(message = resources.getString(R.string.personal_tools_permission_denied, resources.getString(setting.title)), type = ToastType.Warning)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finishRequest() }
    CardGroup {
        personalToolSettings.forEachIndexed { index, setting ->
            val enabled = setting.option in assistant.localTools
            item(
                headlineContent = { Text(stringResource(setting.title)) },
                supportingContent = {
                    Column {
                        Text(stringResource(setting.description))
                        if (!supported[index]) Text(stringResource(R.string.personal_tools_unavailable))
                        if (missing[index].isNotEmpty() && deniedIndex == index) Text(stringResource(R.string.personal_tools_permission_denied, stringResource(setting.title)))
                        else if (missing[index].isNotEmpty() && enabled) Text(stringResource(R.string.personal_tools_access_revoked))
                        if (supported[index] && missing[index].isNotEmpty()) {
                            TextButton(enabled = !rootBusy && pendingIndex == null, onClick = {
                                // Only an explicit tap issues permission; it never enables the tool.
                                rootBusy = true
                                scope.launch {
                                    try {
                                        val command = localPermissionGrantCommand(context.packageName, setting.option, Build.VERSION.SDK_INT, Process.myUid() / 100_000)
                                        val result = if (command != null && RootCommandGuard.check(command) == null) root.exec(command, 20_000) else null
                                        revision++
                                        if (result?.error == null && result?.exitCode == 0 && missingLocalToolPermissions(context, setting.option).isEmpty()) {
                                            deniedIndex = null
                                            toaster.show(message = resources.getString(R.string.assistant_page_local_tools_root_granted), type = ToastType.Success)
                                        } else toaster.show(message = resources.getString(R.string.assistant_page_local_tools_root_grant_failed), type = ToastType.Warning)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { toaster.show(message = resources.getString(R.string.assistant_page_local_tools_root_grant_failed), type = ToastType.Warning) }
                                    finally { rootBusy = false }
                                }
                            }) { Text(stringResource(R.string.assistant_page_local_tools_grant_root)) }
                        }
                    }
                },
                trailingContent = {
                    Switch(checked = enabled, enabled = !rootBusy && pendingIndex == null && (supported[index] || enabled), onCheckedChange = { checked ->
                        if (!checked) setEnabled(setting.option, false)
                        else if (missingLocalToolPermissions(context, setting.option).isEmpty()) { deniedIndex = null; setEnabled(setting.option, true) }
                        else { pendingIndex = index; showRationale = true }
                    })
                },
            )
        }
    }
    val pending = pendingIndex
    if (showRationale && pending != null) {
        val setting = personalToolSettings[pending]
        AlertDialog(title = { Text(stringResource(R.string.assistant_page_local_tools_permission_title)) },
            text = { Text(stringResource(setting.description) + "\n\n" + stringResource(R.string.assistant_page_local_tools_permission_explanation)) },
            onDismissRequest = { pendingIndex = null; showRationale = false },
            confirmButton = { TextButton(onClick = {
                showRationale = false
                // Location coarse/fine are requested together, only for this feature.
                try { launcher.launch(localToolRuntimePermissions(setting.option, Build.VERSION.SDK_INT).toTypedArray()) }
                catch (_: Exception) { finishRequest() }
            }) { Text(stringResource(R.string.assistant_page_local_tools_permission_request)) } },
            dismissButton = { TextButton(onClick = { pendingIndex = null; showRationale = false }) { Text(stringResource(R.string.assistant_page_local_tools_permission_cancel)) } },
        )
    }
}
