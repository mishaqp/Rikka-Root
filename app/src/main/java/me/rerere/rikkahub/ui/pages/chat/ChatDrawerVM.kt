package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.drawerConversationQuery
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.isLegacyConversationTitle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class ChatDrawerVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val chatService: ChatService,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val assistantIdFlow = settingsStore.settingsFlow
        .map { it.assistantId }
        .distinctUntilChanged()

    private val sortOrderFlow = settingsStore.settingsFlow
        .map { it.displaySetting.conversationSortOrder }
        .distinctUntilChanged()

    // Presentation-only All filter; null folder still preserves the Unfiled view.
    private val _selectedFolderId = MutableStateFlow<Uuid?>(null)
    val selectedFolderId: StateFlow<Uuid?> = _selectedFolderId.asStateFlow()
    private val _showAllFolders = MutableStateFlow(true)
    val showAllFolders: StateFlow<Boolean> = _showAllFolders.asStateFlow()
    private val _presentationSort = MutableStateFlow(
        DrawerConversationSort.entries.firstOrNull {
            it.name == savedStateHandle.get<String>("drawerPresentationSort")
        } ?: DrawerConversationSort.NEWEST
    )
    val presentationSort: StateFlow<DrawerConversationSort> = _presentationSort.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _openOnNextChat = MutableStateFlow<Boolean>(savedStateHandle["drawerOpenOnNextChat"] ?: false)
    val openOnNextChat: StateFlow<Boolean> = _openOnNextChat.asStateFlow()
    var assistantPickerExpanded: Boolean
        get() = savedStateHandle["drawerAssistantPickerExpanded"] ?: false
        set(value) { savedStateHandle["drawerAssistantPickerExpanded"] = value }
    fun requestOpenOnNextChat(origin: Uuid) {
        savedStateHandle["drawerOpenOrigin"] = origin.toString()
        savedStateHandle["drawerOpenOnNextChat"] = true
        _openOnNextChat.value = true
    }
    fun hasOpenRequestFor(chatId: Uuid): Boolean =
        _openOnNextChat.value && savedStateHandle.get<String>("drawerOpenOrigin") != chatId.toString()
    fun consumeOpenRequest() {
        savedStateHandle["drawerOpenOnNextChat"] = false
        _openOnNextChat.value = false
    }
    fun setSearchQuery(query: String) { _searchQuery.value = query }
    private val dateRefresh = MutableStateFlow(0)
    private val loadedLegacyTitles = ConcurrentHashMap<Uuid, String>()
    private val pendingLegacyTitleBackfills = ConcurrentHashMap.newKeySet<Uuid>()
    private val legacyTitleBackfillMutex = Mutex()

    private data class DrawerDateContext(val today: LocalDate, val zone: ZoneId)
    private val dateContext = dateRefresh.flatMapLatest {
        flow {
            while (true) {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                emit(DrawerDateContext(today, zone))
                val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant()
                delay(Duration.between(Instant.now(), tomorrow).toMillis().coerceAtLeast(1L))
            }
        }
    }.distinctUntilChanged()

    private data class DrawerQueryOptions(
        val assistantId: Uuid,
        val folderId: Uuid?,
        val allFolders: Boolean,
        val dateBasis: ConversationSortOrder,
        val sort: DrawerConversationSort,
        val query: String = "",
    )

    // 当前助手的文件夹列表（Room Flow，增删改自动刷新）
    val folders: StateFlow<List<Folder>> = assistantIdFlow
        .flatMapLatest { folderRepo.getFoldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(assistantIdFlow, _selectedFolderId, _showAllFolders, sortOrderFlow, _presentationSort) {
                assistantId, folderId, allFolders, dateBasis, sort ->
            DrawerQueryOptions(assistantId, folderId, allFolders, dateBasis, sort)
        }
            .combine(_searchQuery) { options, query -> options.copy(query = query.trim()) }
            .combine(dateContext) { options, dates -> options to dates }
            .flatMapLatest { (options, dates) ->
                val paging = conversationRepo.getDrawerConversationsPaging(drawerConversationQuery(
                    assistantId = options.assistantId.toString(),
                    folderId = options.folderId?.toString(),
                    includeAllFolders = options.allFolders,
                    sortByCreateTime = options.dateBasis == ConversationSortOrder.CREATE_TIME,
                    oldestFirst = options.sort == DrawerConversationSort.OLDEST,
                    alphabetical = options.sort == DrawerConversationSort.TITLE,
                    todayMillis = dates.today.atStartOfDay(dates.zone).toInstant().toEpochMilli(),
                    yesterdayMillis = dates.today.minusDays(1).atStartOfDay(dates.zone).toInstant().toEpochMilli(),
                    weekStartMillis = drawerWeekStart(dates.today).atStartOfDay(dates.zone).toInstant().toEpochMilli(),
                    includePreviews = conversationRepo.drawerSupportsMessagePreviews(),
                    searchQuery = options.query,
                ))
                paging.map { pagingData ->
                    pagingData
                        .map { summary ->
                            backfillLoadedLegacyTitle(summary.conversation)
                            ConversationListItem.Item(
                                conversation = summary.conversation,
                                previewText = summary.previewText,
                                hasTools = summary.hasTools,
                                hasWorkflows = summary.hasWorkflows,
                            )
                        }
                        .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                            drawerConversationHeader(before?.conversation, after?.conversation,
                                options.dateBasis, dates.today, dates.zone)
                        }
                }
            }
            .cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    init {
        // 助手切换时重置文件夹筛选，回到「聊天」视图，
        // 避免继续显示上一个助手文件夹内的会话（文件夹是助手内分组）
        viewModelScope.launch {
            assistantIdFlow.collect {
                loadedLegacyTitles.clear()
                _selectedFolderId.value = null
                _showAllFolders.value = true
            }
        }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    private fun backfillLoadedLegacyTitle(conversation: Conversation) {
        if (!isLegacyConversationTitle(conversation.title)) {
            loadedLegacyTitles.remove(conversation.id)
            return
        }
        loadedLegacyTitles[conversation.id] = conversation.title
        queueLegacyTitleBackfill(conversation.id, conversation.title)
    }

    private fun queueLegacyTitleBackfill(conversationId: Uuid, observedTitle: String) {
        if (!pendingLegacyTitleBackfills.add(conversationId)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Serialize history reads; first drawer rendering never waits for upkeep.
                legacyTitleBackfillMutex.withLock {
                    chatService.backfillLegacyConversationTitle(conversationId, observedTitle)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Error messages from history decoding can contain private message fragments.
                Log.w("ChatDrawerVM", "Не удалось обновить старое название: ${error.javaClass.simpleName}")
            } finally {
                // Skipped live sessions and failed reads remain eligible on later list loads.
                pendingLegacyTitleBackfills.remove(conversationId)
            }
        }
    }

    fun selectFolder(folderId: Uuid?) {
        _showAllFolders.value = false
        _selectedFolderId.value = folderId
    }

    fun selectAllFolders() {
        _showAllFolders.value = true
        _selectedFolderId.value = null
    }

    fun setPresentationSort(sort: DrawerConversationSort) {
        _presentationSort.value = sort
        savedStateHandle["drawerPresentationSort"] = sort.name
    }

    /** Refresh on reopening too: time-zone changes must not keep Activity-lived headers stale. */
    fun refreshDateGrouping() {
        dateRefresh.value += 1
        // cachedIn may reuse already mapped rows when the drawer reopens.
        loadedLegacyTitles.forEach { (id, title) -> queueLegacyTitleBackfill(id, title) }
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            folderRepo.createFolder(assistantId, trimmed)
        }
    }

    fun renameFolder(folderId: Uuid, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepo.renameFolder(folderId, trimmed)
        }
    }

    /**
     * 删除文件夹。若文件夹内有正在生成回复的会话，拒绝删除并返回 false（UI 层据此提示用户）。
     */
    fun deleteFolder(folderId: Uuid): Boolean {
        if (chatService.hasGeneratingConversationInFolder(folderId)) {
            return false
        }
        viewModelScope.launch {
            // 经 ChatService 删除：会同步清空活跃 session 内存态的 folderId，避免整对象保存写回已删文件夹
            chatService.deleteFolder(folderId)
            if (_selectedFolderId.value == folderId) {
                _selectedFolderId.value = null
            }
        }
        return true
    }

    fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        viewModelScope.launch {
            // 经 ChatService 移动：活跃会话会先同步内存态，避免后续整对象保存覆盖 folder_id
            chatService.moveConversationToFolder(conversationId, folderId)
        }
    }

}
