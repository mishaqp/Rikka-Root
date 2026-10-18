package me.rerere.rikkahub.data.ai.tools.local

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.spi.FileSystemProvider
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class PrivateInstallCreationRaceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `denied hardlink fallback preserves a destination created at the native install boundary`() = runBlocking {
        val staging = folder.newFile("staging").apply { writeText("staged-content") }
        val target = File(folder.root, "target")
        val provider = mockk<FileSystemProvider>()
        val filesystem = mockk<FileSystem>()
        val stagedPath = mockk<Path>()
        val targetPath = mockk<Path>()
        every { stagedPath.fileSystem } returns filesystem
        every { targetPath.fileSystem } returns filesystem
        every { filesystem.provider() } returns provider
        var installAttempts = 0

        fun competingCreation() {
            installAttempts++
            assertFalse("The competing writer starts after the caller's preflight", target.exists())
            Files.writeString(target.toPath(), "competing-file", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }

        every { provider.move(stagedPath, targetPath, *anyVararg()) } answers {
            // Exactly UnixCopyFile.move: lstat finds no target, then a competitor creates
            // one before native rename. ATOMIC_MOVE delegates to that real rename primitive.
            // Android14 source and the independent native-window C proof accompany this test.
            assertFalse(Files.exists(target.toPath()))
            competingCreation()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Unit
        }
        every { provider.newOutputStream(targetPath, *anyVararg()) } answers {
            competingCreation()
            // Forward the real requested options to the real filesystem: CREATE_NEW must
            // refuse the competitor, while a truncating implementation would destroy it.
            Files.newOutputStream(target.toPath(), *secondArg<Array<java.nio.file.OpenOption>>())
        }

        val originalLink = privateHardLink
        try {
            privateHardLink = { _, _ -> throw AccessDeniedException("Android denied link") }
            val result = runCatching {
                installPrivate(withPath(staging, stagedPath), withPath(target, targetPath))
            }
            assertEquals(1, installAttempts)
            assertEquals("A no-overwrite install must preserve the competing writer's bytes", "competing-file", target.readText())
            assertTrue("Failed exclusive install must preserve the completed stage", staging.exists())
            assertEquals("staged-content", staging.readText())
            assertTrue("The actual competing creation, not reflection/API absence, must cause the refusal",
                result.exceptionOrNull() is FileAlreadyExistsException)
        } finally {
            privateHardLink = originalLink
        }
    }

    private fun withPath(file: File, path: Path): File = object : File(file.path) {
        override fun toPath(): Path = path
    }

    /** Invoke the actual private installer; do not replace its decision or implementation. */
    private suspend fun installPrivate(staging: File, target: File): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = Class.forName("me.rerere.rikkahub.data.ai.tools.local.LocalFileAccessKt")
            .declaredMethods.single { it.name == "installPrivateNewFile" }.apply { isAccessible = true }
        try {
            when (method.parameterTypes.toList()) {
                listOf(File::class.java, File::class.java) -> { method.invoke(null, staging, target); Unit }
                listOf(File::class.java, File::class.java, Function0::class.java, Continuation::class.java) ->
                    method.invoke(null, staging, target, {}, continuation)
                else -> error("Unexpected private installer signature; this is not race evidence")
            }
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}
