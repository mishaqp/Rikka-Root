package me.rerere.rikkahub.ui.components.message

import android.app.Application
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatErrorSolution
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ChatErrorCharacterizationTest {
    @Test fun networkClassificationUsesProductionExceptionTypesAndLocalizedResources() {
        val context = RuntimeEnvironment.getApplication()
        val loop = GenerationLoop(context, ProviderManager(OkHttpClient(), context), Json)
        // Classification is private and has no public projection. Invoke the real method instead
        // of copying its when-expression into a test-only "error policy".
        val classify = GenerationLoop::class.java.getDeclaredMethod("getNetworkErrorMessage", IOException::class.java)
            .apply { isAccessible = true }
        listOf(
            UnknownHostException("host") to R.string.chat_generation_network_unknown_host,
            SocketTimeoutException("timeout") to R.string.chat_generation_network_timeout,
            ConnectException("refused") to R.string.chat_generation_network_unreachable,
            NoRouteToHostException("route") to R.string.chat_generation_network_unreachable,
            IOException("connection reset") to R.string.chat_generation_network_disconnected,
            // Classification uses exception type, not misleading free-form text.
            IOException("timeout unknown host refused") to R.string.chat_generation_network_disconnected,
        ).forEach { (error, resource) ->
            assertEquals(error.javaClass.simpleName, context.getString(resource), classify.invoke(loop, error))
        }
    }

    @Test fun chatErrorRetainsThrowableConversationIdentityTimestampAndRecoveryAction() {
        val throwable = IOException("Provider disconnected")
        val id = Uuid.parse("52000000-0000-0000-0000-000000000001")
        val conversationId = Uuid.parse("52000000-0000-0000-0000-000000000002")
        val error = ChatError(id, "Generation error", throwable, conversationId, 1_767_326_645_000, ChatErrorSolution.CheckFastModelSettings)
        assertEquals(id, error.id)
        assertEquals(conversationId, error.conversationId)
        assertEquals("Generation error", error.title)
        assertSame(throwable, error.error)
        assertEquals(1_767_326_645_000, error.timestamp)
        assertEquals(ChatErrorSolution.CheckFastModelSettings, error.solution)
    }

    @Test fun anonymousErrorsHaveIndependentIdentityAndOptionalPresentationMetadata() {
        val first = ChatError(error = Throwable())
        val second = ChatError(error = Throwable())
        assertNotEquals(first.id, second.id)
        assertNull(first.title)
        assertNull(first.conversationId)
        assertNull(first.solution)
        assertNull(first.error.message)
    }
}
