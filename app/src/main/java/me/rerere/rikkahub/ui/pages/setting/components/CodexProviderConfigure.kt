package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.codex.CodexAccount
import me.rerere.rikkahub.data.codex.CodexAccountRepository
import me.rerere.rikkahub.data.codex.CodexOAuthManager
import me.rerere.rikkahub.data.codex.CodexOAuthStatus
import me.rerere.rikkahub.data.codex.CodexTokenStatus
import me.rerere.rikkahub.data.codex.CodexUsageWindow
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun CodexProviderConfigure(
    provider: ProviderSetting.Codex,
    onEdit: (ProviderSetting.Codex) -> Unit,
    onModelsRefreshed: ((List<Model>, Boolean) -> Unit)? = null,
) {
    val repository = koinInject<CodexAccountRepository>()
    val oauthManager = koinInject<CodexOAuthManager>()
    val providerManager = koinInject<ProviderManager>()
    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val oauthStatus by oauthManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val oauthSuccessText by rememberUpdatedState(stringResource(R.string.codex_oauth_success))
    val oauthModelsFailedText by rememberUpdatedState(stringResource(R.string.codex_oauth_success_models_failed))
    val modelsRefreshFailedText by rememberUpdatedState(stringResource(R.string.codex_models_refresh_failed))
    val accountRefreshFailedText by rememberUpdatedState(stringResource(R.string.codex_refresh_failed))
    val accountUpdateFailedText by rememberUpdatedState(stringResource(R.string.codex_account_update_failed))
    val accountRemoveFailedText by rememberUpdatedState(stringResource(R.string.codex_remove_failed))
    val currentProvider by rememberUpdatedState(provider)
    val currentOnEdit by rememberUpdatedState(onEdit)
    val currentOnModelsRefreshed by rememberUpdatedState(onModelsRefreshed)
    var refreshing by remember { mutableStateOf(false) }

    DisposableEffect(oauthManager) {
        onDispose {
            if (oauthManager.status.value == CodexOAuthStatus.Waiting) oauthManager.cancelLogin()
        }
    }
    val canEnable = accounts.any { it.enabled && it.tokenStatus != CodexTokenStatus.INVALID }

    LaunchedEffect(oauthStatus) {
        when (val status = oauthStatus) {
            is CodexOAuthStatus.Success -> {
                refreshing = true
                try {
                    val models = providerManager.getProviderByType(currentProvider).listModels(currentProvider)
                    val latest = currentProvider
                    val updateModels = currentOnModelsRefreshed
                    if (updateModels != null) updateModels(models, true)
                    else currentOnEdit(latest.copy(models = mergeCodexModels(latest.models, models)))
                    toaster.show(oauthSuccessText, type = ToastType.Success)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Credentials are already saved. Model discovery failure is separate from sign-in.
                    toaster.show(
                        oauthModelsFailedText,
                        type = ToastType.Warning,
                    )
                } finally {
                    refreshing = false
                }
                oauthManager.consumeResult()
            }

            is CodexOAuthStatus.Error -> {
                toaster.show(status.message, type = ToastType.Error)
                oauthManager.consumeResult()
            }

            else -> Unit
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = provider.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.codex_provider_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = oauthManager::startLogin,
            enabled = oauthStatus != CodexOAuthStatus.Waiting && !refreshing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.codex_sign_in))
        }

        if (oauthStatus == CodexOAuthStatus.Waiting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.codex_oauth_waiting))
            TextButton(onClick = oauthManager::cancelLogin) {
                Text(stringResource(R.string.cancel))
            }
        }
        if (refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    refreshing = true
                    try {
                        val models = providerManager.getProviderByType(currentProvider).listModels(currentProvider)
                        val latest = currentProvider
                        val updateModels = currentOnModelsRefreshed
                        if (updateModels != null) updateModels(models, false)
                        else currentOnEdit(latest.copy(models = mergeCodexModels(latest.models, models)))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        toaster.show(modelsRefreshFailedText, type = ToastType.Error)
                    } finally {
                        refreshing = false
                    }
                }
            },
            enabled = canEnable && !refreshing && oauthStatus != CodexOAuthStatus.Waiting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.codex_refresh_models))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.codex_enable),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (canEnable) {
                        stringResource(R.string.codex_round_robin_description)
                    } else {
                        stringResource(R.string.codex_sign_in_required)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = { onEdit(provider.copy(enabled = it)) },
                enabled = provider.enabled || canEnable,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.codex_accounts_count, accounts.size),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        var failed = false
                        accounts.forEach { account ->
                            try {
                                repository.refreshAccount(account.id)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                failed = true
                            }
                        }
                        if (failed) {
                            toaster.show(accountRefreshFailedText, type = ToastType.Error)
                        }
                    }
                },
                enabled = accounts.isNotEmpty(),
            ) {
                Text(stringResource(R.string.codex_check_status))
            }
        }

        if (accounts.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.codex_no_accounts),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            accounts.forEach { account ->
                CodexAccountCard(
                    account = account,
                    onEnabledChange = { enabled ->
                        scope.launch {
                            try {
                                repository.setEnabled(account.id, enabled)
                                if (!enabled && repository.accounts.value.none {
                                    it.enabled && it.tokenStatus != CodexTokenStatus.INVALID
                                }) {
                                    currentOnEdit(currentProvider.copy(enabled = false))
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                toaster.show(accountUpdateFailedText, type = ToastType.Error)
                            }
                        }
                    },
                    onRefresh = {
                        scope.launch {
                            try {
                                repository.refreshAccount(account.id)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                toaster.show(accountRefreshFailedText, type = ToastType.Error)
                            }
                        }
                    },
                    onReauthenticate = oauthManager::startLogin,
                    onDelete = {
                        scope.launch {
                            try {
                                repository.delete(account.id)
                                if (repository.accounts.value.none { it.enabled && it.tokenStatus != CodexTokenStatus.INVALID }) {
                                    currentOnEdit(currentProvider.copy(enabled = false))
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                toaster.show(accountRemoveFailedText, type = ToastType.Error)
                            }
                        }
                    },
                )
            }
        }
    }
}

