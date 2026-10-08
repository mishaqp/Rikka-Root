// Adapted from ExTV/rikkahub-agent ToolHostActivity SAF picker mode (AGPL v3).
package me.rerere.rikkahub.ui.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.SafUiResult
import me.rerere.rikkahub.data.ai.tools.local.SafUiSession
import me.rerere.rikkahub.data.ai.tools.local.sharedSafToolSessions
import me.rerere.rikkahub.ui.theme.RikkahubTheme

/** Visible host for the native tree picker. It never requests broad storage access. */
class SafToolActivity : ComponentActivity() {
    private var session: SafUiSession? = null
    private var waitingForPicker = false

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        waitingForPicker = false
        val id = session?.id ?: run { finish(); return@registerForActivityResult }
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            sharedSafToolSessions.complete(id, SafUiResult.Cancelled)
            finish()
            return@registerForActivityResult
        }
        val flags = result.data?.flags ?: 0
        val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri) || flags and access != access ||
            flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) {
            sharedSafToolSessions.complete(id, SafUiResult.Error("Выбранная папка не поддерживает постоянный доступ для чтения и записи."))
            finish()
            return@registerForActivityResult
        }
        sharedSafToolSessions.grant(id, SafUiResult.Granted(uri.toString())) {
            contentResolver.takePersistableUriPermission(uri, access)
            check(contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_REQUEST_ID) ?: run { finish(); return }
        val current = sharedSafToolSessions.get(id) ?: run { finish(); return }
        session = current
        waitingForPicker = savedInstanceState?.getBoolean(STATE_WAITING) ?: current.pickerStarted
        if (!sharedSafToolSessions.attach(id) {
                withContext(Dispatchers.Main.immediate) { picker.unregister(); finish() }
            }) { finish(); return }
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                BackHandler { cancel() }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Доступ к папке", style = MaterialTheme.typography.titleLarge)
                        Text(current.request.label ?: "Выберите папку в системном окне Android. Доступ сохранится до его отзыва в системе.")
                        Button(onClick = { cancel() }) { Text("Отмена") }
                    }
                }
            }
        }
        lifecycleScope.launch { current.await(); finish() }
    }

    override fun onResume() {
        super.onResume()
        val current = session ?: return
        if (!sharedSafToolSessions.isActive(current.id)) { finish(); return }
        if (!sharedSafToolSessions.beginPicker(current.id)) return
        val treePicker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) current.request.initialUri?.let {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it))
            }
        }
        waitingForPicker = true
        try { picker.launch(treePicker) } catch (_: Exception) {
            waitingForPicker = false
            sharedSafToolSessions.complete(current.id, SafUiResult.Error("В Android нет доступного системного выбора папки."))
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_WAITING, waitingForPicker)
        super.onSaveInstanceState(outState)
    }
    private fun cancel() {
        val id = session?.id ?: return
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { sharedSafToolSessions.cancel(id); finish() }
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !waitingForPicker) cancel()
    }
    override fun onDestroy() {
        // Android may destroy the host while the external picker remains open.
        if (!isChangingConfigurations && (!waitingForPicker || isFinishing)) cancel()
        super.onDestroy()
    }
    companion object {
        const val EXTRA_REQUEST_ID = "saf_tool_request_id"
        private const val STATE_WAITING = "saf_waiting_for_picker"
    }
}
