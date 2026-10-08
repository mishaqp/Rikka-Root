package me.rerere.rikkahub.ui.pages.setting.ssh

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/** Mandatory safe input for the original saved-host tools; credentials never enter chat. */
@Composable
fun SettingSshPage(repo: SshHostRepository = koinInject()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hosts by remember { mutableStateOf<List<SshHostEntity>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    // Intentionally not rememberSaveable: Android state bundles must not contain secrets.
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    DisposableEffect(context) {
        val window = context.activity()?.window
        val secureBefore = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            password = ""
            privateKey = ""
            passphrase = ""
            if (!secureBefore) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    LaunchedEffect(repo) {
        try { hosts = withContext(Dispatchers.IO) { repo.getAll() } }
        catch (_: Exception) { failed = true }
    }
    Scaffold(topBar = {
        LargeFlexibleTopAppBar(title = { Text(stringResource(R.string.setting_ssh_title)) },
            navigationIcon = { BackButton() }, scrollBehavior = scrollBehavior, colors = CustomColors.topBarColors)
    }, containerColor = CustomColors.topBarColors.containerColor) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.setting_ssh_security_info))
            if (failed) Text(stringResource(R.string.setting_ssh_error), color = MaterialTheme.colorScheme.error)
            hosts.forEach { saved ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(saved.name, style = MaterialTheme.typography.titleMedium)
                        Text("${saved.user}@${saved.host}:${saved.port}")
                    }
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            failed = false
                            try {
                                hosts = withContext(Dispatchers.IO) { repo.deleteByName(saved.name); repo.getAll() }
                            } catch (_: Exception) { failed = true }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.setting_ssh_delete)) }
                }
            }
            Text(stringResource(R.string.setting_ssh_save_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.setting_ssh_name)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.setting_ssh_host)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.setting_ssh_port)) }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.setting_ssh_user)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.setting_ssh_password)) },
                singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(privateKey, { privateKey = it }, label = { Text(stringResource(R.string.setting_ssh_private_key)) },
                enabled = !busy, visualTransformation = PasswordVisualTransformation(), minLines = 2,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.setting_ssh_passphrase)) },
                singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && name.isNotBlank() && host.isNotBlank() && user.isNotBlank() &&
                (port.toIntOrNull() ?: 0) in 1..65535 && (password.isNotBlank() || privateKey.isNotBlank()), onClick = {
                val saved = SshHostEntity(name.trim(), host.trim(), port.toInt(), user.trim(), createdAtMs = System.currentTimeMillis()).apply {
                    this.password = password.takeIf(String::isNotBlank)
                    this.privateKey = privateKey.takeIf(String::isNotBlank)
                    this.passphrase = passphrase.takeIf(String::isNotBlank)
                }
                scope.launch {
                    busy = true
                    failed = false
                    try {
                        hosts = withContext(Dispatchers.IO) { repo.upsert(saved); repo.getAll() }
                        password = ""
                        privateKey = ""
                        passphrase = ""
                    } catch (_: Exception) { failed = true }
                    finally {
                        saved.password = null
                        saved.privateKey = null
                        saved.passphrase = null
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.setting_ssh_save)) }
        }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.takeUnless { it === this }?.activity()
    else -> null
}
