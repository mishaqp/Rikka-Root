package me.rerere.rikkahub.workflow.trigger

import android.app.Application
import android.content.Context
import androidx.room.Room
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowAction
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class WorkflowLookupRecoveryPart3Test {
    private lateinit var context: Context
    private lateinit var database: WorkflowDatabase
    private lateinit var repository: WorkflowRepository
    private lateinit var store: WorkflowTimeCronOccurrenceStore
    private var fault: Exception? = null
    private var now = 1_800_000_000_000L
    private var effects = 0
    private var cancellations = 0
    private val definition = WorkflowDefinition("lookup-part3", "Проверка восстановления",
        trigger = TriggerSpec.TimeCron(cron = "@every 1m", timezone = "UTC"),
        actions = listOf(WorkflowAction("show_toast", buildJsonObject {})),
        createdAtMs = 0L, updatedAtMs = 7L)
    private val scheduling = object : WorkflowTimeCronScheduling {
        override fun schedule(workflowId: String, occurrence: WorkflowTimeCronOccurrence) = Unit
        override fun cancelTimer(workflowId: String, occurrence: WorkflowTimeCronOccurrence) { cancellations++ }
        override fun cancelAll(workflowId: String) { cancellations++ }
    }

    @Before fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("workflow_timecron_occurrences", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, WorkflowDatabase::class.java).allowMainThreadQueries().build()
        val actual = database.workflowDao()
        val dao = object : WorkflowDao by actual {
            override suspend fun getById(id: String): WorkflowEntity? {
                fault?.let { throw it }
                return actual.getById(id)
            }
        }
        repository = WorkflowRepository(dao, database.workflowRunDao(), database) { it }
        repository.upsert(definition)
        startKoin { modules(module { single { repository } }) }
        store = WorkflowTimeCronOccurrenceStore(context)
    }
    @After fun cleanup() { stopKoin(); database.close() }
    private fun family() = TimeCronTriggerFamily(context, CoroutineScope(Dispatchers.Default), scheduling, store, { now })
    private suspend fun armed(): Pair<TimeCronTriggerFamily, WorkflowTimeCronOccurrence> {
        val family = family()
        family.sync(listOf(definition), TriggerFireCallback { _, _ -> effects++ })
        cancellations = 0
        val due = requireNotNull(store.get(definition.id)); now = due.atMillis
        return family to due
    }

    @Test fun transientRoomLookupFailurePreservesDueIdentityAndHealthyRetryExecutesExactlyOnce() = runBlocking {
        val (family, due) = armed()
        fault = IOException("temporary database failure")
        val failure = runCatching { family.onWorkerFired(definition.id, due.atMillis, due.revision) }.exceptionOrNull()
        assertEquals("Ошибка чтения не должна становиться удалением occurrence", due, store.get(definition.id))
        assertEquals(0, cancellations); assertEquals(0, effects)
        assertTrue("Ошибка должна достигнуть retry boundary", failure is IOException)
        fault = null
        assertTrue(family.onWorkerFired(definition.id, due.atMillis, due.revision))
        assertFalse(family.onWorkerFired(definition.id, due.atMillis, due.revision))
        assertEquals(1, effects)
    }

    @Test fun parentCancellationFromRoomIsPropagatedWithoutDeletingDueIdentity() = runBlocking {
        val (family, due) = armed()
        fault = CancellationException("parent stopped")
        val failure = runCatching { family.onWorkerFired(definition.id, due.atMillis, due.revision) }.exceptionOrNull()
        assertEquals("Отмена родителя не должна удалять durable timer", due, store.get(definition.id))
        assertEquals(0, cancellations); assertEquals(0, effects)
        assertTrue("Отмена должна остаться отменой", failure is CancellationException)
    }

    @Test fun unreadableLiveWorkflowPreservesOccurrenceUntilItsStoredDefinitionIsRepaired() = runBlocking {
        val (family, due) = armed()
        val original = requireNotNull(database.workflowDao().getById(definition.id))
        database.workflowDao().upsert(original.copy(definitionJson = "{broken"))

        val failure = runCatching { family.onWorkerFired(definition.id, due.atMillis, due.revision) }.exceptionOrNull()

        assertEquals("Повреждённая запись существует и не должна удалять расписание", due, store.get(definition.id))
        assertEquals(0, cancellations)
        assertEquals(0, effects)
        assertNotNull("Ошибка чтения определения должна достигнуть retry boundary", failure)
        database.workflowDao().upsert(original)
        assertTrue(family.onWorkerFired(definition.id, due.atMillis, due.revision))
        assertFalse(family.onWorkerFired(definition.id, due.atMillis, due.revision))
        assertEquals(1, effects)
    }

    @Test fun unavailableDependencyContainerDoesNotBecomeMissingWorkflow() = runBlocking {
        val (family, due) = armed()
        stopKoin()
        val failure = runCatching { family.onWorkerFired(definition.id, due.atMillis, due.revision) }.exceptionOrNull()
        assertEquals(due, store.get(definition.id)); assertEquals(0, cancellations); assertEquals(0, effects)
        assertNotNull("Сбой DI должен достигнуть retry boundary", failure)
    }

    @Test fun trulyMissingLiveWorkflowStillCancelsDueIdentityWithoutEffects() = runBlocking {
        val (family, due) = armed()
        database.workflowDao().deleteById(definition.id)
        assertFalse(family.onWorkerFired(definition.id, due.atMillis, due.revision))
        assertNull(store.get(definition.id)); assertTrue(cancellations > 0); assertEquals(0, effects)
    }

    @Test fun explicitlyDisabledWorkflowStillCancelsEvenIfItsStoredDefinitionIsUnreadable() = runBlocking {
        val (family, due) = armed()
        val original = requireNotNull(database.workflowDao().getById(definition.id))
        database.workflowDao().upsert(original.copy(enabled = false, definitionJson = "{broken"))
        assertFalse(family.onWorkerFired(definition.id, due.atMillis, due.revision))
        assertNull(store.get(definition.id))
        assertTrue(cancellations > 0)
        assertEquals(0, effects)
    }
}
