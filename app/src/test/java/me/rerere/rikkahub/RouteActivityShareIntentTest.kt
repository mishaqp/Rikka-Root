package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Invokes the real activity parser before onCreate, without unrelated app services. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
class RouteActivityShareIntentTest {
    @Test
    fun `single Parcelable stream retains its URI and text`() {
        val uri = Uri.parse("content://gallery/photo.png")
        val route = shareRoute(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Описание фото")
        })

        assertEquals(listOf(uri.toString()), route.streamUris)
        assertEquals("Описание фото", route.text)
    }

    @Test
    fun `single share accepts CharSequence text`() {
        val route = shareRoute(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, SpannableString("Текст из приложения") as CharSequence)
        })

        assertEquals("Текст из приложения", route.text)
    }

    @Test
    fun `single document Parcelable stream retains its URI`() {
        val uri = Uri.parse("content://files/report.pdf")
        val route = shareRoute(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
        })

        assertEquals(listOf(uri.toString()), route.streamUris)
        assertEquals("", route.text)
    }

    @Test
    fun `multiple Parcelable streams create the assistant selection route`() {
        val route = shareRoute(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(
                Uri.parse("content://files/first.pdf"), Uri.parse("content://gallery/second.png"),
            ))
            putExtra(Intent.EXTRA_TEXT, "Два вложения")
        })

        assertEquals(listOf("content://files/first.pdf", "content://gallery/second.png"), route.streamUris)
        assertEquals("Два вложения", route.text)
    }

    @Test
    fun `legacy camera String stream remains compatible`() {
        val route = shareRoute(Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, "content://camera/photo.jpg")
            putExtra(Intent.EXTRA_TEXT, "Камера")
        })

        assertEquals(listOf("content://camera/photo.jpg"), route.streamUris)
        assertEquals("Камера", route.text)
    }

    @Test
    fun `empty legacy stream does not become a draft attachment`() {
        val route = shareRoute(Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, "  ")
            putExtra(Intent.EXTRA_TEXT, "Текст без файла")
        })

        assertEquals(emptyList<String>(), route.streamUris)
        assertEquals("Текст без файла", route.text)
    }

    @Test
    fun `malformed single stream is ignored without losing text`() {
        val route = shareRoute(Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, 123)
            putExtra(Intent.EXTRA_TEXT, "Текст без файла")
        })

        assertEquals(emptyList<String>(), route.streamUris)
        assertEquals("Текст без файла", route.text)
    }

    @Test
    fun `empty multiple streams retain the selection text`() {
        val route = shareRoute(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Uri>())
            putExtra(Intent.EXTRA_TEXT, "Без вложений")
        })

        assertEquals(emptyList<String>(), route.streamUris)
        assertEquals("Без вложений", route.text)
    }

    @Test
    fun `malformed multiple stream elements cannot become draft attachments`() {
        val route = shareRoute(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Bundle()))
            putExtra(Intent.EXTRA_TEXT, "Некорректное вложение")
        })

        assertEquals(emptyList<String>(), route.streamUris)
        assertEquals("Некорректное вложение", route.text)
    }

    @Test
    fun `process text remains an assistant selection draft`() {
        val route = shareRoute(Intent(Intent.ACTION_PROCESS_TEXT).apply {
            putExtra(Intent.EXTRA_PROCESS_TEXT, SpannableString("Выбранный текст") as CharSequence)
        })

        assertEquals("Выбранный текст", route.text)
        assertEquals(emptyList<String>(), route.streamUris)
    }

    private fun shareRoute(intent: Intent) = routeShareIntent(intent)
}

internal fun routeShareIntent(intent: Intent): Screen.ShareHandler {
    val activity = Robolectric.buildActivity(RouteActivity::class.java).get()
    val initial = Screen.Chat("existing")
    val stack = mutableListOf<NavKey>(initial)
    RouteActivity::class.java.getDeclaredField("navStack").apply { isAccessible = true }
        .set(activity, stack)
    RouteActivity::class.java.getDeclaredMethod("handleIntent", Intent::class.java)
        .apply { isAccessible = true }.invoke(activity, intent)

    assertEquals("Share must add exactly one selection destination", 2, stack.size)
    assertEquals(initial, stack.first())
    assertTrue("Share must await assistant selection", stack.last() is Screen.ShareHandler)
    return stack.last() as Screen.ShareHandler
}
