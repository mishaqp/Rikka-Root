package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val REPOSITORY_API_URL = "https://api.github.com/repos/mishaqp/Rikka-Root"
private const val NIGHTLY_API_URL = "$REPOSITORY_API_URL/releases/tags/nightly"
private const val UPDATE_PREFERENCES = "nightly_updater"
private const val UPDATE_CACHE_KEY = "cache"

class UpdateChecker(
    clientProvider: () -> OkHttpClient,
    private val appScope: AppScope,
) {
    constructor(client: OkHttpClient, appScope: AppScope) : this({ client }, appScope)

    // Resolving the shared client also initializes network/search services and reads their cache.
    // The first access is in fetch(), after check() has switched to Dispatchers.IO.
    private val client: OkHttpClient by lazy(clientProvider)
    private val checkMutex = Mutex()
    private val cacheJson = Json { ignoreUnknownKeys = true }
    private var cacheLoaded = false
    private var preferences: SharedPreferences? = null
    @Volatile private var applicationContext: Context? = null
    private val mutableNightlyState = MutableStateFlow(NightlyUpdateState())
    private val mutableUpdateState = MutableStateFlow<UiState<UpdateInfo>>(UiState.Idle)

    val nightlyState: StateFlow<NightlyUpdateState> = mutableNightlyState.asStateFlow()

    /** Retained for the existing chat card and view model; errors have no toast or notification. */
    val updateState: StateFlow<UiState<UpdateInfo>> = mutableUpdateState.asStateFlow()

    fun start(context: Context): Job {
        applicationContext = context.applicationContext
        return checkNow(force = false)
    }

    fun checkNow(force: Boolean = true): Job = appScope.launch(Dispatchers.IO) { check(force) }

    suspend fun check(force: Boolean = false): NightlyUpdateState = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            loadCache()
            val previous = mutableNightlyState.value
            val now = System.currentTimeMillis()
            if (!shouldCheckNightly(previous.checkedAt, now, force)) return@withLock previous

            val checking = previous.copy(isChecking = true, error = null, checkedAt = now)
            publish(checking)
            // Save the attempt before networking, including when the process dies while offline.
            saveCache(checking.copy(isChecking = false))
            val result = try {
                val release = decodeNightlyReleaseUpdate(fetch(NIGHTLY_API_URL, force))
                    ?: throw UpdateCheckException("Nightly-сборка с подходящим APK пока недоступна.")
                val currentCommit = BuildConfig.GIT_COMMIT
                if (!isGitCommitHash(currentCommit)) {
                    throw UpdateCheckException("Не удалось определить коммит установленной сборки.")
                }
                val update = if (sameGitCommit(currentCommit, release.commitHash)) {
                    release
                } else {
                    val comparison = compareCommits(currentCommit, release.commitHash, force)
                    if (comparison.relation == NightlyCommitRelation.DIVERGED) {
                        throw UpdateCheckException("История nightly и установленной сборки разошлась. Не удалось определить наличие обновления.")
                    }
                    release.copy(
                        isNewer = comparison.relation == NightlyCommitRelation.AHEAD,
                        commitTitles = comparison.commitTitles,
                        changelog = comparison.commitTitles.joinToString("\n") { "- $it" },
                    )
                }
                NightlyUpdateState(update = update, checkedAt = now)
            } catch (cancel: CancellationException) {
                publish(previous.copy(isChecking = false, checkedAt = now))
                saveCache(mutableNightlyState.value)
                throw cancel
            } catch (error: Exception) {
                checking.copy(
                    isChecking = false,
                    error = when (error) {
                        is UpdateCheckException -> error.message
                        is IOException -> "Не удалось связаться с GitHub. Проверьте подключение к интернету."
                        else -> "Не удалось проверить обновления. Попробуйте ещё раз."
                    },
                )
            }
            publish(result)
            saveCache(result)
            result
        }
    }

    private suspend fun compareCommits(current: String, latest: String, force: Boolean): NightlyComparison {
        val url = "$REPOSITORY_API_URL/compare/$current...$latest?per_page=30"
        val first = decodeNightlyComparison(fetch(url, force, comparing = true))
        if (first.relation != NightlyCommitRelation.AHEAD || first.totalCommits <= 30) return first

        // Compare pages are chronological. Fetch only the tail to show the latest 30 changes.
        val lastPage = (first.totalCommits.toLong() + 29) / 30
        val last = decodeNightlyComparison(fetch("$url&page=$lastPage", force, comparing = true))
        val preceding = if (last.commitTitles.size < 30) {
            if (lastPage == 2L) first else {
                decodeNightlyComparison(fetch("$url&page=${lastPage - 1}", force, comparing = true))
            }
        } else null
        return first.copy(commitTitles = (preceding?.commitTitles.orEmpty() + last.commitTitles).takeLast(30))
    }

    private suspend fun fetch(url: String, force: Boolean, comparing: Boolean = false): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Rikka-Root ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (force) cacheControl(CacheControl.FORCE_NETWORK) }
            .build()
        return client.newCall(request).await().use { response ->
            when {
                response.isSuccessful -> response.body.string()
                response.code == 403 || response.code == 429 ->
                    throw UpdateCheckException("GitHub временно ограничил проверки. Попробуйте позже.")
                comparing && (response.code == 404 || response.code == 422) ->
                    throw UpdateCheckException("GitHub не смог сравнить коммиты установленной и nightly-сборки.")
                response.code == 404 -> throw UpdateCheckException("Nightly-сборка пока не опубликована.")
                else -> throw UpdateCheckException("Не удалось получить данные GitHub (HTTP ${response.code}).")
            }
        }
    }

    private fun loadCache() {
        if (cacheLoaded) return
        val context = applicationContext ?: return
        preferences = runCatching {
            context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
        }.getOrNull()
        cacheLoaded = true
        val cache = runCatching {
            preferences?.getString(UPDATE_CACHE_KEY, null)?.let {
                cacheJson.decodeFromString<NightlyUpdateCache>(it)
            }
        }.getOrNull() ?: return
        if (cache.matchesInstalledCommit(BuildConfig.GIT_COMMIT)) {
            publish(cache.state.copy(isChecking = false))
        }
    }

    private fun saveCache(state: NightlyUpdateState) {
        runCatching {
            preferences?.edit()?.putString(
                UPDATE_CACHE_KEY,
                cacheJson.encodeToString(NightlyUpdateCache(BuildConfig.GIT_COMMIT, state.copy(isChecking = false))),
            )?.commit()
        }
    }

    private fun publish(state: NightlyUpdateState) {
        mutableNightlyState.value = state
        mutableUpdateState.value = when {
            state.update != null -> UiState.Success(state.update)
            state.isChecking -> UiState.Loading
            state.error != null -> UiState.Error(IllegalStateException(state.error))
            else -> UiState.Success(UpdateInfo(BuildConfig.VERSION_NAME, "", "", emptyList()))
        }
    }

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                setTitle(download.name)
                setDescription("Загрузка обновления Rikka-Root…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                setMimeType("application/vnd.android.package-archive")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, download.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                publish(mutableNightlyState.value.copy(error = "Не удалось начать загрузку APK. Попробуйте открыть nightly-релиз в браузере."))
            }
        }
    }
}

private class UpdateCheckException(message: String) : Exception(message)

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>,
    val commitHash: String = "",
    val isNewer: Boolean = false,
    val commitTitles: List<String> = emptyList(),
)

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
