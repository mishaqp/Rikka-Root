// NFC reader adapted from ExTV/rikkahub-agent, local/ToolHostActivity.kt (AGPL v3).
package me.rerere.rikkahub.ui.activity

import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.TagTechnology
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.NfcNdefCodec
import me.rerere.rikkahub.data.ai.tools.local.NfcResult
import me.rerere.rikkahub.data.ai.tools.local.NfcSession
import me.rerere.rikkahub.data.ai.tools.local.sharedNfcSessions
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import java.io.IOException

/** Only a live, approved in-memory request can open the physical NFC reader. */
class NfcToolActivity : ComponentActivity() {
    private var session: NfcSession? = null
    private var adapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_REQUEST_ID)
        val request = id?.let { sharedNfcSessions.get(it) }
        if (request == null) { finish(); return }
        session = request
        adapter = getSystemService(NfcManager::class.java)?.defaultAdapter
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(if (request.write) R.string.nfc_tool_write_title else R.string.nfc_tool_read_title))
                        Text(stringResource(if (request.write) R.string.nfc_tool_write_prompt else R.string.nfc_tool_read_prompt))
                        Text(stringResource(R.string.nfc_tool_timeout, request.timeoutSeconds))
                        Button(onClick = { cancelSession(); finish() }) {
                            Text(stringResource(R.string.assistant_page_local_tools_permission_cancel))
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            val result = withTimeoutOrNull(request.timeoutSeconds * 1000L) { request.await() }
            if (result == null) sharedNfcSessions.complete(request.requestId, NfcResult.Timeout)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val request = session ?: return
        if (!sharedNfcSessions.isActive(request.requestId)) { finish(); return }
        val reader = adapter
        if (reader == null || !reader.isEnabled) {
            completeSession(NfcResult.Error(getString(R.string.nfc_tool_unavailable)))
            finish(); return
        }
        try {
            // SKIP_NDEF_CHECK would hide the Ndef technology that read/write needs.
            reader.enableReaderMode(this, { tag ->
                if (sharedNfcSessions.claim(request.requestId)) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val result = try {
                            if (request.write) writeTag(tag, request) else readTag(tag, request)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: TagLostException) {
                            NfcResult.Error(getString(R.string.nfc_tool_tag_lost))
                        } catch (_: IOException) {
                            NfcResult.Error(getString(R.string.nfc_tool_io_error))
                        } catch (_: Exception) {
                            NfcResult.Error(getString(R.string.nfc_tool_failed))
                        }
                        sharedNfcSessions.complete(request.requestId, result)
                    }
                }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
        } catch (_: Exception) {
            completeSession(NfcResult.Error(getString(R.string.nfc_tool_unavailable)))
            finish()
        }
    }

    private fun readTag(tag: Tag, request: NfcSession): NfcResult {
        if (!sharedNfcSessions.isActive(request.requestId)) return NfcResult.Cancelled
        val ndef = Ndef.get(tag) ?: return NfcResult.ReadOk("[]", tagId(tag), false)
        return useTechnology(ndef, request) {
            val message = ndef.ndefMessage
            val records = message?.let { NfcNdefCodec.decode(it).toString() } ?: "[]"
            NfcResult.ReadOk(records, tagId(tag))
        }
    }

    private fun writeTag(tag: Tag, request: NfcSession): NfcResult {
        if (!sharedNfcSessions.isActive(request.requestId)) return NfcResult.Cancelled
        val records = request.records ?: return NfcResult.Error(getString(R.string.nfc_tool_failed))
        val message = NfcNdefCodec.encode(records)
        val ndef = Ndef.get(tag)
        if (ndef != null) return useTechnology(ndef, request) {
            if (!ndef.isWritable) return@useTechnology NfcResult.Error(getString(R.string.nfc_tool_read_only))
            if (message.toByteArray().size > ndef.maxSize) return@useTechnology NfcResult.Error(getString(R.string.nfc_tool_too_small))
            if (!sharedNfcSessions.isActive(request.requestId)) return@useTechnology NfcResult.Cancelled
            ndef.writeNdefMessage(message)
            NfcResult.WriteOk(tagId(tag))
        }
        val formatable = NdefFormatable.get(tag) ?: return NfcResult.Error(getString(R.string.nfc_tool_not_ndef))
        return useTechnology(formatable, request) {
            if (!sharedNfcSessions.isActive(request.requestId)) return@useTechnology NfcResult.Cancelled
            formatable.format(message)
            NfcResult.WriteOk(tagId(tag))
        }
    }

    private fun useTechnology(tech: TagTechnology, request: NfcSession, action: () -> NfcResult): NfcResult {
        val close: () -> Unit = { runCatching { tech.close() }; Unit }
        if (!request.operationGate.attach(close)) return NfcResult.Cancelled
        return try {
            request.operationGate.perform {
                tech.connect()
                if (!sharedNfcSessions.isActive(request.requestId)) NfcResult.Cancelled else action()
            }
        } finally {
            request.operationGate.detach(close)
            close()
        }
    }

    private fun completeSession(result: NfcResult) {
        val request = session ?: return
        // Marks the session as stopping before dispatching close/drain to IO.
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { sharedNfcSessions.complete(request.requestId, result) }
    }

    private fun cancelSession() = completeSession(NfcResult.Cancelled)

    override fun onPause() {
        cancelSession()
        runCatching { adapter?.disableReaderMode(this) }
        super.onPause()
    }

    override fun onDestroy() {
        cancelSession()
        super.onDestroy()
    }

    private fun tagId(tag: Tag): String = tag.id.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    companion object {
        const val EXTRA_REQUEST_ID = "nfc_request_id"
    }
}
