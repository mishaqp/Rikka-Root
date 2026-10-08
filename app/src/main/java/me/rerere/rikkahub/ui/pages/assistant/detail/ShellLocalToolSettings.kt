package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.ai.tools.local.localToolRuntimePermissions
import me.rerere.rikkahub.data.ai.tools.local.missingLocalToolPermissions
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.compose.koinInject

private data class ShellToolSetting(val option: LocalToolOption, val title: Int, val description: Int)
private val shellToolSettings = listOf(
    ShellToolSetting(LocalToolOption.Termux, R.string.assistant_page_local_tools_termux_title, R.string.shell_tools_termux_desc),
    ShellToolSetting(LocalToolOption.Ssh, R.string.assistant_page_local_tools_ssh_title, R.string.assistant_page_local_tools_ssh_desc),
    ShellToolSetting(LocalToolOption.McpControl, R.string.assistant_page_local_tools_mcp_control_title, R.string.assistant_page_local_tools_mcp_control_desc),
    ShellToolSetting(LocalToolOption.ExternalAutomation, R.string.assistant_page_local_tools_external_automation_title, R.string.assistant_page_local_tools_external_automation_desc),
)

/** Agent AssistantLocalToolPage shell/network rows adapted to the fork's approval and permission UI. */
@Composable
internal fun ShellLocalToolSettings(assistant: Assistant, onUpdate: (Assistant) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val nav = LocalNavController.current
    // Restore persisted integration state even before any tool or settings page was opened.
    koinInject<TermuxPreferences>()
    val latestAssistant by rememberUpdatedState(assistant)
    val latestUpdate by rememberUpdatedState(onUpdate)
    var pendingIndex by rememberSaveable(assistant.id.toString()) { mutableStateOf<Int?>(null) }
    var deniedIndex by rememberSaveable(assistant.id.toString()) { mutableStateOf<Int?>(null) }
    var showRationale by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var showTermuxPostGrantDialog by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var termuxDialogShownThisVisit by remember(assistant.id) { mutableStateOf(false) }

    fun setEnabled(option: LocalToolOption, enabled: Boolean) {
        val current = latestAssistant
        latestUpdate(current.copy(localTools = if (enabled) (current.localTools + option).distinct()
            else current.localTools - option))
        if (enabled && option == LocalToolOption.Termux && !termuxDialogShownThisVisit) {
            val recentlyVerified = TermuxIntegration.lastVerifiedOkAtMs > 0 &&
                (System.currentTimeMillis() - TermuxIntegration.lastVerifiedOkAtMs) < 24L * 60 * 60 * 1000
            if (!recentlyVerified) {
                termuxDialogShownThisVisit = true
                showTermuxPostGrantDialog = true
            }
        }
    }

    fun finishPermissionRequest() {
        val index = pendingIndex ?: return
        pendingIndex = null
        val setting = shellToolSettings[index]
        if (missingLocalToolPermissions(context, setting.option).isEmpty()) {
            deniedIndex = null
            setEnabled(setting.option, true)
        } else {
            deniedIndex = index
            setEnabled(setting.option, false)
            toaster.show(resources.getString(R.string.shell_tools_permission_denied,
                resources.getString(setting.title)), type = ToastType.Warning)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { finishPermissionRequest() }

    CardGroup(title = { Text(stringResource(R.string.shell_tools_title)) }) {
        shellToolSettings.forEachIndexed { index, setting ->
            val enabled = setting.option in assistant.localTools
            item(
                headlineContent = { Text(stringResource(setting.title)) },
                supportingContent = {
                    Column {
                        Text(stringResource(setting.description))
                        if (setting.option == LocalToolOption.Termux) {
                            TermuxStatusRowSubtitle(enabled)
                            TextButton(onClick = { nav.navigate(Screen.SettingTermux) }) {
                                Text(stringResource(R.string.shell_tools_open_termux_settings))
                            }
                        }
                        if (setting.option == LocalToolOption.Ssh) {
                            TextButton(onClick = { nav.navigate(Screen.SettingSsh) }) {
                                Text(stringResource(R.string.shell_tools_open_ssh_settings))
                            }
                        }
                        if (deniedIndex == index) {
                            Text(stringResource(R.string.shell_tools_permission_denied, stringResource(setting.title)))
                        }
                    }
                },
                trailingContent = {
                    Switch(checked = enabled, enabled = pendingIndex == null,
                        onCheckedChange = { checked ->
                            if (!checked) setEnabled(setting.option, false)
                            else if (setting.option == LocalToolOption.Termux &&
                                TermuxIntegration.state(context) == TermuxIntegration.State.NOT_INSTALLED) {
                                deniedIndex = index
                                toaster.show(resources.getString(R.string.setting_termux_verify_not_installed),
                                    type = ToastType.Warning)
                            } else if (missingLocalToolPermissions(context, setting.option).isEmpty()) {
                                deniedIndex = null
                                setEnabled(setting.option, true)
                            } else {
                                pendingIndex = index
                                showRationale = true
                            }
                        })
                },
            )
        }
    }

    val pending = pendingIndex
    if (showRationale && pending != null) {
        val setting = shellToolSettings[pending]
        AlertDialog(
            title = { Text(stringResource(R.string.assistant_page_local_tools_permission_title)) },
            text = { Text(stringResource(if (setting.option == LocalToolOption.Termux)
                R.string.shell_tools_termux_permission_rationale else R.string.shell_tools_ssh_permission_rationale)) },
            onDismissRequest = { showRationale = false; pendingIndex = null },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    try {
                        permissionLauncher.launch(localToolRuntimePermissions(setting.option,
                            android.os.Build.VERSION.SDK_INT).toTypedArray())
                    } catch (_: Exception) { finishPermissionRequest() }
                }) { Text(stringResource(R.string.assistant_page_local_tools_permission_request)) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false; pendingIndex = null }) {
                    Text(stringResource(R.string.assistant_page_local_tools_permission_cancel))
                }
            },
        )
    }

    if (showTermuxPostGrantDialog) {
        val termuxCommand = stringResource(R.string.assistant_page_local_tools_termux_postgrant_command)
        val termuxCopiedText = stringResource(R.string.assistant_page_local_tools_termux_postgrant_copied)
        AlertDialog(
            onDismissRequest = { showTermuxPostGrantDialog = false },
            title = { Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_message))
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(text = termuxCommand,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            modifier = Modifier.padding(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.writeClipboardText(termuxCommand)
                    toaster.show(termuxCopiedText)
                    showTermuxPostGrantDialog = false
                }) { Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { showTermuxPostGrantDialog = false }) {
                    Text(stringResource(R.string.shell_tools_dismiss))
                }
            },
        )
    }
}

