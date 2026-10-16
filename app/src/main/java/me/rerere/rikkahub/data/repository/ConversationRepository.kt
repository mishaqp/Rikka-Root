package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.AttachmentReferenceCoordinator
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.data.model.HistoryNotWritableException
import me.rerere.rikkahub.data.model.requireCompleteHistory
import me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer
import me.rerere.rikkahub.data.ssh.sanitizeSshToolMessages
import me.rerere.rikkahub.data.ai.mcp.control.McpToolSecretSanitizer
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    private val sshToolSecrets: SshToolSecretSanitizer? = null,
    private val mcpSecretSanitizer: McpToolSecretSanitizer? = null,
) {
    private val historyLoads = java.util.concurrent.ConcurrentHashMap<String, HistoryLoadState>()
    internal val attachmentReferences = AttachmentReferenceCoordinator()

    /** Also reject reconstructed snapshots of an ID whose latest load failed. */
    fun requireWritableHistory(conversation: Conversation) {
        conversation.requireCompleteHistory()
        if (historyLoads[conversation.id.toString()] == HistoryLoadState.INCOMPLETE) {
            throw HistoryNotWritableException(HistoryLoadState.INCOMPLETE)
        }
    }

    private fun requireWritableId(conversationId: Uuid) {
        if (historyLoads[conversationId.toString()] == HistoryLoadState.INCOMPLETE) {
            throw HistoryNotWritableException(HistoryLoadState.INCOMPLETE)
        }
    }

    private var drawerJsonFunctionsAvailable: Boolean? = null

    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl))

    /** Global cleanup for UI, assistant, Web and queue callers. Unknown references retain bytes. */
    internal suspend fun cleanupChatFiles(candidates: Collection<Uri>): Set<String> {
        val localFiles = candidates.filter { it.toString().startsWith("file://") }.distinct()
        if (localFiles.isEmpty()) return emptySet()
        return try {
            database.withTransaction {
                // The transaction excludes Room writers from the query/delete window.
                val unreferenced = localFiles.filterNot { hasFileReference(it.toString()) }
                currentCoroutineContext().ensureActive()
                attachmentReferences.deleteUnreferenced(unreferenced, filesManager::deleteChatFiles)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("ConversationRepository", "Failed to confirm attachment references; retaining files", error)
            emptySet()
        }
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes.nodes, nodes.state)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getDrawerConversationsPaging(
        query: SimpleSQLiteQuery,
    ): Flow<PagingData<DrawerConversationSummary>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { conversationDAO.getDrawerConversationsPaging(query) },
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            DrawerConversationSummary(
                conversation = conversationSummaryToConversation(LightConversationEntity(
                    id = entity.id, assistantId = entity.assistantId, title = entity.title,
                    isPinned = entity.isPinned, createAt = entity.createAt, updateAt = entity.updateAt,
                    folderId = entity.folderId,
                )),
                previewText = entity.previewText.replace(Regex("\\s+"), " ").trim(),
                hasTools = entity.hasTools,
                hasWorkflows = entity.hasWorkflows,
            )
        }
    }

    /** Older system SQLite builds may omit JSON1; the drawer still opens without previews. */
    suspend fun drawerSupportsMessagePreviews(): Boolean = withContext(Dispatchers.IO) {
        drawerJsonFunctionsAvailable ?: try {
            database.openHelper.readableDatabase.query("SELECT json_valid('[]')").use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) == 1
            }
        } catch (_: SQLiteException) {
            false
        }.also { drawerJsonFunctionsAvailable = it }
    }

    /** Rename metadata only; never write a lightweight drawer item over the full conversation. */
    suspend fun updateConversationTitle(conversationId: Uuid, title: String) {
        requireWritableId(conversationId)
        database.withTransaction {
            conversationDAO.updateTitle(conversationId.toString(), title)
            messageFtsManager.updateConversationTitle(conversationId.toString(), title)
        }
    }

    /** The loaded drawer row may be stale after a manual or background AI rename. */
    suspend fun backfillConversationTitle(conversationId: Uuid, expectedTitle: String, title: String): Boolean {
        requireWritableId(conversationId)
        return conversationDAO.updateTitleIfUnchanged(conversationId.toString(), expectedTitle, title) == 1
    }

    /** Background results own only the title, including its duplicated search metadata. */
    suspend fun updateGeneratedConversationTitle(conversationId: Uuid, expectedTitle: String, title: String): Boolean {
        requireWritableId(conversationId)
        return database.withTransaction {
            val changed = conversationDAO.updateTitleIfUnchanged(conversationId.toString(), expectedTitle, title) == 1
            if (changed) messageFtsManager.updateConversationTitle(conversationId.toString(), title)
            changed
        }
    }

    /** A late result must neither rewrite nodes nor recreate a deleted row. */
    suspend fun updateConversationSuggestions(conversationId: Uuid, suggestions: List<String>): Boolean {
        requireWritableId(conversationId)
        return conversationDAO.updateSuggestions(conversationId.toString(), JsonInstant.encodeToString(suggestions)) == 1
    }

    fun getUnfiledConversationsOfAssistantPaging(
        assistantId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getUnfiledConversationsOfAssistantPaging(
                assistantId = assistantId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(
        folderId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getConversationsOfFolderPaging(
                folderId = folderId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes.nodes, nodes.state)
        } else null
    }

    /** Startup-only prefilter for old result cards from versions without a recovery journal. */
    internal suspend fun findInterruptedBackgroundChats(): Set<Uuid> = withContext(Dispatchers.IO) {
        val names = listOf("workspace_background_start", "workspace_background_list", "workspace_background_output",
            "workspace_background_stop", "subagent_dispatch", "subagent_list", "subagent_get")
        val toolFields = names.map { JsonInstant.encodeToString("toolName") + ":" + JsonInstant.encodeToString(it) }
        // The native result JSON is nested inside UIMessagePart.Text, so its quotes are escaped.
        val states = listOf("""\"running\":true""", """\"running\": true""",
            """\"status\":\"RUNNING\"""", """\"status\": \"RUNNING\"""",
            """\"status\":\"PENDING\"""", """\"status\": \"PENDING\"""")
        val toolsWhere = toolFields.joinToString(" OR ") { "instr(messages, ?) > 0" }
        val statesWhere = states.joinToString(" OR ") { "instr(messages, ?) > 0" }
        val query = SimpleSQLiteQuery("SELECT DISTINCT conversation_id FROM message_node WHERE ($toolsWhere) AND ($statesWhere)",
            (toolFields + states).toTypedArray())
        database.openHelper.readableDatabase.query(query).use { cursor ->
            buildSet { while (cursor.moveToNext()) runCatching { Uuid.parse(cursor.getString(0)) }.getOrNull()?.let(::add) }
        }
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun countConversationsByAssistant(): Map<Uuid, Int> {
        return conversationDAO.countByAssistant().mapNotNull { row ->
            runCatching { Uuid.parse(row.assistantId) }.getOrNull()?.let { it to row.count }
        }.toMap()
    }

    suspend fun insertConversation(conversation: Conversation) {
        requireWritableHistory(conversation)
        val safeConversation = sanitizeSshConversation(conversation, sshToolSecrets, mcpSecretSanitizer)
        attachmentReferences.withFileReservation(safeConversation.files.map { it.toString() }) {
            database.withTransaction {
                conversationDAO.insert(
                    conversationToConversationEntity(safeConversation)
                )
                saveMessageNodes(safeConversation.id.toString(), safeConversation.messageNodes)
                attachmentReferences.conversationWritten(safeConversation.id)
            }
            messageFtsManager.indexConversation(safeConversation)
        }
    }

    suspend fun updateConversation(conversation: Conversation) {
        requireWritableHistory(conversation)
        val safeConversation = sanitizeSshConversation(conversation, sshToolSecrets, mcpSecretSanitizer)
        attachmentReferences.withFileReservation(safeConversation.files.map { it.toString() }) {
            database.withTransaction {
                conversationDAO.update(
                    conversationToConversationEntity(safeConversation)
                )
                // 删除旧的节点，插入新的节点
                messageNodeDAO.deleteByConversation(conversation.id.toString())
                saveMessageNodes(safeConversation.id.toString(), safeConversation.messageNodes)
                attachmentReferences.conversationWritten(safeConversation.id)
            }
            messageFtsManager.indexConversation(safeConversation)
        }
    }

    /** Streaming checkpoint: keep the existing rows and serialize only changed nodes; index at final save. */
    internal suspend fun checkpointGeneratedNodes(conversation: Conversation, previousNodes: List<MessageNode>?) = withContext(Dispatchers.IO) {
        requireWritableHistory(conversation)
        val safe = sanitizeSshConversation(conversation, sshToolSecrets, mcpSecretSanitizer)
        val previous = previousNodes.orEmpty().withIndex().associateBy { it.value.id }
        val retainedIds = safe.messageNodes.mapTo(hashSetOf()) { it.id }
        attachmentReferences.withFileReservation(safe.files.map { it.toString() }) {
            database.withTransaction {
                conversationDAO.update(conversationToConversationEntity(safe))
                if (previousNodes == null) {
                    // The first chunk can include repairs not yet committed to Room. Replace
                    // those rows transactionally, but defer the derived search index to final save.
                    messageNodeDAO.deleteByConversation(safe.id.toString())
                } else for (removed in previousNodes) if (removed.id !in retainedIds) messageNodeDAO.deleteById(removed.id.toString())
                val changed = safe.messageNodes.mapIndexedNotNull { index, node ->
                    val old = previous[node.id]
                    if (old?.index == index && old.value == node) null else MessageNodeEntity(
                        id = node.id.toString(), conversationId = safe.id.toString(), nodeIndex = index,
                        messages = JsonInstant.encodeToString(node.messages), selectIndex = node.selectIndex)
                }
                if (changed.isNotEmpty()) messageNodeDAO.insertAll(changed)
                attachmentReferences.conversationWritten(safe.id)
            }
        }
    }

    suspend fun deleteConversation(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.historyLoadState == HistoryLoadState.NOT_LOADED) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        requireWritableHistory(fullConversation)
        messageFtsManager.deleteConversation(conversation.id.toString())
        try {
            database.withTransaction {
                // message_node 会通过 CASCADE 自动删除
                conversationDAO.delete(
                    conversationToConversationEntity(conversation)
                )
                // A lingering open session is no longer a history reference, but its queue is.
                attachmentReferences.conversationDeleted(conversation.id)
            }
        } catch (error: Throwable) {
            attachmentReferences.conversationWritten(conversation.id)
            throw error
        }
        cleanupChatFiles(fullConversation.files)
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: Uuid? = null,
    ) = messageFtsManager.search(keyword, sort, assistantId?.toString())

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        // Validate every snapshot before clearing the old complete search index.
        val conversations = conversationDAO.getAllIds().mapNotNull { id ->
            getConversationById(Uuid.parse(id))
        }
        conversations.forEach(::requireWritableHistory)
        messageFtsManager.deleteAll()
        conversations.forEachIndexed { index, conversation ->
            messageFtsManager.indexConversation(sanitizeSshConversation(conversation, sshToolSecrets, mcpSecretSanitizer))
            onProgress(index + 1, conversations.size)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        val conversations = getConversationsOfAssistant(assistantId).first().mapNotNull { getConversationById(it.id) }
        conversations.forEach(::requireWritableHistory)
        conversations.forEach { deleteConversation(it) }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            config = conversation.config?.let { JsonInstant.encodeToString(it) } ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>,
        historyLoadState: HistoryLoadState = HistoryLoadState.NOT_LOADED,
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            historyLoadState = historyLoadState,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            config = conversationEntity.config.ifEmpty { null }?.let { JsonInstant.decodeFromString(it) },
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun updatePinStatus(conversationId: Uuid, isPinned: Boolean) {
        requireWritableId(conversationId)
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = isPinned,
        )
    }

    suspend fun updateConversationAssistant(conversationId: Uuid, assistantId: Uuid) {
        requireWritableId(conversationId)
        conversationDAO.updateAssistantId(conversationId.toString(), assistantId.toString())
    }

    /**
     * 只更新会话持有的配置（含注入绑定和工作目录），不重写消息节点。
     */
    suspend fun updateConversationConfig(conversation: Conversation) {
        requireWritableHistory(conversation)
        conversationDAO.updateConfig(
            id = conversation.id.toString(),
            config = conversation.config?.let { JsonInstant.encodeToString(it) } ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        requireWritableId(conversationId)
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            historyLoadState = HistoryLoadState.NOT_LOADED,
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private data class LoadedHistory(val nodes: List<MessageNode>, val state: HistoryLoadState)

    private suspend fun loadMessageNodes(conversationId: String): LoadedHistory {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            var loadState = HistoryLoadState.COMPLETE
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SQLiteBlobTooBigException) {
                    android.util.Log.w("ConversationRepository", "History page could not be loaded at offset $offset", e)
                    loadState = HistoryLoadState.INCOMPLETE
                    break
                } catch (e: IllegalStateException) {
                    android.util.Log.w("ConversationRepository", "History page could not be loaded at offset $offset", e)
                    loadState = HistoryLoadState.INCOMPLETE
                    break
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            LoadedHistory(nodes, loadState).also { historyLoads[conversationId] = it.state }
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(sanitizeSshToolMessages(node.messages,
                    sanitizePrivateToolArgs(sshToolSecrets, mcpSecretSanitizer))),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }
}

internal fun sanitizeSshConversation(conversation: Conversation, secrets: SshToolSecretSanitizer?,
    mcpSecrets: McpToolSecretSanitizer? = null): Conversation =
    conversation.copy(messageNodes = conversation.messageNodes.map { node ->
        node.copy(messages = sanitizeSshToolMessages(node.messages,
            sanitizePrivateToolArgs(secrets, mcpSecrets)))
    })

private fun sanitizePrivateToolArgs(ssh: SshToolSecretSanitizer?, mcp: McpToolSecretSanitizer?): (String, String) -> String =
    { name, input ->
        me.rerere.rikkahub.data.ai.tools.protectToolArguments(name, input, ssh, mcp)
    }

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
