package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingUpdatePage() {
    val checker = koinInject<UpdateChecker>()
    val state by checker.nightlyState.collectAsStateWithLifecycle()
    val update = state.update
    val context = LocalContext.current
    val nav = LocalNavController.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Обновление приложения") },
                navigationIcon = { BackButton() },
                scrollBehavior = scroll,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Канал nightly", style = MaterialTheme.typography.titleLarge)
                        Text("Установлено: ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT_SHORT.ifBlank { "коммит не указан" }}")
                        if (update != null) {
                            Text(if (update.isNewer) "Доступна новая сборка" else "Установлена актуальная сборка")
                            Text("Nightly: ${update.version}")
                            update.commitHash.takeIf { it.isNotBlank() }?.let { hash ->
                                Text("Коммит: ${hash.take(7)}")
                            }
                            if (update.publishedAt.isNotBlank()) Text("Опубликовано: ${update.publishedAt}")
                        } else {
                            Text(if (state.isChecking) "Проверяю обновления…" else "Сведения о nightly пока недоступны")
                        }
                        TextButton(onClick = { nav.navigate(Screen.SettingAbout) }) {
                            Text("Проверить обновления в «О приложении»")
                        }
                    }
                }
            }
            if (update != null) {
                if (update.downloads.isNotEmpty()) {
                    item { Text("Скачать APK", style = MaterialTheme.typography.titleMedium) }
                    itemsIndexed(update.downloads, key = { index, download -> "download:$index:${download.name}" }) { _, download ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(download.name, style = MaterialTheme.typography.titleSmall)
                                if (download.size.isNotBlank()) Text(download.size, style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { checker.downloadUpdate(context, download) }) { Text("Скачать APK") }
                            }
                        }
                    }
                } else {
                    item { Text("APK этой сборки пока недоступен") }
                }
                item { Text("Изменения", style = MaterialTheme.typography.titleMedium) }
                val titles = update.commitTitles.take(30)
                if (titles.isEmpty()) {
                    item { Text("Список изменений недоступен") }
                } else {
                    itemsIndexed(titles, key = { index, _ -> "commit:$index" }) { _, title ->
                        Card(Modifier.fillMaxWidth()) { Text(title, Modifier.padding(16.dp)) }
                    }
                }
            }
            item {
                TextButton(onClick = { context.openUrl("https://github.com/mishaqp/Rikka-Root/releases/tag/nightly") }) {
                    Text("Открыть nightly на GitHub")
                }
            }
        }
    }
}
