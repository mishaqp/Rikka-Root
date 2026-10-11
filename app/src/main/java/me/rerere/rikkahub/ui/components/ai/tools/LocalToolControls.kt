package me.rerere.rikkahub.ui.components.ai.tools

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.ai.tools.local.localToolRuntimePermissions
import me.rerere.rikkahub.data.ai.tools.local.missingLocalToolPermissions
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.service.scheduling.ExactAlarmAccess
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.compose.koinInject

internal data class LocalToolPermissionRequest(
    val ownerId: String,
    val generation: Long,
    val option: String,
    val enableAfterGrant: Boolean,
)

internal data class LocalToolPermissionResult(
    val queue: LocalToolPermissionQueue,
    val enabled: Set<String>,
)

/** UI-only Android request reducer. Option keys never enter the Assistant model. */
internal data class LocalToolPermissionQueue(
    val ownerId: String,
    val generation: Long = 0,
    val desired: Set<String> = emptySet(),
    val pending: List<Pair<String, Boolean>> = emptyList(),
    val active: LocalToolPermissionRequest? = null,
) {
    val busy: Boolean get() = active != null || pending.isNotEmpty()

    fun replace(options: List<String>, currentEnabled: Set<String>): LocalToolPermissionQueue = copy(
        generation = generation + 1,
        desired = options.toSet(),
        pending = options.distinct().filterNot { it in currentEnabled }.map { it to true },
    )

    fun requestPermission(option: String): LocalToolPermissionQueue {
        if (active?.option == option || pending.any { it.first == option }) return this
        return copy(pending = pending + (option to false))
    }

    fun startNext(): LocalToolPermissionQueue {
        if (active != null || pending.isEmpty()) return this
        val next = pending.first()
        return copy(pending = pending.drop(1), active = LocalToolPermissionRequest(ownerId, generation, next.first, next.second))
    }

    fun finish(request: LocalToolPermissionRequest, granted: Boolean, currentEnabled: Set<String>): LocalToolPermissionResult {
        if (request != active || request.ownerId != ownerId) return LocalToolPermissionResult(this, currentEnabled)
        val stillRequested = request.generation == generation &&
            (!request.enableAfterGrant || request.option in desired)
        val enabled = if (stillRequested && granted && request.enableAfterGrant) currentEnabled + request.option else currentEnabled
        val updatedDesired = if (stillRequested && !granted && request.enableAfterGrant) desired - request.option else desired
        return LocalToolPermissionResult(copy(active = null, desired = updatedDesired), enabled)
    }
}

private val permissionQueueSaver = listSaver<LocalToolPermissionQueue, Any>(
    save = { queue ->
        listOf(queue.ownerId, queue.generation, ArrayList(queue.desired),
            ArrayList(queue.pending.map { it.first }), ArrayList(queue.pending.map { it.second }),
            queue.active?.ownerId.orEmpty(), queue.active?.generation ?: -1L,
            queue.active?.option.orEmpty(), queue.active?.enableAfterGrant ?: false)
    },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val desired = (saved[2] as List<String>).toSet()
        @Suppress("UNCHECKED_CAST")
        val options = saved[3] as List<String>
        @Suppress("UNCHECKED_CAST")
        val enables = saved[4] as List<Boolean>
        LocalToolPermissionQueue(saved[0] as String, saved[1] as Long, desired,
            options.zip(enables), (saved[5] as String).takeIf { it.isNotEmpty() }?.let {
                LocalToolPermissionRequest(it, saved[6] as Long, saved[7] as String, saved[8] as Boolean)
            })
    },
)

private fun LocalToolPermissionRequest.token(): String = "$ownerId|$generation|$option|$enableAfterGrant"
private fun String.request(): LocalToolPermissionRequest? {
    val parts = split('|')
    if (parts.size != 4) return null
    return LocalToolPermissionRequest(parts[0], parts[1].toLongOrNull() ?: return null, parts[2], parts[3].toBoolean())
}

