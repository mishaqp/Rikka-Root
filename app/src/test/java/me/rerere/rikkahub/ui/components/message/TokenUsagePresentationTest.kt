package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsagePresentationTest {
    @Test fun `prompt cache output speed and duration retain their separate values`() {
        assertEquals(
            listOf(
                TokenUsageStatistic.Count(TokenCountKind.Prompt, 18_450),
                TokenUsageStatistic.Count(TokenCountKind.Cached, 18_200),
                TokenUsageStatistic.Count(TokenCountKind.Completion, 537),
                TokenUsageStatistic.Speed(537 / 2.1),
                TokenUsageStatistic.Duration(2.1),
            ),
            tokenUsageStatistics(
                TokenUsage(promptTokens = 18_450, cachedTokens = 18_200, completionTokens = 537, totalTokens = 18_987),
                durationMillis = 2_100,
            ),
        )
    }

    @Test fun `zero input and its cache are omitted while reported output remains`() {
        assertEquals(
            listOf(TokenUsageStatistic.Count(TokenCountKind.Completion, 37)),
            tokenUsageStatistics(TokenUsage(cachedTokens = 12, completionTokens = 37)),
        )
    }

    @Test fun `provider total is shown when no breakdown or timing exists`() {
        assertEquals(
            listOf(TokenUsageStatistic.Count(TokenCountKind.Total, 8_900)),
            tokenUsageStatistics(TokenUsage(totalTokens = 8_900)),
        )
    }

    @Test fun `zero and negative durations never produce speed or elapsed time`() {
        listOf(0L, -1L).forEach { duration ->
            assertEquals(
                listOf(TokenUsageStatistic.Count(TokenCountKind.Completion, 12)),
                tokenUsageStatistics(TokenUsage(completionTokens = 12), duration),
            )
        }
    }

    @Test fun `provider total remains visible alongside elapsed time with zero breakdown counts`() {
        assertEquals(
            listOf(
                TokenUsageStatistic.Count(TokenCountKind.Total, 8_900),
                TokenUsageStatistic.Duration(1.25),
            ),
            tokenUsageStatistics(TokenUsage(totalTokens = 8_900), durationMillis = 1_250),
        )
    }

    @Test fun `timing values retain one decimal and follow reference binary double rounding`() {
        mapOf(1.0 to "1.0", 1.15 to "1.1", 1.25 to "1.3", 2.1 to "2.1")
            .forEach { (value, expected) -> assertEquals(expected, formatTokenStatisticDecimal(value)) }
    }

    @Test fun `very short completion remains finite`() {
        val speed = tokenUsageStatistics(TokenUsage(completionTokens = Int.MAX_VALUE), durationMillis = 1)
            .filterIsInstance<TokenUsageStatistic.Speed>().single()
        assertTrue(speed.tokensPerSecond.isFinite())
        assertEquals(Int.MAX_VALUE * 1_000.0, speed.tokensPerSecond, 0.0)
    }

    @Test fun `compact counts follow the reference thresholds and remove decimal zeros`() {
        mapOf(
            0 to "0",
            537 to "537",
            1_000 to "1k",
            1_234 to "1.2k",
            18_450 to "18.4k",
            100_400 to "100k",
            999_999 to "1000k",
            1_000_000 to "1M",
            1_250_000 to "1.3M",
        ).forEach { (tokens, expected) -> assertEquals("$tokens tokens", expected, compactTokenCount(tokens)) }
    }
}
