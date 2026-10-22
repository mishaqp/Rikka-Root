package me.rerere.rikkahub.ui.pages.imggen

import android.app.Application
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ImgGenIoPart3Test {
    @Test fun generationWritesPreviewAndFinalOffMainAndPublishesOnMain() = runBlocking { exercise(edit = false) }
    @Test fun editWritesPreviewAndFinalOffMainAndPublishesOnMain() = runBlocking { exercise(edit = true) }

    @Test fun cancellingAfterPreviewRemovesTemporaryFileWithoutPublishingFinalMedia() = runBlocking {
        val emitted = CompletableDeferred<Unit>()
        val rig = Rig(flow { emit(item("preview", partial = true)); emitted.complete(Unit); awaitCancellation() })
        try {
            withContext(Dispatchers.Main) { rig.vm.updatePrompt("Generate"); rig.vm.generateImage() }
            emitted.await()
            val generation = ReflectionHelpers.getField<Job>(rig.vm, "cancelJob")
            val preview = rig.files.single()
            assertTrue(preview.exists())
            withContext(Dispatchers.Main) { rig.vm.startNewSession() }
            generation.join()
            assertFalse("Cancelled preview must be cleaned after its IO completes", preview.exists())
            assertTrue(rig.rows.isEmpty())
            assertTrue(rig.vm.currentGeneratedImages.value.isEmpty())
            assertNull(rig.vm.error.value)
        } finally { rig.close() }
    }

    @Test fun cancellationDuringFinalWriteRemovesUnpublishedBytes() = runBlocking {
        val written = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val rig = Rig(flowOf(item("final", false)), afterWrite = { file, main ->
            written.countDown()
            if (Thread.currentThread() !== main) check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(file.exists())
        })
        try {
            withContext(Dispatchers.Main) { rig.vm.updatePrompt("Создать"); rig.vm.generateImage() }
            assertTrue(written.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val generation = ReflectionHelpers.getField<Job>(rig.vm, "cancelJob")
            withContext(Dispatchers.Main) { rig.vm.cancelGeneration() }
            release.countDown()
            generation.join()
            assertTrue("До публикации в БД отмена удаляет файл", rig.files.none { it.exists() })
            assertTrue(rig.rows.isEmpty())
            assertTrue(rig.vm.currentGeneratedImages.value.isEmpty())
        } finally { release.countDown(); rig.close() }
    }

    @Test fun cancellationDuringFinalCommitKeepsCommittedBytesAndMetadataTogether() = runBlocking {
        val inserting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rig = Rig(flowOf(item("final", false)), beforeInsert = {
            inserting.complete(Unit)
            release.await()
        })
        try {
            withContext(Dispatchers.Main) { rig.vm.updatePrompt("Создать"); rig.vm.generateImage() }
            inserting.await()
            val generation = ReflectionHelpers.getField<Job>(rig.vm, "cancelJob")
            withContext(Dispatchers.Main) { rig.vm.startNewSession() }
            release.complete(Unit)
            generation.join()
            assertEquals("Начатый commit должен завершиться", 1, rig.rows.size)
            assertEquals("final", rig.files.single().readText())
            assertEquals("images/${rig.files.single().name}", rig.rows.single().path)
            assertTrue("Отмена не публикует картинку в новую сессию", rig.vm.currentGeneratedImages.value.isEmpty())
        } finally { release.complete(Unit); rig.close() }
    }

    @Test fun failedFinalInsertRemovesBytesWithoutPublishingMedia() = runBlocking {
        val rig = Rig(flowOf(item("final", false)), beforeInsert = { throw java.io.IOException("Ошибка записи") })
        try {
            withContext(Dispatchers.Main) { rig.vm.updatePrompt("Создать"); rig.vm.generateImage() }
            ReflectionHelpers.getField<Job>(rig.vm, "cancelJob").join()
            assertEquals(1, rig.files.size)
            assertFalse("Ошибка insert не оставляет бесхозный файл", rig.files.single().exists())
            assertTrue(rig.rows.isEmpty())
            assertTrue(rig.vm.currentGeneratedImages.value.isEmpty())
            assertNotNull(rig.vm.error.value)
        } finally { rig.close() }
    }

    private suspend fun exercise(edit: Boolean) {
        val rig = Rig(flowOf(item("preview", true), item("final", false)))
        val publications = CopyOnWriteArrayList<Thread>()
        val observer = CoroutineScope(Dispatchers.Unconfined).launch {
            rig.vm.currentGeneratedImages.collect { if (it.isNotEmpty()) publications.add(Thread.currentThread()) }
        }
        try {
            withContext(Dispatchers.Main) {
                rig.vm.updatePrompt("Generate")
                if (edit) { rig.vm.addReferenceImages(listOf("file:///original.jpg")); rig.vm.editImage() }
                else rig.vm.generateImage()
            }
            ReflectionHelpers.getField<Job>(rig.vm, "cancelJob").join()
            assertNull("Generation must complete successfully", rig.vm.error.value)
            assertEquals(2, rig.files.size)
            assertEquals(listOf("preview", "final"), rig.bytes)
            assertTrue("Both real file saves must execute away from Main", rig.writeThreads.all { it !== rig.mainThread })
            assertTrue("Preview and final UI updates must be published", publications.size >= 2)
            assertTrue("UI state must be published on Main", publications.all { it === rig.mainThread })
            assertFalse("Final image replaces temporary preview", rig.files.first().exists())
            assertEquals("final", rig.files.last().readText())
            assertEquals(1, rig.rows.size)
            assertEquals(if (edit) GenMediaEntity.TYPE_IMAGE_EDIT else GenMediaEntity.TYPE_IMAGE_GENERATION, rig.rows.single().type)
            assertEquals(rig.files.last().absolutePath, rig.vm.currentGeneratedImages.value.single().filePath)
        } finally { observer.cancel(); rig.close() }
    }

    private class Rig(
        images: Flow<ImageGenerationItem>,
        afterWrite: (File, Thread) -> Unit = { _, _ -> },
        beforeInsert: suspend () -> Unit = {},
    ) {
        private val executor = Executors.newSingleThreadExecutor { command -> Thread(command, "imggen-fixture-main") }
        private val dispatcher = executor.asCoroutineDispatcher()
        val mainThread: Thread = executor.submit<Thread> { Thread.currentThread() }.get()
        private val scope: AppScope
        private val store = ViewModelStore()
        val vm: ImgGenVM
        val files = CopyOnWriteArrayList<File>()
        val bytes = CopyOnWriteArrayList<String>()
        val writeThreads = CopyOnWriteArrayList<Thread>()
        val rows = CopyOnWriteArrayList<GenMediaEntity>()
        init {
            Dispatchers.setMain(dispatcher)
            scope = AppScope()
            val context = RuntimeEnvironment.getApplication()
            val managed = mockk<ManagedFileDAO>()
            coEvery { managed.getByPath(any()) } returns null
            coEvery { managed.insert(any()) } returns 1L
            val fileManager = spyk(FilesManager(context, FilesRepository(managed), scope))
            every { fileManager.createImageFileFromBase64(any(), any()) } answers {
                writeThreads.add(Thread.currentThread())
                val file = callOriginal()
                files.add(file); bytes.add(file.readText()); afterWrite(file, mainThread); file
            }
            val media = mockk<GenMediaDAO>()
            coEvery { media.insert(any()) } coAnswers { beforeInsert(); rows.add(firstArg()); Unit }
            val model = Model(modelId = "image", displayName = "fixture/model")
            val setting = ProviderSetting.OpenAI(models = listOf(model))
            val settings = mockk<SettingsStore>()
            every { settings.settingsFlow } returns kotlinx.coroutines.flow.MutableStateFlow(Settings(providers = listOf(setting), imageGenerationModelId = model.id))
            val provider = mockk<Provider<ProviderSetting.OpenAI>>()
            coEvery { provider.generateImage(any(), any()) } returns images
            coEvery { provider.editImage(any(), any()) } returns images
            val manager = ProviderManager(OkHttpClient(), context).apply { registerProvider("openai", provider) }
            vm = ImgGenVM(context, settings, manager, GenMediaRepository(media), fileManager)
            store.put("fixture", vm)
        }
        suspend fun close() {
            withContext(Dispatchers.Main) { store.clear() }
            scope.cancel()
            Dispatchers.resetMain()
            dispatcher.close()
        }
    }

    private fun item(text: String, partial: Boolean) = ImageGenerationItem(Base64.getEncoder().encodeToString(text.toByteArray()), "image/png", partial)
}
