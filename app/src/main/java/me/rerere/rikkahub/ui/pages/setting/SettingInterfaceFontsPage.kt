package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.ui.theme.rememberAppFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberCodeFontFamily
import me.rerere.ui.components.Select
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import java.io.File

@Composable
fun SettingInterfaceFontsPage(target: String, state: InterfacePageState = rememberInterfacePageState()) {
    val display = state.settings.displaySetting
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (family, path, name) = display.fontSelection(target)
    val resolved = when (target) { "app" -> rememberAppFontFamily(display); "code" -> rememberCodeFontFamily(display); else -> rememberChatFontFamily(display) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            failed = false
            runCatching { withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: error("Unreadable font")
                importInterfaceFont(context, input, FileUtils.getFileNameFromUri(context, uri).orEmpty(), target)
            } }.onSuccess { imported -> state.updateDisplaySetting { it.withFontSelection(target, ChatFontFamily.CUSTOM, imported.path, imported.name) } }
                .onFailure { failed = true }
        }
    }
    InterfaceSettingsScaffold(fontTitle(target)) {
        item { InterfaceSettingsCard {
            InterfaceNavigationRow(R.string.ui_google_fonts_title) { state.navigate(Screen.SettingInterfaceGoogleFonts(target)) }
            InterfaceDivider()
            InterfaceNavigationRow(R.string.ui_local_font, detail = name.takeIf(String::isNotBlank)) { picker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype", "application/octet-stream")) }
            InterfaceDivider()
            InterfaceNavigationRow(R.string.ui_display_settings_page_font_reset_label) { state.updateDisplaySetting { it.withFontSelection(target, ChatFontFamily.DEFAULT, "", "") } }
        } }
        item { InterfaceSettingsCard {
            Text(stringResource(R.string.ui_font_system), Modifier.padding(14.dp), style = MaterialTheme.typography.titleSmall)
            Select(ChatFontFamily.entries, family, { selected ->
                if (selected == ChatFontFamily.CUSTOM && path.isBlank()) picker.launch(arrayOf("*/*"))
                else state.updateDisplaySetting { it.withFontSelection(target, selected, path, name) }
            }, Modifier.padding(horizontal = 14.dp), optionToString = { fontChoiceLabel(it, name) })
            Text(stringResource(if (target == "code") R.string.ui_font_code_sample else R.string.ui_font_sample),
                Modifier.padding(14.dp), fontFamily = resolved, style = MaterialTheme.typography.bodyLarge)
        } }
        if (failed) item { Text(stringResource(R.string.ui_font_import_error), color = MaterialTheme.colorScheme.error) }
    }
}

internal fun DisplaySetting.fontSelection(target: String) = when (target) {
    "app" -> Triple(appFontFamily, appCustomFontPath, appCustomFontName)
    "code" -> Triple(codeFontFamily, codeCustomFontPath, codeCustomFontName)
    else -> Triple(chatFontFamily, chatCustomFontPath, chatCustomFontName)
}

internal fun DisplaySetting.withFontSelection(target: String, family: ChatFontFamily, path: String, name: String) = when (target) {
    "app" -> copy(appFontFamily = family, appCustomFontPath = path, appCustomFontName = name)
    "code" -> copy(codeFontFamily = family, codeCustomFontPath = path, codeCustomFontName = name)
    else -> copy(chatFontFamily = family, chatCustomFontPath = path, chatCustomFontName = name)
}

@Composable
internal fun fontChoiceLabel(family: ChatFontFamily, name: String): String = when (family) {
    ChatFontFamily.DEFAULT -> stringResource(R.string.ui_font_default)
    ChatFontFamily.SERIF -> stringResource(R.string.ui_font_serif)
    ChatFontFamily.MONOSPACE -> stringResource(R.string.ui_font_monospace)
    ChatFontFamily.CUSTOM -> name.ifBlank { stringResource(R.string.ui_font_custom) }
}

internal fun fontTitle(target: String): Int = when (target) {
    "app" -> R.string.ui_display_settings_page_app_font_title
    "code" -> R.string.ui_display_settings_page_code_font_title
    else -> R.string.ui_fonts_chat
}

