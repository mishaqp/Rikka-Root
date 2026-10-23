package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.rikkahub.data.kimi.*
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun KimiProviderConfigure(
    provider: ProviderSetting.KimiCode,
    onEdit: (ProviderSetting.KimiCode) -> Unit,
    onModelsRefreshed: ((List<Model>, Boolean) -> Unit)? = null,
) {
    val repository = koinInject<KimiAccountRepository>()
    val oauth = koinInject<KimiOAuthManager>()
    val transport = koinInject<KimiProvider>()
    val account by repository.account.collectAsStateWithLifecycle()
    val status by oauth.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentProvider by rememberUpdatedState(provider)
    val currentOnEdit by rememberUpdatedState(onEdit)
    val currentModelsCallback by rememberUpdatedState(onModelsRefreshed)
    var selectedRegion by rememberSaveable { mutableStateOf<KimiRegion?>(null) }
    val region = kimiLoginRegion(selectedRegion, status, account?.region)
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf("") }
    var errorNotice by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    suspend fun refresh(afterLogin: Boolean) {
        val models = transport.listModels(currentProvider)
        val callback = currentModelsCallback
        if (callback != null) callback(models, afterLogin)
        else currentOnEdit(currentProvider.copy(models = mergeCodexModels(currentProvider.models, models),
            enabled = currentProvider.enabled || (afterLogin && models.isNotEmpty())))
        val metadataErrors = transport.refreshAccount()
        errorNotice = models.isEmpty() || metadataErrors.isNotEmpty()
        notice = when {
            models.isEmpty() -> "Вход сохранён, но Kimi не вернул доступных моделей. Проверьте подписку Kimi Code."
            metadataErrors.isNotEmpty() -> "Модели доступны. ${metadataErrors.joinToString("\n")}"
            afterLogin -> "Вход в Kimi сохранён. Доступно моделей: ${models.size}."
            else -> "Аккаунт проверен. Доступно моделей: ${models.size}."
        }
    }

    LaunchedEffect(repository) {
        try {
            repository.load()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            notice = "Не удалось загрузить сессию Kimi. Повторите вход."; errorNotice = true
        } finally { loading = false }
    }
    LaunchedEffect(status) {
        when (val result = status) {
            is KimiOAuthStatus.Success -> {
                busy = true
                try { refresh(afterLogin = true) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    notice = "Вход сохранён, но доступ к моделям не подтверждён. ${safeKimiError(error)}"
                    errorNotice = true
                } finally { busy = false }
                oauth.consumeResult(result)
            }
            is KimiOAuthStatus.Error -> {
                notice = result.message; errorNotice = true
                oauth.consumeResult(result)
            }
            else -> Unit
        }
    }
    // The application-scoped flow survives browser navigation and activity recreation.
    val waiting = status as? KimiOAuthStatus.Waiting
    val pending = waiting != null || status is KimiOAuthStatus.Starting
    val activeAccount = account
    val canUse = activeAccount != null && !activeAccount.needsLogin

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Вход через аккаунт Kimi Code", style = MaterialTheme.typography.titleMedium)
        Text("Подтвердите вход на официальной странице Kimi в браузере. После подтверждения вернитесь в приложение. Сессия сохранится на этом устройстве.",
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KimiRegion.entries.forEach { item ->
                FilterChip(selected = region == item, onClick = { selectedRegion = item }, enabled = !pending && !busy && !loading,
                    label = { Text(if (item == KimiRegion.CN) "Kimi.com" else "Kimi.ai") })
            }
        }
        Text(region.label, style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            notice = ""; errorNotice = false; oauth.startLogin(region)
        }, enabled = !pending && !busy && !loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (activeAccount == null) "Войти в Kimi" else "Войти снова / сменить аккаунт")
        }
        if (loading || busy || status is KimiOAuthStatus.Starting) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (waiting != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ожидаем подтверждения в браузере")
                    SelectionContainer { Text(waiting.userCode, style = MaterialTheme.typography.headlineSmall) }
                    if (waiting.browserLaunchFailed) Text("Браузер не открылся. Откройте ссылку вручную или с другого устройства.")
                    SelectionContainer { Text(waiting.verificationUrl, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = {
                        runCatching { CustomTabsOAuthAuthorizationLauncher.launch(context, waiting.verificationUrl) }
                            .onFailure { notice = "Не удалось открыть браузер. Используйте ссылку выше."; errorNotice = true }
                    }) { Text("Открыть страницу входа") }
                    OutlinedButton(onClick = oauth::cancelLogin) { Text("Отменить вход") }
                }
            }
        }
        if (notice.isNotBlank()) Text(notice, color = if (errorNotice) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Использовать Kimi")
                Text(if (canUse) "Доступность моделей и квоты определяет подписка Kimi Code." else "Сначала выполните вход.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = provider.enabled, enabled = canUse && !busy && !pending && !loading,
                onCheckedChange = { currentOnEdit(currentProvider.copy(enabled = it)) })
        }
        if (activeAccount != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(activeAccount.name, style = MaterialTheme.typography.titleMedium)
                    if (activeAccount.membership.isNotBlank()) Text("Подписка: ${activeAccount.membership}")
                    Text(activeAccount.region.label, style = MaterialTheme.typography.bodySmall)
                    Text(when {
                        activeAccount.needsLogin -> "Сессия недействительна — войдите снова."
                        activeAccount.tokens.expiresAt <= System.currentTimeMillis() -> "Сессия будет автоматически обновлена при следующем запросе."
                        else -> "Сессия сохранена на устройстве."
                    }, color = if (activeAccount.needsLogin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (activeAccount.checkedAt == null) Text("Состояние подписки ещё не проверено.", style = MaterialTheme.typography.bodySmall)
                    else Text("Последняя проверка: ${kimiDisplayTime(activeAccount.checkedAt)}", style = MaterialTheme.typography.bodySmall)
                    activeAccount.quotas.forEach { quota ->
                        val remaining = (1.0 - quota.usedRatio).coerceIn(0.0, 1.0).toFloat()
                        Text("${quota.name}: осталось ${(remaining * 100).roundToInt()}%")
                        LinearProgressIndicator(progress = { remaining }, modifier = Modifier.fillMaxWidth(),
                            color = if (remaining == 0f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        quota.resetAt?.let { Text("Обновление: ${kimiDisplayReset(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            busy = true
                            try { refresh(afterLogin = false) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { notice = safeKimiError(error); errorNotice = true }
                            finally { busy = false }
                        }
                    }, enabled = !busy && !pending && !loading && !activeAccount.needsLogin, modifier = Modifier.fillMaxWidth()) {
                        Text("Проверить аккаунт и обновить модели")
                    }
                    TextButton(onClick = { confirmSignOut = true }, enabled = !busy && !pending && !loading) { Text("Выйти из Kimi") }
                }
            }
        }
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false },
        title = { Text("Выйти из Kimi?") }, text = { Text("Сессия будет удалена с этого устройства. Чаты сохранятся.") },
        confirmButton = { TextButton(onClick = {
            confirmSignOut = false
            scope.launch {
                busy = true
                try {
                    oauth.cancelLogin(); repository.signOut()
                    currentOnEdit(currentProvider.copy(enabled = false))
                    notice = "Вы вышли из Kimi."; errorNotice = false
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { notice = safeKimiError(error); errorNotice = true }
                finally { busy = false }
            }
        }) { Text("Выйти") } }, dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Отмена") } })
}

private fun kimiDisplayTime(value: Long): String = runCatching {
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
}.getOrDefault("неизвестно")
private fun kimiDisplayReset(value: String): String = runCatching { kimiDisplayTime(Instant.parse(value).toEpochMilli()) }.getOrDefault(value)
