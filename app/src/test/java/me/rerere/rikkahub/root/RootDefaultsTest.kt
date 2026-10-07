package me.rerere.rikkahub.root

import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Test

class RootDefaultsTest {
    @Test fun `new assistants do not enable root`() {
        assertFalse(Assistant().localTools.contains(LocalToolOption.Root))
    }
}
