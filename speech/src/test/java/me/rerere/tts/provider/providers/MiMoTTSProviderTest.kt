package me.rerere.tts.provider.providers

import me.rerere.common.http.SseEvent
import me.rerere.tts.model.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MiMoTTSProviderTest {
    @Test
    fun decode_audio_data_from_sse_chunk() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(expected)
        val data = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val actual = decodeMiMoAudioData(data)

        assertNotNull(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun ignore_sse_chunk_without_audio_data() {
        val data = """{"choices":[{"delta":{"content":"hello"}}]}"""
        assertNull(decodeMiMoAudioData(data))
    }

    @Test
    fun emits_single_terminal_chunk_on_done_and_closed() {
        val processor = MiMoSseProcessor(model = "mimo-v2-tts", voice = "mimo_default")
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val audioData = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val first = processor.process(SseEvent.Event(id = null, type = null, data = audioData))
        val done = processor.process(SseEvent.Event(id = null, type = null, data = "[DONE]"))
        val terminal = processor.process(SseEvent.Closed)

        assertNotNull(first)
        assertEquals(AudioFormat.PCM, first?.format)
        assertFalse(first?.isLast ?: true)
        assertNull(done)
        assertNotNull(terminal)
        assertTrue(terminal?.isLast ?: false)
    }

    @Test
    fun throws_when_stream_closed_without_audio() {
        val processor = MiMoSseProcessor(model = "mimo-v2-tts", voice = "mimo_default")

        var thrown: Throwable? = null
        try {
            processor.process(SseEvent.Event(id = null, type = null, data = "[DONE]"))
            processor.process(SseEvent.Closed)
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
    }

    @Test fun staticMimoPromptGuidanceUsesRussianInstructionalText() {
        val guidance = MiMoTTSProvider().promptGuidance
        val nonRussian = guidance.lineSequence().map(String::trim).filter(String::isNotEmpty)
            .filterNot { Regex("[А-Яа-яЁё]").containsMatchIn(it) }.toList()
        assertTrue("Built-in instructional lines must be Russian: ${nonRussian.joinToString(" | ")}", nonRussian.isEmpty())
    }

    @Test fun staticMimoPromptGuidanceRetainsExactProviderTagsExamplesAndApiMarkers() {
        val guidance = MiMoTTSProvider().promptGuidance
        val styles = "开心/悲伤/愤怒/恐惧/惊讶/兴奋/委屈/平静/冷漠/怅然/欣慰/无奈/释然/温柔/高冷/活泼/严肃/慵懒/俏皮/深沉/磁性/醇厚/清亮/空灵/甜美/沙哑/御姐音/正太音/大叔音/台湾腔/东北话/四川话/河南话/粤语"
        val audioTags = "[吸气] [深呼吸] [叹气] [笑] [轻笑] [大笑] [冷笑] [抽泣] [哽咽] [颤抖] [气声] [撒娇] [疲惫] [震惊]"
        for (literal in listOf(styles, audioTags, "text_to_speech", "\"text\"", "(style)", "(开心 磁性)", "(唱歌)",
            "()", "（）", "[]", "(紧张 深呼吸)", "(紧张，深呼吸)",
            "(磁性)夜已经深了[叹气]城市还在呼吸。我是今晚陪你的人。")) {
            assertTrue("Provider literal changed: $literal", guidance.contains(literal))
        }
    }

}
