package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.BackgroundStatus
import org.koin.compose.koinInject
import java.time.Instant

@Composable
fun WorkspaceProcessesPanel(id: String, isActive: Boolean) {
    val repository: WorkspaceRepository = koinInject()
    val scope = rememberCoroutineScope()
    var command by remember(id) { mutableStateOf("") }
    var cwd by remember(id) { mutableStateOf("") }
    var processes by remember(id) { mutableStateOf(emptyList<BackgroundStatus>()) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember(id) { mutableStateOf(false) }
    var pending by remember(id) { mutableStateOf<Pair<String, String>?>(null) }
    var output by remember(id) { mutableStateOf<BackgroundStatus?>(null) }

    suspend fun refresh() {
        try { processes = repository.backgroundProcesses(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Не удалось получить список процессов" }
    }

    LaunchedEffect(id, isActive) {
        if (isActive) while (true) { refresh(); delay(1_000) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Фоновые процессы workspace", style = MaterialTheme.typography.titleMedium)
                Text("До 5 процессов одновременно. Они завершаются при выходе процесса приложения или удалении workspace. Вывод хранится в памяти: последние 32768 символов каждого потока.")
                OutlinedTextField(command, { command = it }, label = { Text("Команда") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cwd, { cwd = it }, label = { Text("Каталог относительно workspace") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { pending = command to cwd }, enabled = command.isNotBlank() && !busy) { Text("Запустить") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (processes.isEmpty()) Text("Управляемых процессов пока нет")
            }
        }
        items(processes, key = { it.id }) { process ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(process.command, fontFamily = FontFamily.Monospace)
                    Text("${process.id}\n${Instant.ofEpochMilli(process.startedAtMillis)} · ${process.cwd.ifBlank { "/workspace" }}")
                    Text(if (process.running) "Выполняется" else "Завершён · код ${process.exitCode}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { output = process }) { Text("Вывод") }
                        TextButton(enabled = process.running && !busy, onClick = {
                            busy = true
                            scope.launch {
                                try { repository.stopBackground(id, process.id); refresh() }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message ?: "Остановка не подтверждена" }
                                finally { busy = false }
                            }
                        }) { Text("Остановить") }
                    }
                }
            }
        }
    }

    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Запустить фоновую команду?") },
            text = { Text("${request.first}\nКаталог: ${request.second.ifBlank { "/workspace" }}", fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = {
                pending = null; busy = true; error = null
                scope.launch {
                    try { repository.startBackground(id, request.first, request.second); command = ""; refresh() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Запуск не удался" }
                    finally { busy = false }
                }
            }) { Text("Разрешить") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Отклонить") } },
        )
    }
    output?.let { selected ->
        val current = processes.firstOrNull { it.id == selected.id } ?: selected
        AlertDialog(
            onDismissRequest = { output = null }, title = { Text("Вывод процесса") },
            text = {
                Text("Код: ${current.exitCode ?: "выполняется"}\nstdout (пропущено ${current.droppedStdout} символов):\n${current.stdout}\n\nstderr (пропущено ${current.droppedStderr} символов):\n${current.stderr}",
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace)
            },
            confirmButton = { TextButton(onClick = { output = null }) { Text("Закрыть") } },
        )
    }
}
