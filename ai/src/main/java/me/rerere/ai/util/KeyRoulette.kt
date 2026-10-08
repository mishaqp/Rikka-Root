package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化非秘密的 SHA-256 指纹到 cacheDir/lru_key_roulette_v2.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys
        }
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val FINGERPRINT_CACHE_FILE = "lru_key_roulette_v2.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

// 文件结构: Map<providerId, Map<SHA-256(apiKey), lastUsedTimestamp>>
private typealias LruCache = Map<String, Map<String, Long>>

private val FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")

private fun keyFingerprint(key: String): String = MessageDigest.getInstance("SHA-256")
    .digest(key.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    init {
        // Delete legacy plaintext even when no request (or no configured key) follows.
        synchronized(LruFileLock) { loadCache() }
    }

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys
        val keysByFingerprint = keyList.associateBy(::keyFingerprint)

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()

            // 取本 provider 的记录，过滤掉已过期条目和不在当前 key 列表中的条目
            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keysByFingerprint && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            // 优先选从未使用的 key，否则选最久未使用的
            val selected = keysByFingerprint.keys.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key

            providerCache[selected] = now
            allCache[providerId] = providerCache

            // 清理整个 provider 条目均已过期的记录
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }

            saveCache(allCache)
            return keysByFingerprint.getValue(selected)
        }
    }

    private fun loadCache(): LruCache {
        val file = File(context.cacheDir, FINGERPRINT_CACHE_FILE)
        val cache = readCache(file).mapValues { (_, entries) ->
            entries.filterKeys { FINGERPRINT_PATTERN.matches(it) }
        }
        val legacy = File(context.cacheDir, LRU_CACHE_FILE)
        if (!legacy.exists()) return cache

        val migrated = readCache(legacy).mapValues { (_, entries) ->
            entries.entries.associate { (key, timestamp) -> keyFingerprint(key) to timestamp }
        }.toMutableMap()
        // A newer cache can coexist with the old file after an interrupted update.
        // Keep the newest timestamps while retaining each provider's insertion order.
        cache.forEach { (provider, entries) ->
            val merged = migrated[provider].orEmpty().toMutableMap()
            entries.forEach { (fingerprint, timestamp) ->
                merged[fingerprint] = maxOf(merged[fingerprint] ?: Long.MIN_VALUE, timestamp)
            }
            migrated[provider] = merged
        }
        // No plaintext copy/temp file is ever created during migration.
        if (!legacy.delete()) {
            try {
                legacy.writeBytes(byteArrayOf())
            } catch (_: Exception) {
                throw IllegalStateException("Не удалось удалить старый кеш ключей провайдера")
            }
            legacy.delete()
        }
        saveCache(migrated)
        return migrated
    }

    private fun readCache(file: File): LruCache = try {
        if (file.exists()) Json.decodeFromString(file.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, FINGERPRINT_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }
}