/**
 * Subtitle row for the Termux toggle: shows a colored dot summarising the integration
 * state plus a "Verify" affordance that runs an end-to-end smoke test (sends a tiny
 * command through Termux and waits for the result). The dot is ONLY green after a
 * successful verification within the last hour — that's the only signal that proves
 * `allow-external-apps=true` is actually in effect, since we cannot read Termux's
 * private home dir.
 */
@Composable
private fun TermuxStatusRowSubtitle(enabled: Boolean) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val staticState = remember(resumeTick) { TermuxIntegration.state(ctx) }

    var verifying by remember { mutableStateOf(false) }
    var lastVerifyError by remember { mutableStateOf<String?>(null) }

    // Reads the process-scoped timestamp from TermuxIntegration, backed by TermuxPreferences
    // so a successful verify keeps the dot green across app restarts too, not just navigation
    // within this session (GitHub #14). No recency window: matches SettingTermuxPage's rule.
    // resumeTick triggers a recompute on every onResume.
    val lastVerifiedOkAt = remember(resumeTick) { TermuxIntegration.lastVerifiedOkAtMs }
    val verified = lastVerifiedOkAt > 0

    val (dotColor, label) = when {
        staticState == TermuxIntegration.State.NOT_INSTALLED ->
            androidx.compose.ui.graphics.Color(0xFFEF4444) to stringResource(R.string.assistant_page_local_tools_termux_status_not_installed)
        staticState == TermuxIntegration.State.NO_PERMISSION ->
            androidx.compose.ui.graphics.Color(0xFFF59E0B) to stringResource(R.string.assistant_page_local_tools_termux_status_no_permission)
        verified ->
            androidx.compose.ui.graphics.Color(0xFF22C55E) to stringResource(R.string.assistant_page_local_tools_termux_status_ok)
        lastVerifyError != null ->
            androidx.compose.ui.graphics.Color(0xFFEF4444) to (lastVerifyError ?: "")
        else ->
            androidx.compose.ui.graphics.Color(0xFFEAB308) to stringResource(R.string.assistant_page_local_tools_termux_status_untested)
    }

    val verifyHint = stringResource(R.string.assistant_page_local_tools_termux_verify)
    val verifyingHint = stringResource(R.string.assistant_page_local_tools_termux_verifying)

    val canVerify = !verifying && enabled && staticState == TermuxIntegration.State.READY

    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .let { if (canVerify) it.clickable {
                if (verifying) return@clickable
                verifying = true
                lastVerifyError = null
                scope.launch {
                    val result = TermuxIntegration.verify(ctx)
                    verifying = false
                    when (result) {
                        TermuxIntegration.VerifyResult.Ok -> {
                            TermuxIntegration.markVerifiedOk()
                            resumeTick++  // force recompose so verifiedRecently flips
                            toaster.show(ctx.getString(R.string.assistant_page_local_tools_termux_verify_ok))
                        }
                        TermuxIntegration.VerifyResult.AllowExternalAppsMissing -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_verify_props_missing)
                            toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                        }
                        TermuxIntegration.VerifyResult.NoPermission -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_verify_no_permission)
                            toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                        }
                        TermuxIntegration.VerifyResult.NotInstalled -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_status_not_installed)
                        }
                        is TermuxIntegration.VerifyResult.UnexpectedOutput -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_verify_unexpected, result.stdout.take(60))
                            toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                        }
                        is TermuxIntegration.VerifyResult.OtherError -> {
                            // Transient failure (Termux killed, timeout, etc), not proof the
                            // setup is wrong: don't erase a previously-verified state over it.
                            resumeTick++
                            lastVerifyError = result.message
                            toaster.show(result.message, type = ToastType.Error)
                        }
                    }
                }
            } else it }
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(10.dp)
        ) {
            drawCircle(color = dotColor)
        }
        Text(
            text = if (verifying) verifyingHint
                   else if (canVerify && !verified) "$label · $verifyHint"
                   else label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
