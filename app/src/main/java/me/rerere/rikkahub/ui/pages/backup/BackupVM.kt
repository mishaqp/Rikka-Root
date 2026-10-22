package me.rerere.rikkahub.ui.pages.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.mergeImportedProviders
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.utils.UiState
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import me.rerere.rikkahub.data.sync.importer.ChatboxPartialImportException
import me.rerere.rikkahub.data.sync.importer.ChatboxStreamingImportResult

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)
    val localBackupItems = MutableStateFlow(WebDavConfig.BackupItem.entries.toList())

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(transform: (Settings) -> Settings) = viewModelScope.launch {
        settingsStore.update(transform)
    }

    fun updateBackupReminder(transform: (me.rerere.rikkahub.data.datastore.BackupReminderConfig) -> me.rerere.rikkahub.data.datastore.BackupReminderConfig) =
        updateSettings { current -> current.copy(backupReminderConfig = transform(current.backupReminderConfig)) }

    fun updateLocalBackupItems(items: List<WebDavConfig.BackupItem>) {
        localBackupItems.value = items
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToDocument(context: Context, targetUri: Uri) {
        val exportFile = exportToFile()
        try {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(targetUri)
                    ?: throw IOException("Не удалось открыть выбранный файл для записи.")
                output.use { outputStream ->
                    FileInputStream(exportFile).use { inputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = inputStream.read(buffer)
                            if (count < 0) break
                            outputStream.write(buffer, 0, count)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            recordBackupTime()
            withContext(NonCancellable + Dispatchers.IO) { exportFile.delete() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IOException("Не удалось завершить экспорт. Временный архив сохранён: ${exportFile.absolutePath}", error)
        }
    }

    suspend fun exportToFile(): File {
        val file = webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = localBackupItems.value)
        )
        return file
    }

    suspend fun restoreFromLocalFile(file: File) {
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.webDavConfig.copy(items = localBackupItems.value),
        )
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.value
        var importedConversations = 0
        var skippedExistingConversations = 0
        var savedHasSystemPrompt = false
        var completed: ChatboxStreamingImportResult? = null
        suspend fun commitSettings(result: ChatboxStreamingImportResult?) {
            settingsStore.update { latestSettings ->
                latestSettings.copy(
                    providers = if (result != null) mergeImportedProviders(latestSettings.providers, result.providers) else latestSettings.providers,
                    assistants = latestSettings.assistants.map { assistant ->
                        if (savedHasSystemPrompt && assistant.id == currentSettings.assistantId) {
                            assistant.copy(allowConversationSystemPrompt = true)
                        } else assistant
                    },
                )
            }
        }
        val result = try {
            ChatboxImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            providers = currentSettings.providers,
            shouldImportConversation = { conversationId ->
                val exists = conversationRepository.existsConversationById(conversationId)
                if (exists) skippedExistingConversations++
                !exists
            },
            saveImage = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation)
                importedConversations++
                savedHasSystemPrompt = savedHasSystemPrompt || !conversation.customSystemPrompt.isNullOrBlank()
            }
            ).also { completed = it; commitSettings(it) }
        } catch (error: Exception) {
            val partial = (error as? ChatboxPartialImportException)?.partial ?: completed
            var settingsFailure: Exception? = null
            withContext(NonCancellable) {
                // Retain imported files: a failed/cancelled insert can have committed before returning.
                if (importedConversations > 0) {
                    try { commitSettings(partial) } catch (failure: Exception) { settingsFailure = failure; error.addSuppressed(failure) }
                }
            }
            if (error is CancellationException) throw error
            if (importedConversations == 0) throw IOException("Не удалось импортировать чаты Chatbox. Существующие данные сохранены.", error)
            val settingsWarning = if (settingsFailure == null) "Настройки сохранённых чатов применены."
                else "Связанные настройки сохранить не удалось. Проверьте провайдеров и разрешение системного промпта перед продолжением."
            throw IOException("Импорт Chatbox выполнен частично: сохранено $importedConversations чатов, пропущено $skippedExistingConversations существующих. $settingsWarning Существующие чаты сохранены.", error)
        }

        Log.i(
            TAG,
            "Импорт Chatbox: провайдеров ${result.providers.size}, " +
                "чатов $importedConversations, пропущено существующих $skippedExistingConversations, " +
                "изображений ${result.importedImageParts}, пропущено изображений ${result.skippedImageParts}, " +
                "пропущено ответвлений ${result.skippedForkMessages} и сессий ${result.skippedSessions}"
        )
        ChatboxRestoreResult(
            importedProviders = result.providers.count { imported -> currentSettings.providers.none { it.id == imported.id } },
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            importedImageParts = result.importedImageParts,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
            skippedForkMessages = result.skippedForkMessages,
            skippedSessions = result.skippedSessions,
        )
    }

    suspend fun restoreFromCherryStudio(file: File) = withContext(Dispatchers.IO) {
        try {
            val importProviders = CherryStudioProviderImporter.importProviders(file)
            require(importProviders.isNotEmpty()) { "В архиве Cherry Studio нет провайдеров для импорта." }
            currentCoroutineContext().ensureActive()
            settingsStore.update { current -> current.copy(providers = importProviders + current.providers) }
            Log.i(TAG, "Импорт Cherry Studio завершён: провайдеров ${importProviders.size}")
            Unit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IOException("Не удалось завершить импорт настроек Cherry Studio. Повторите импорт перед перезапуском.", error)
        }
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
)
