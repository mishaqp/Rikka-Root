package me.rerere.rikkahub.data.ai.tools

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.search.BingSearchService
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Exercise the actual Bing service boundary; no Internet, source-text assertions or copied mapper. */
class BingSearchFailureMessageTest {
    private lateinit var connection: Connection

    @Before fun setUp() {
        connection = mockk()
        every { connection.userAgent(any()) } returns connection
        every { connection.header(any(), any()) } returns connection
        every { connection.referrer(any()) } returns connection
        every { connection.cookie(any(), any()) } returns connection
        every { connection.timeout(any()) } returns connection
        mockkStatic(Jsoup::class)
        every { Jsoup.connect(any<String>()) } returns connection
    }

    @After fun tearDown() {
        unmockkStatic(Jsoup::class)
    }

    private fun assertRussianFailure(original: IOException) = runBlocking {
        every { connection.get() } throws original
        val result = BingSearchService.search(
            buildJsonObject { put("query", "проверка обновлений") },
            SearchCommonOptions(),
            SearchServiceOptions.BingLocalOptions(),
        )
        assertTrue("A transport error must remain a failure", result.isFailure)
        val failure = requireNotNull(result.exceptionOrNull())
        assertNotSame("Do not return the original English transport exception", original, failure)
        val message = failure.message.orEmpty()
        assertTrue("Expected actionable Russian text, got: $message", Regex("[А-Яа-яЁё]").containsMatchIn(message))
        assertSame("Keep the transport error available for diagnostics", original, failure.cause)
    }

    @Test fun `Bing HTTP failures have a Russian explanation and preserve their cause`() {
        assertRussianFailure(HttpStatusException("HTTP error fetching URL", 503, "https://www.bing.com/search"))
    }

    @Test fun `Bing DNS failures have a Russian explanation and preserve their cause`() {
        assertRussianFailure(UnknownHostException("www.bing.com"))
    }

    @Test fun `Bing timeouts have a Russian explanation and preserve their cause`() {
        assertRussianFailure(SocketTimeoutException("Read timed out"))
    }
}
