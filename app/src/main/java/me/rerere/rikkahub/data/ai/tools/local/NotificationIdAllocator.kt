package me.rerere.rikkahub.data.ai.tools.local

/** Reserve durably before posting, even when separate tool instances run concurrently. */
internal class NotificationIdAllocator(
    private val readNext: () -> Int,
    private val storeNext: (Int) -> Boolean,
) {
    fun allocate(): Int? = synchronized(allocationLock) {
        val id = readNext()
        // Never wrap and overwrite an existing notification, even if the counter is exhausted.
        if (id < FIRST_ID || id == Int.MAX_VALUE || !storeNext(id + 1)) null else id
    }

    companion object {
        const val FIRST_ID = 0x60000000
        private val allocationLock = Any()
    }
}
