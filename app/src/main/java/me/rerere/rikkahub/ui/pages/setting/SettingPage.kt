package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.NightlyUpdateState
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val statuses = rememberSettingsDashboardStatuses(settings)
    val updateChecker = koinInject<UpdateChecker>()
    val update by updateChecker.nightlyState.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val open: (SettingsTarget) -> Unit = { target ->
        when (target) {
            is SettingsTarget.Route -> navigator.navigate(target.screen)
            is SettingsTarget.Url -> context.openUrl(target.url)
            is SettingsTarget.Share -> try {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, target.text)
                }, "Поделиться Rikka-Root"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "Нет приложения для отправки. Откройте релизы Rikka-Root на GitHub.", Toast.LENGTH_LONG).show()
            }
        }
    }
    SettingPageContent(
        settings = settings,
        statuses = statuses,
        update = update,
        query = query,
        onQueryChange = { query = it },
        open = open,
        openScreen = { navigator.navigate(it) },
    )
}

/** The production dashboard, with observed state supplied by its host. */
@Composable
internal fun SettingPageContent(
    settings: Settings,
    statuses: Map<SettingsDashboardStatusKey, SettingsDashboardStatus>,
    update: NightlyUpdateState,
    query: String,
    onQueryChange: (String) -> Unit,
    open: (SettingsTarget) -> Unit,
    openScreen: (Screen) -> Unit,
    buildLabel: String = "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT_SHORT}",
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val searchResults = remember(query) { SettingsSearchIndex.search(query) }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(title = { Text("Настройки") }, navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior, colors = CustomColors.topBarColors)
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("statePanel") {
                SettingsStatePanel(settings, statuses[SettingsDashboardStatusKey.Root], update, openScreen,
                    buildLabel = buildLabel)
            }
            item("quickTiles") {
                SettingsQuickTiles(SettingsDashboardPanelPresentation.quickTileValues(
                    settings, statuses[SettingsDashboardStatusKey.Root],
                )) { tile ->
                    val assistantId = settings.assistantId.takeIf { id -> settings.assistants.any { it.id == id } }
                    openScreen(tile.route(assistantId?.toString()))
                }
            }
            item("search") {
                OutlinedTextField(
                    value = query, onValueChange = onQueryChange, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    label = { Text("Поиск по настройкам") },
                    placeholder = { Text("Например: root, голос, батарея") },
                    leadingIcon = { Icon(HugeIcons.GlobalSearch, null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = { onQueryChange("") }) { Icon(HugeIcons.Cancel01, "Очистить поиск") }
                    }) else null,
                )
            }
            if (query.isNotBlank()) {
                if (searchResults.isEmpty()) item("emptySearch") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ничего не найдено", style = MaterialTheme.typography.titleMedium)
                        Text("Попробуйте другое название или слово: рут, сервер, doze, обновление.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(searchResults, key = { "search_${it.id}" }) { item ->
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        SettingsItem(item.displayTitle(), item.displayDescription(), item.icon, item.section.tone,
                            onClick = { open(item.target) }, status = settingsStatusOf(item.id, statuses), query = query)
                    }
                }
            } else {
                SettingsCatalog.sections.forEach { section ->
                    item("section_${section.id}") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(section.title, Modifier.padding(horizontal = 8.dp),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column {
                                    val entries = SettingsCatalog.items.filter { it.section == section }
                                    entries.forEachIndexed { index, item ->
                                        SettingsItem(item.displayTitle(), item.displayDescription(), item.icon, section.tone,
                                            onClick = { open(item.target) }, status = settingsStatusOf(item.id, statuses))
                                        if (index < entries.lastIndex) HorizontalDivider(
                                            Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun settingsStatusOf(id: String, statuses: Map<SettingsDashboardStatusKey, SettingsDashboardStatus>): SettingsDashboardStatus? {
    val key = when (id) {
        "root" -> SettingsDashboardStatusKey.Root
        "termux" -> SettingsDashboardStatusKey.Termux
        "ssh" -> SettingsDashboardStatusKey.Ssh
        "mcp" -> SettingsDashboardStatusKey.Mcp
        "workflows" -> SettingsDashboardStatusKey.Workflows
        "cron" -> SettingsDashboardStatusKey.Cron
        "browser" -> SettingsDashboardStatusKey.Browser
        "web" -> SettingsDashboardStatusKey.Web
        "approvals" -> SettingsDashboardStatusKey.Approvals
        "files" -> SettingsDashboardStatusKey.Storage
        "backup" -> SettingsDashboardStatusKey.Backup
        else -> return null
    }
    return statuses[key]
}