internal fun mergeCodexModels(existing: List<Model>, refreshed: List<Model>): List<Model> {
    val refreshedByModelId = refreshed.associateBy(Model::modelId)
    val merged = existing.map { model ->
        refreshedByModelId[model.modelId]?.let { refreshedModel ->
            model.copy(
                inputModalities = refreshedModel.inputModalities,
                outputModalities = refreshedModel.outputModalities,
                abilities = refreshedModel.abilities,
                supportedReasoningEfforts = refreshedModel.supportedReasoningEfforts,
            )
        } ?: model
    }
    val existingModelIds = existing.mapTo(mutableSetOf(), Model::modelId)
    return merged + refreshed.distinctBy(Model::modelId).filterNot { it.modelId in existingModelIds }
}

@Composable
private fun CodexAccountCard(
    account: CodexAccount,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReauthenticate: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.codex_remove_account_title)) },
            text = { Text(stringResource(R.string.codex_remove_account_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    }
                ) {
                    Text(
                        stringResource(R.string.codex_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                    if (account.email.isNotBlank()) {
                        Text(
                            account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = account.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            Text(
                text = when (account.tokenStatus) {
                    CodexTokenStatus.AVAILABLE -> stringResource(R.string.codex_token_available)
                    CodexTokenStatus.EXPIRED -> stringResource(R.string.codex_token_expired)
                    CodexTokenStatus.INVALID -> stringResource(R.string.codex_token_unavailable)
                    CodexTokenStatus.UNKNOWN -> stringResource(R.string.codex_token_not_checked)
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (account.tokenStatus) {
                    CodexTokenStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
                    CodexTokenStatus.INVALID, CodexTokenStatus.EXPIRED -> MaterialTheme.colorScheme.error
                    CodexTokenStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            account.usage?.primary?.let {
                CodexUsageRow(
                    window = it,
                    fallbackName = stringResource(R.string.codex_five_hour_limit),
                )
            }
            account.usage?.secondary?.let {
                CodexUsageRow(
                    window = it,
                    fallbackName = stringResource(R.string.codex_weekly_limit),
                )
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.codex_refresh))
                }
                TextButton(onClick = onReauthenticate) {
                    Text(stringResource(R.string.codex_reauthenticate))
                }
                TextButton(onClick = { showDeleteConfirmation = true }) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodexUsageRow(
    window: CodexUsageWindow,
    fallbackName: String,
) {
    val remaining = (100.0 - window.usedPercent).coerceIn(0.0, 100.0)
    val name = when (window.windowMinutes) {
        300L -> stringResource(R.string.codex_five_hour_limit)
        10_080L -> stringResource(R.string.codex_weekly_limit)
        43_200L -> stringResource(R.string.codex_monthly_limit)
        null -> fallbackName
        else -> stringResource(R.string.codex_minute_limit, window.windowMinutes)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.codex_percent_remaining, remaining.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { (remaining / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        window.resetsAt?.let { epochSeconds ->
            Text(
                text = stringResource(
                    R.string.codex_resets_at,
                    RESET_FORMAT.format(Instant.ofEpochSecond(epochSeconds)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val RESET_FORMAT: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