/** Central UI actions; neither Android grants nor presets change tool approval policy. */
class LocalToolControls internal constructor(
    private val isBusy: () -> Boolean,
    private val enable: (LocalToolOption, Boolean) -> Unit,
    private val apply: (Set<LocalToolOption>) -> Unit,
    private val permission: (LocalToolOption) -> Unit,
) {
    val busy: Boolean get() = isBusy()
    fun setEnabled(option: LocalToolOption, enabled: Boolean) = enable(option, enabled)
    fun applyOptions(desired: Set<LocalToolOption>) = apply(desired)
    fun requestPermission(option: LocalToolOption) = permission(option)
}

/** Composed on the main page so Android callbacks survive opening or closing detail sheets. */
@Composable
fun rememberLocalToolControls(assistant: Assistant, onUpdate: (Assistant) -> Unit): LocalToolControls {
    val context = LocalContext.current
    val resources = context.resources
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    // Loads the persisted smoke-test timestamp before an enable action.
    koinInject<TermuxPreferences>()
    val ownerId = assistant.id.toString()
    val latestAssistant by rememberUpdatedState(assistant)
    val latestUpdate by rememberUpdatedState(onUpdate)
    val entries = LocalToolUiRegistry.entries
    val optionByKey = remember { entries.associate { it.option::class.simpleName!! to it.option } }
    fun key(option: LocalToolOption): String = option::class.simpleName!!

    var queue by rememberSaveable(ownerId, stateSaver = permissionQueueSaver) {
        mutableStateOf(LocalToolPermissionQueue(ownerId))
    }
    var phase by rememberSaveable(ownerId) { mutableStateOf(0) } // 0: queued, 1: rationale, 2: Android
    // Kept across assistant changes: a late ActivityResult belongs to the request actually launched.
    var runtimeToken by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsToken by rememberSaveable { mutableStateOf<String?>(null) }
    var optimisticTools by remember(ownerId) { mutableStateOf<List<LocalToolOption>?>(null) }
    var showTermuxSetup by rememberSaveable(ownerId) { mutableStateOf(false) }
    var termuxSetupShown by rememberSaveable(ownerId) { mutableStateOf(false) }
    var showWorkflowsHint by rememberSaveable(ownerId) { mutableStateOf(false) }
    var workflowsHintShown by rememberSaveable(ownerId) { mutableStateOf(false) }
    var verifyingTermux by remember(ownerId) { mutableStateOf(false) }

    SideEffect {
        if (optimisticTools == assistant.localTools) optimisticTools = null
    }
    fun currentTools(): List<LocalToolOption> = optimisticTools ?: latestAssistant.localTools
    fun currentKeys(): Set<String> = currentTools().mapTo(linkedSetOf(), ::key)
    fun commit(tools: List<LocalToolOption>) {
        if (latestAssistant.id.toString() != ownerId) return
        val distinct = tools.distinct()
        if (distinct == currentTools()) return
        optimisticTools = distinct
        latestUpdate(latestAssistant.copy(localTools = distinct))
    }
    fun afterEnable(option: LocalToolOption) {
        when (option) {
            LocalToolOption.Termux -> {
                val verifiedAt = TermuxIntegration.lastVerifiedOkAtMs
                val recent = verifiedAt > 0 && System.currentTimeMillis() - verifiedAt in 0 until 24L * 60 * 60 * 1000
                if (!recent && !termuxSetupShown) { termuxSetupShown = true; showTermuxSetup = true }
            }
            LocalToolOption.Workflows -> if (!workflowsHintShown) {
                workflowsHintShown = true; showWorkflowsHint = true
            }
            else -> Unit
        }
    }
    fun finish(request: LocalToolPermissionRequest, granted: Boolean, explainDenial: Boolean = true) {
        val previous = queue
        val valid = previous.active == request && request.ownerId == latestAssistant.id.toString() &&
            request.generation == previous.generation && (!request.enableAfterGrant || request.option in previous.desired)
        val result = previous.finish(request, granted, currentKeys())
        queue = result.queue
        if (previous.active == request) phase = 0
        if (!valid) return
        val option = optionByKey[request.option] ?: return
        if (result.enabled != currentKeys()) {
            commit(currentTools().filter { key(it) in result.enabled } +
                entries.map { it.option }.filter { key(it) in result.enabled && it !in currentTools() })
        }
        if (granted && request.enableAfterGrant) afterEnable(option)
        if (!granted && explainDenial) {
            val message = if (option == LocalToolOption.Volume) resources.getString(R.string.hardware_tools_dnd_denied)
                else resources.getString(R.string.assistant_page_local_tools_permission_denied, LocalToolUiRegistry.entry(option).title(context))
            toaster.show(message, type = ToastType.Warning)
        }
    }
    fun permissions(option: LocalToolOption): List<String> = if (option == LocalToolOption.Calendar)
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        else localToolRuntimePermissions(option, Build.VERSION.SDK_INT)
    fun hasAccess(option: LocalToolOption): Boolean = when (option) {
        LocalToolOption.Calendar -> permissions(option).all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        LocalToolOption.Brightness -> Settings.System.canWrite(context)
        LocalToolOption.Volume -> context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        LocalToolOption.ScreenTime -> context.hasUsageStatsPermission()
        LocalToolOption.CronJobs, LocalToolOption.Workflows -> ExactAlarmAccess.canSchedule(context)
        else -> missingLocalToolPermissions(context, option).isEmpty()
    }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = runtimeToken?.request()
        runtimeToken = null
        if (request != null) finish(request, optionByKey[request.option]?.let(::hasAccess) == true)
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val request = settingsToken?.request()
        settingsToken = null
        if (request != null) {
            val option = optionByKey[request.option]
            val allowed = option?.let(::hasAccess) == true
            if (option in setOf(LocalToolOption.CronJobs, LocalToolOption.Workflows) && !allowed &&
                request.ownerId == latestAssistant.id.toString() && request.generation == queue.generation) {
                toaster.show(resources.getString(R.string.schedule_execution_exact_denied), type = ToastType.Warning)
            }
            val fallbackEnabled = option in setOf(LocalToolOption.ScreenTime, LocalToolOption.CronJobs, LocalToolOption.Workflows)
            finish(request, allowed || fallbackEnabled, explainDenial = !fallbackEnabled)
        }
    }
    fun launchSettings(request: LocalToolPermissionRequest, option: LocalToolOption) {
        val action = when (option) {
            LocalToolOption.Brightness -> Settings.ACTION_MANAGE_WRITE_SETTINGS
            LocalToolOption.Volume -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            LocalToolOption.ScreenTime -> Settings.ACTION_USAGE_ACCESS_SETTINGS
            else -> Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
        }
        val packageUri = option == LocalToolOption.Brightness || option in setOf(LocalToolOption.CronJobs, LocalToolOption.Workflows)
        settingsToken = request.token()
        phase = 2
        try {
            settingsLauncher.launch(if (packageUri) Intent(action, Uri.parse("package:${context.packageName}")) else Intent(action))
        } catch (_: ActivityNotFoundException) {
            try { settingsLauncher.launch(Intent(action)) }
            catch (_: Exception) {
                settingsToken = null
                val fallback = option in setOf(LocalToolOption.ScreenTime, LocalToolOption.CronJobs, LocalToolOption.Workflows)
                if (option in setOf(LocalToolOption.CronJobs, LocalToolOption.Workflows)) toaster.show(resources.getString(R.string.schedule_execution_exact_settings_unavailable), type = ToastType.Warning)
                finish(request, fallback)
            }
        } catch (_: Exception) {
            settingsToken = null
            finish(request, option in setOf(LocalToolOption.ScreenTime, LocalToolOption.CronJobs, LocalToolOption.Workflows))
        }
    }
    fun launchAccess(request: LocalToolPermissionRequest, option: LocalToolOption) {
        if (option == LocalToolOption.Brightness || option == LocalToolOption.Volume) {
            launchSettings(request, option)
        } else {
            runtimeToken = request.token()
            phase = 2
            try { runtimeLauncher.launch(permissions(option).toTypedArray()) }
            catch (_: Exception) { runtimeToken = null; finish(request, false) }
        }
    }

    LaunchedEffect(queue, phase, runtimeToken, settingsToken, showTermuxSetup, showWorkflowsHint) {
        if (latestAssistant.id.toString() != ownerId || runtimeToken != null || settingsToken != null || showTermuxSetup || showWorkflowsHint) return@LaunchedEffect
        if (queue.active == null) { queue = queue.startNext(); return@LaunchedEffect }
        val request = queue.active ?: return@LaunchedEffect
        if (request.generation != queue.generation) { finish(request, false, false); return@LaunchedEffect }
        if (phase != 0) return@LaunchedEffect
        val option = optionByKey[request.option] ?: run { finish(request, false, false); return@LaunchedEffect }
        if (!localToolSupported(context, option)) {
            toaster.show(resources.getString(R.string.hardware_tools_unavailable), type = ToastType.Warning)
            finish(request, false, false)
        } else if (option == LocalToolOption.Termux && TermuxIntegration.state(context) == TermuxIntegration.State.NOT_INSTALLED) {
            toaster.show(resources.getString(R.string.setting_termux_verify_not_installed), type = ToastType.Warning)
            finish(request, false, false)
        } else if (hasAccess(option)) {
            finish(request, true)
        } else if (option in setOf(LocalToolOption.ScreenTime, LocalToolOption.CronJobs, LocalToolOption.Workflows)) {
            if (request.enableAfterGrant) commit(currentTools() + option)
            toaster.show(resources.getString(if (option == LocalToolOption.ScreenTime)
                R.string.assistant_page_local_tools_screen_time_permission_required else R.string.schedule_execution_exact_rationale), type = ToastType.Warning)
            launchSettings(request, option)
        } else {
            phase = 1
        }
    }

    val active = queue.active
    if (phase == 1 && active != null && active.generation == queue.generation) {
        val option = optionByKey[active.option]
        if (option != null) {
            val explanation = when (option) {
                LocalToolOption.Termux -> resources.getString(R.string.shell_tools_termux_permission_rationale)
                LocalToolOption.Ssh -> resources.getString(R.string.shell_tools_ssh_permission_rationale)
                LocalToolOption.Download -> resources.getString(R.string.file_tools_legacy_download_permission)
                LocalToolOption.Calendar -> resources.getString(R.string.permission_calendar_read_desc) + "\n\n" + resources.getString(R.string.permission_calendar_write_desc)
                LocalToolOption.Volume -> LocalToolUiRegistry.entry(option).description(context) + "\n\n" + resources.getString(R.string.hardware_tools_dnd_denied)
                else -> LocalToolUiRegistry.entry(option).description(context) + "\n\n" + resources.getString(R.string.assistant_page_local_tools_permission_explanation)
            }
            AlertDialog(
                onDismissRequest = { finish(active, false, false) },
                title = { Text(stringResource(R.string.assistant_page_local_tools_permission_title)) },
                text = { Text(explanation) },
                confirmButton = { TextButton(onClick = { launchAccess(active, option) }) {
                    Text(stringResource(if (option == LocalToolOption.Brightness || option == LocalToolOption.Volume)
                        R.string.hardware_tools_open_settings else R.string.assistant_page_local_tools_permission_request))
                } },
                dismissButton = {
                    Column {
                        if (option == LocalToolOption.Volume && active.enableAfterGrant) {
                            TextButton(onClick = { finish(active, true) }) { Text(stringResource(R.string.hardware_tools_without_dnd)) }
                        }
                        TextButton(onClick = { finish(active, false, false) }) { Text(stringResource(R.string.assistant_page_local_tools_permission_cancel)) }
                    }
                },
            )
        }
    }

    if (showTermuxSetup) {
        val command = stringResource(R.string.assistant_page_local_tools_termux_postgrant_command)
        AlertDialog(
            onDismissRequest = { showTermuxSetup = false },
            title = { Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_message))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(command, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(8.dp))
                    }
                    TextButton(enabled = !verifyingTermux, onClick = {
                        verifyingTermux = true
                        scope.launch {
                            try {
                                val result = TermuxIntegration.verify(context)
                                val message = when (result) {
                                    TermuxIntegration.VerifyResult.Ok -> { TermuxIntegration.markVerifiedOk(); resources.getString(R.string.assistant_page_local_tools_termux_verify_ok) }
                                    TermuxIntegration.VerifyResult.AllowExternalAppsMissing -> { TermuxIntegration.clearVerified(); resources.getString(R.string.assistant_page_local_tools_termux_verify_props_missing) }
                                    TermuxIntegration.VerifyResult.NoPermission -> { TermuxIntegration.clearVerified(); resources.getString(R.string.assistant_page_local_tools_termux_verify_no_permission) }
                                    TermuxIntegration.VerifyResult.NotInstalled -> { TermuxIntegration.clearVerified(); resources.getString(R.string.assistant_page_local_tools_termux_status_not_installed) }
                                    is TermuxIntegration.VerifyResult.UnexpectedOutput -> { TermuxIntegration.clearVerified(); resources.getString(R.string.assistant_page_local_tools_termux_verify_unexpected, result.stdout.take(60)) }
                                    is TermuxIntegration.VerifyResult.OtherError -> result.message
                                }
                                toaster.show(message, type = if (result == TermuxIntegration.VerifyResult.Ok) ToastType.Success else ToastType.Warning)
                            } catch (canceled: CancellationException) { throw canceled }
                            finally { verifyingTermux = false }
                        }
                    }) { Text(stringResource(if (verifyingTermux) R.string.assistant_page_local_tools_termux_verifying else R.string.assistant_page_local_tools_termux_verify)) }
                }
            },
            confirmButton = { TextButton(onClick = {
                context.writeClipboardText(command)
                toaster.show(resources.getString(R.string.assistant_page_local_tools_termux_postgrant_copied))
                showTermuxSetup = false
            }) { Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_copy)) } },
            dismissButton = { TextButton(onClick = { showTermuxSetup = false }) { Text(stringResource(R.string.shell_tools_dismiss)) } },
        )
    }
    if (showWorkflowsHint) AlertDialog(
        onDismissRequest = { showWorkflowsHint = false },
        title = { Text(stringResource(R.string.assistant_page_local_tools_workflows_hint_title)) },
        text = { Text(stringResource(R.string.assistant_page_local_tools_workflows_hint_message)) },
        confirmButton = { TextButton(onClick = { showWorkflowsHint = false }) { Text(stringResource(R.string.large_tools_dismiss)) } },
    )

    fun apply(desired: Set<LocalToolOption>) {
        val ordered = entries.map { it.option }.filter { it in desired }
        queue = queue.replace(ordered.map(::key), currentKeys())
        // Disable removed options immediately; Android results merge successful enables later.
        commit(currentTools().filter { it in desired })
        if (LocalToolOption.Termux !in desired) showTermuxSetup = false
        if (LocalToolOption.Workflows !in desired) showWorkflowsHint = false
    }
    return LocalToolControls(
        isBusy = { queue.busy || runtimeToken != null || settingsToken != null || showTermuxSetup || showWorkflowsHint },
        enable = { option, enabled ->
            val waiting = queue.pending.filter { it.second }.map { it.first } +
                listOfNotNull(queue.active?.takeIf { it.enableAfterGrant && it.generation == queue.generation }?.option)
            val desired = currentTools().toSet() + waiting.mapNotNull { optionByKey[it] }
            apply(if (enabled) desired + option else desired - option)
        },
        apply = ::apply,
        permission = { option -> queue = queue.requestPermission(key(option)) },
    )
}

internal fun localToolSupported(context: Context, option: LocalToolOption): Boolean {
    val pm = context.packageManager
    return when (option) {
        LocalToolOption.Torch -> pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        LocalToolOption.Nfc -> pm.hasSystemFeature(PackageManager.FEATURE_NFC)
        LocalToolOption.Location -> pm.hasSystemFeature(PackageManager.FEATURE_LOCATION)
        LocalToolOption.CallLog -> pm.hasSystemFeature(if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_CALLING else PackageManager.FEATURE_TELEPHONY)
        LocalToolOption.SmsInbox, LocalToolOption.SmsSend -> pm.hasSystemFeature(if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY)
        LocalToolOption.CameraPhoto -> pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        LocalToolOption.MicRecorder, LocalToolOption.SpeechToText -> pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        else -> true
    }
}