@Composable
fun SettingInterfaceGoogleFontsPage(target: String, state: InterfacePageState = rememberInterfacePageState()) {
    val context = LocalContext.current
    val client: OkHttpClient = koinInject()
    val repository = remember(context, client) { GoogleFontsRepository(File(context.cacheDir, "google_fonts"), client) }
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<GoogleFontEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<GoogleFontEntry?>(null) }
    var download by remember { mutableStateOf<DownloadedGoogleFont?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    suspend fun load(refresh: Boolean = false) {
        loading = true; failed = false
        try { catalog = repository.loadCatalog(refresh) }
        catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error; failed = true }
        finally { loading = false }
    }
    LaunchedEffect(repository) { load() }
    DisposableEffect(Unit) { onDispose { download?.dispose() } }
    GoogleFontsPickerContent(catalog, loading, failed, selected, downloading, progress, download, applying,
        onRefresh = { scope.launch { load(true) } },
        onSelect = { font -> scope.launch {
            selected = font; downloading = true; failed = false; progress = null
            download?.dispose(); download = null
            var pending: DownloadedGoogleFont? = null
            try { pending = repository.download(font) { progress = it }; download = pending; pending = null }
            catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error; failed = true }
            finally { pending?.dispose(); downloading = false }
        } },
        onApply = { scope.launch {
            val source = download ?: return@launch
            applying = true; failed = false
            try {
                val imported = withContext(Dispatchers.IO) { importInterfaceFont(context, source.file.inputStream(), selected?.family.orEmpty(), target, source.license) }
                state.updateDisplaySetting { it.withFontSelection(target, ChatFontFamily.CUSTOM, imported.path, imported.name) }
                state.navigate(Screen.SettingInterfaceFonts(target))
            } catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error; failed = true }
            finally { applying = false }
        } })
}

/** An injectable catalog keeps screenshots and previews offline while exercising the real picker. */
@Composable
fun GoogleFontsPickerContent(
    catalog: List<GoogleFontEntry>,
    loading: Boolean = false,
    failed: Boolean = false,
    selected: GoogleFontEntry? = null,
    downloading: Boolean = false,
    progress: Float? = null,
    download: DownloadedGoogleFont? = null,
    applying: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelect: (GoogleFontEntry) -> Unit = {},
    onApply: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var showLicense by remember { mutableStateOf(false) }
    val previewFont = remember(download?.file?.path) { download?.let { runCatching { FontFamily(Font(it.file)) }.getOrNull() } }
    val fonts = catalog.filter { font -> font.family.contains(query.trim(), true) || font.subsets.any { it.contains(query.trim(), true) } }
    InterfaceSettingsScaffold(R.string.ui_google_fonts_title, actions = {
        IconButton(onClick = onRefresh, enabled = !loading && !downloading && !applying) { Icon(HugeIcons.Refresh03, stringResource(R.string.ui_google_fonts_refresh)) }
    }) {
        item { InterfaceFooter(R.string.ui_google_fonts_hint) }
        item { OutlinedTextField(query, { query = it }, singleLine = true,
            label = { Text(stringResource(R.string.ui_google_fonts_search)) }, modifier = Modifier.fillMaxWidth().testTag("ui_google_fonts_search")) }
        if (failed) item { Text(stringResource(R.string.ui_google_fonts_failed), color = MaterialTheme.colorScheme.error) }
        if (selected != null) item { InterfaceSettingsCard {
            Text(selected.family, Modifier.padding(14.dp), style = MaterialTheme.typography.titleSmall)
            if (downloading) {
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 14.dp))
                Text(stringResource(R.string.ui_google_fonts_downloading), Modifier.padding(14.dp))
            } else if (previewFont != null) {
                Text(stringResource(R.string.ui_font_sample), Modifier.padding(14.dp), fontFamily = previewFont, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showLicense = true }) { Text(stringResource(R.string.ui_google_fonts_license)) }
                    Button(onClick = onApply, enabled = !applying, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.ui_apply)) }
                }
            }
        } }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(24.dp)) { CircularProgressIndicator() } }
        else if (fonts.isEmpty()) item { Text(stringResource(R.string.ui_google_fonts_no_results), Modifier.padding(14.dp)) }
        else items(fonts, key = { it.url }) { font ->
            ListItem(headlineContent = { Text(font.family) }, supportingContent = { Text(font.subsets.joinToString(", ")) },
                modifier = Modifier.fillMaxWidth().clickable(enabled = !downloading && !applying) { onSelect(font) },
                colors = ListItemDefaults.colors(containerColor = if (font == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow))
        }
    }
    if (showLicense) AlertDialog(onDismissRequest = { showLicense = false },
        title = { Text(stringResource(R.string.ui_google_fonts_license)) },
        text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(download?.license.orEmpty(), Modifier.verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.ui_cancel)) } })
}
