package me.rerere.rikkahub.miniapps

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.koin.core.context.GlobalContext

/** Standalone viewer also handles cold deep links and .moruapp open/share intents. */
class MiniAppActivity : ComponentActivity() {
    private var app by mutableStateOf<MiniApp?>(null)
    private var failure by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(true)
    private var session: MiniAppWebSession? = null
    private val permissionsMutex = Mutex()
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionResult?.complete(grants.isNotEmpty() && grants.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                BackHandler { back() }
                MiniAppViewerContent(app?.name ?: stringResource(R.string.mini_apps_title), loading, failure,
                    onBack = ::back, onReload = { session?.webView?.reload() }, onPin = { pin() },
                    webView = session?.webView)
            }
        }
        lifecycleScope.launch {
            try {
                val store = GlobalContext.get().get<MiniAppStore>()
                store.load()
                val id = intent.dataString?.let(MiniAppStore::idFromLink) ?: run {
                    val uri = if (intent.action == Intent.ACTION_SEND)
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else intent.data
                    if (uri == null || uri.scheme !in setOf("content", "file")) throw MiniAppException("not_found", getString(R.string.mini_apps_not_found))
                    MiniAppLauncher.importUri(this@MiniAppActivity, uri, store).app.id
                }
                val installed = store.byId(id) ?: throw MiniAppException("not_found", getString(R.string.mini_apps_not_found))
                store.refreshBridge(installed)
                val host = GlobalContext.get().get<MiniAppNativeHost>().forApp(installed, ::requestPermissions)
                val bridge = MiniAppBridge(store, id, host)
                val runtime = MiniAppWebSession(this@MiniAppActivity, installed, bridge,
                    onLoading = { loading = it }, onExternalLink = ::openExternal)
                session = runtime
                app = installed
                lifecycleScope.launch {
                    store.changes.collect { change ->
                        if (change.appId == id) runtime.webView.evaluateJavascript(MiniAppBridge.changedScript(change.key), null)
                    }
                }
                runtime.load()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                session?.close(); session = null
                failure = e.message ?: getString(R.string.mini_apps_open_failed)
                loading = false
            }
        }
    }

    private suspend fun requestPermissions(required: List<String>): Boolean = withContext(Dispatchers.Main.immediate) {
        permissionsMutex.withLock {
            val missing = required.filter { ContextCompat.checkSelfPermission(this@MiniAppActivity, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) return@withLock true
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withLock false
            val result = CompletableDeferred<Boolean>()
            permissionResult = result
            try { permissions.launch(missing.toTypedArray()); result.await() }
            finally { permissionResult = null }
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) { Toast.makeText(this, R.string.mini_apps_link_failed, Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this, R.string.mini_apps_link_failed, Toast.LENGTH_LONG).show() }
    }

    private fun back() {
        val view = session?.webView
        if (view != null && view.canGoBack()) view.goBack() else finish()
    }

    private fun pin() {
        val current = app ?: return
        lifecycleScope.launch {
            try {
                val requested = MiniAppLauncher.pin(this@MiniAppActivity, current)
                Toast.makeText(this@MiniAppActivity, if (requested) R.string.mini_apps_pin_requested else R.string.mini_apps_pin_unsupported, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(this@MiniAppActivity, R.string.mini_apps_pin_unsupported, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onDestroy() {
        session?.close(); session = null
        permissionResult?.cancel()
        super.onDestroy()
    }
}

@Composable
internal fun MiniAppViewerContent(
    title: String, loading: Boolean, failure: String?, onBack: () -> Unit,
    onReload: () -> Unit, onPin: () -> Unit, webView: android.webkit.WebView?,
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) }
        }, actions = {
            IconButton(onClick = onReload, enabled = webView != null) { Icon(HugeIcons.Refresh01, stringResource(R.string.mini_apps_reload)) }
            IconButton(onClick = onPin, enabled = webView != null) { Icon(HugeIcons.SmartPhone01, stringResource(R.string.mini_apps_pin)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure != null) Text(failure, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            else if (webView != null) AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}
