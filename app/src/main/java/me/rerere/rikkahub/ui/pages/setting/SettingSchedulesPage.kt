package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.ui.components.ai.ScheduleExecutionSettings
import me.rerere.rikkahub.ui.components.ai.rememberScheduleExecutionSettings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingSchedulesPage() {
    val repository = koinInject<ScheduledJobRepository>()
    val access = rememberScheduleExecutionSettings()
    val nav = LocalNavController.current
    val settings = LocalSettings.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val jobsState by produceState(ScheduledJobsState(), repository, revision) {
        value = try {
            val summaries = withContext(Dispatchers.IO) {
                repository.active().sortedWith(compareBy<ScheduledJobEntity> { it.nextRunAtMs ?: Long.MAX_VALUE }.thenByDescending { it.createdAtMs }).map { job ->
                    val name = runCatching { repository.payload(job).name }.getOrNull()
                    ScheduledJobSummary(job, name)
                }
            }
            ScheduledJobsState(summaries, loading = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ScheduledJobsState(loading = false, unavailable = true)
        }
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Расписания и фоновые задачи") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Доступ для фонового выполнения", style = MaterialTheme.typography.titleMedium)
                        ScheduleExecutionSettings(access)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Активные задания", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { revision++ }) { Text("Обновить список") }
                }
            }
            if (jobsState.loading) {
                item { Text("Загружаю задания…") }
            } else if (jobsState.unavailable) {
                item { Text("Список заданий недоступен") }
            } else if (jobsState.jobs.isEmpty()) {
                item { Text("Активных заданий пока нет") }
            }
            items(jobsState.jobs, key = { it.job.id }) { summary ->
                val job = summary.job
                val owner = settings.assistants.firstOrNull { it.id.toString() == job.ownerAssistantId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(summary.name?.takeIf { it.isNotBlank() } ?: "Задание ${job.id.take(8)}", style = MaterialTheme.typography.titleMedium)
                        Text("Ассистент: ${owner?.name?.ifBlank { "По умолчанию" } ?: "Недоступен"}", style = MaterialTheme.typography.bodySmall)
                        Text(job.nextRunAtMs?.let { "Следующий запуск: ${dateFormat.format(Date(it))}" } ?: "Следующий запуск не назначен")
                        Text(if (job.scheduleType == "cron") "Расписание: ${job.cronExpression.orEmpty()} · ${job.timezone}" else "Однократное задание · ${job.timezone}", style = MaterialTheme.typography.bodySmall)
                        Text("Выполнений: ${job.totalRuns} · успешно: ${job.totalSucceeded}", style = MaterialTheme.typography.bodySmall)
                        if (owner != null) TextButton(onClick = { nav.navigate(Screen.AssistantLocalTool(owner.id.toString())) }) {
                            Text("Инструменты ассистента")
                        }
                    }
                }
            }
        }
    }
}

private data class ScheduledJobSummary(val job: ScheduledJobEntity, val name: String?)

private data class ScheduledJobsState(
    val jobs: List<ScheduledJobSummary> = emptyList(),
    val loading: Boolean = true,
    val unavailable: Boolean = false,
)
