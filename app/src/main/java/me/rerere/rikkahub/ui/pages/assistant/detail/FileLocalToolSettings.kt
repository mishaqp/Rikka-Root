package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.os.Build
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
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
import me.rerere.rikkahub.data.ai.tools.local.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

private data class FileToolSetting(val option: LocalToolOption, val title: Int, val description: Int)
private val fileToolSettings=listOf(
    FileToolSetting(LocalToolOption.Files,R.string.file_tools_files_title,R.string.file_tools_files_desc),
    FileToolSetting(LocalToolOption.ExternalStorage,R.string.file_tools_storage_title,R.string.file_tools_storage_desc),
    FileToolSetting(LocalToolOption.Archive,R.string.file_tools_archive_title,R.string.file_tools_archive_desc),
    FileToolSetting(LocalToolOption.MediaPlayer,R.string.file_tools_player_title,R.string.file_tools_player_desc),
    FileToolSetting(LocalToolOption.MediaScanner,R.string.file_tools_scanner_title,R.string.file_tools_scanner_desc),
    FileToolSetting(LocalToolOption.Download,R.string.file_tools_download_title,R.string.file_tools_download_desc),
    FileToolSetting(LocalToolOption.SystemIntents,R.string.file_tools_intents_title,R.string.file_tools_intents_desc),
    FileToolSetting(LocalToolOption.AppLauncher,R.string.file_tools_launcher_title,R.string.file_tools_launcher_desc),
)

@Composable
internal fun FileLocalToolSettings(assistant: Assistant,onUpdate:(Assistant)->Unit) {
    val context=LocalContext.current; val toaster=LocalToaster.current; val scope=rememberCoroutineScope(); val root=koinInject<RootShellManager>()
    val resources=LocalResources.current
    val current by rememberUpdatedState(assistant); val update by rememberUpdatedState(onUpdate)
    var pending by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var showRationale by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var denied by rememberSaveable(assistant.id.toString()) { mutableStateOf(false) }
    var rootBusy by remember { mutableStateOf(false) }; var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val downloadMissing=remember(context,revision) { missingLocalToolPermissions(context,LocalToolOption.Download).isNotEmpty() }
    fun setEnabled(option:LocalToolOption,enabled:Boolean) {
        update(current.copy(localTools=if(enabled)(current.localTools+option).distinct() else current.localTools-option))
    }
    fun finishRequest() {
        pending=false; revision++
        val granted=missingLocalToolPermissions(context,LocalToolOption.Download).isEmpty()
        denied=!granted; setEnabled(LocalToolOption.Download,granted)
        if(!granted) toaster.show(resources.getString(R.string.file_tools_permission_denied),type=ToastType.Warning)
    }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finishRequest() }
    CardGroup {
        fileToolSettings.forEach { setting -> item(
            headlineContent={ Text(stringResource(setting.title)) },
            supportingContent={ Column {
                Text(stringResource(setting.description))
                if(setting.option==LocalToolOption.Download && downloadMissing) {
                    if(denied || setting.option in assistant.localTools) Text(stringResource(R.string.file_tools_permission_denied))
                    TextButton(enabled=!rootBusy&&!pending,onClick={
                        rootBusy=true
                        scope.launch {
                            try {
                                val command=localPermissionGrantCommand(context.packageName,LocalToolOption.Download,Build.VERSION.SDK_INT,Process.myUid()/100_000)
                                val result=if(command!=null&&RootCommandGuard.check(command)==null) root.exec(command,20_000) else null
                                revision++
                                val granted=result?.error==null&&result?.exitCode==0&&missingLocalToolPermissions(context,LocalToolOption.Download).isEmpty()
                                if(granted) denied=false
                                toaster.show(resources.getString(if(granted) R.string.assistant_page_local_tools_root_granted else R.string.assistant_page_local_tools_root_grant_failed),type=if(granted) ToastType.Success else ToastType.Warning)
                            } catch(cancelled:CancellationException) { throw cancelled }
                            catch(_:Exception) { toaster.show(resources.getString(R.string.assistant_page_local_tools_root_grant_failed),type=ToastType.Warning) }
                            finally { rootBusy=false }
                        }
                    }) { Text(stringResource(R.string.assistant_page_local_tools_grant_root)) }
                }
            } },
            trailingContent={ Switch(checked=setting.option in assistant.localTools,enabled=!rootBusy&&!pending,onCheckedChange={ checked ->
                if(checked&&setting.option==LocalToolOption.Download&&missingLocalToolPermissions(context,setting.option).isNotEmpty()) { pending=true; showRationale=true }
                else setEnabled(setting.option,checked)
            }) },
        ) }
    }
    if(showRationale&&pending) AlertDialog(
        title={ Text(stringResource(R.string.assistant_page_local_tools_permission_title)) },
        text={ Text(stringResource(R.string.file_tools_legacy_download_permission)) },
        onDismissRequest={ showRationale=false; pending=false },
        confirmButton={ TextButton(onClick={ showRationale=false; try { launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) } catch(_:Exception) { finishRequest() } }) { Text(stringResource(R.string.assistant_page_local_tools_permission_request)) } },
        dismissButton={ TextButton(onClick={ showRationale=false; pending=false }) { Text(stringResource(R.string.assistant_page_local_tools_permission_cancel)) } },
    )
}
