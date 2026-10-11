package me.rerere.rikkahub.utils

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class NightlyUpdateCheckerTest {
    private lateinit var context: Context
    private lateinit var scope: AppScope
    private lateinit var client: OkHttpClient
    private lateinit var github: GitHubResponses

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nightly_updater", Context.MODE_PRIVATE).edit().clear().commit()
        scope = AppScope()
        github = GitHubResponses()
        client = OkHttpClient.Builder().addInterceptor(github).build()
    }

    @After fun tearDown() {
        scope.cancel()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        Dispatchers.resetMain()
    }

    @Test fun `startup cache prevents another check after updater is recreated`() = runBlocking {
        val checker = UpdateChecker(client, scope)
        checker.start(context).join()
        assertTrue(checker.nightlyState.value.hasUpdate)
        assertEquals(2, github.requests.size)

        val recreated = UpdateChecker(client, scope)
        recreated.start(context).join()
        assertTrue(recreated.nightlyState.value.hasUpdate)
        assertEquals(2, github.requests.size)
    }

    @Test fun `HTTP factory is deferred from UI construction and startup into IO check`() = runBlocking {
        val uiThread = Thread.currentThread()
        val factoryThreads = CopyOnWriteArrayList<Thread>()
        val checker = UpdateChecker(
            clientProvider = {
                factoryThreads += Thread.currentThread()
                client
            },
            appScope = scope,
        )
        assertTrue(factoryThreads.isEmpty())

        checker.start(context).join()
        assertTrue(checker.nightlyState.value.hasUpdate)
        assertEquals(1, factoryThreads.size)
        assertTrue(factoryThreads.all { it !== uiThread })

        checker.check(force = true)
        assertEquals(1, factoryThreads.size)
    }

    @Test fun `cached startup never initializes the HTTP factory`() = runBlocking {
        UpdateChecker(client, scope).start(context).join()
        val factoryThreads = CopyOnWriteArrayList<Thread>()
        val cached = UpdateChecker(
            clientProvider = {
                factoryThreads += Thread.currentThread()
                client
            },
            appScope = scope,
        )
        cached.start(context).join()
        assertTrue(cached.nightlyState.value.hasUpdate)
        assertTrue(factoryThreads.isEmpty())
        assertEquals(2, github.requests.size)
    }

    @Test fun `manual force rechecks and bypasses HTTP cache`() = runBlocking {
        val checker = UpdateChecker(client, scope)
        checker.start(context).join()
        checker.check(force = true)
        assertEquals(4, github.requests.size)
        assertTrue(github.requests.takeLast(2).all { it.header("Cache-Control") == "no-cache" })
    }

    @Test fun `installed full hash matching APK prefix needs no comparison`() = runBlocking {
        github.latestHash = BuildConfig.GIT_COMMIT.take(8)
        val checker = UpdateChecker(client, scope)
        checker.start(context).join()
        assertFalse(checker.nightlyState.value.hasUpdate)
        assertNull(checker.nightlyState.value.error)
        assertEquals(1, github.requests.size)
    }

    @Test fun `older nightly does not announce a downgrade`() = runBlocking {
        github.status = "behind"
        val checker = UpdateChecker(client, scope)
        checker.start(context).join()
        assertFalse(checker.nightlyState.value.hasUpdate)
        assertNull(checker.nightlyState.value.error)
    }

    @Test fun `diverged history is a quiet Russian error rather than current build`() = runBlocking {
        github.status = "diverged"
        val checker = UpdateChecker(client, scope)
        checker.start(context).join()
        assertFalse(checker.nightlyState.value.hasUpdate)
        assertTrue(checker.nightlyState.value.error!!.contains("История nightly"))
    }

    @Test fun `failed startup attempts are persisted and only forced checks retry early`() = runBlocking {
        github.releaseCode = 500
        val checker = UpdateChecker(client, scope)
        checker.start(context).join()
        assertTrue(checker.nightlyState.value.error!!.contains("HTTP 500"))

        val recreated = UpdateChecker(client, scope)
        recreated.start(context).join()
        assertEquals(1, github.requests.size)
        assertTrue(recreated.nightlyState.value.error!!.contains("HTTP 500"))

        github.releaseCode = 200
        recreated.check(force = true)
        assertTrue(recreated.nightlyState.value.hasUpdate)
        assertNull(recreated.nightlyState.value.error)
        assertEquals(3, github.requests.size)
    }

    @Test fun `comparison fetches latest thirty titles across tail pages`() = runBlocking {
        github.totalCommits = 61
        val checker = UpdateChecker(client, scope)
        checker.start(context).join()
        val update = checker.nightlyState.value.update!!
        assertEquals(30, update.commitTitles.size)
        assertEquals("Change 32", update.commitTitles.first())
        assertEquals("Change 61", update.commitTitles.last())
        assertTrue(github.requests.drop(1).all {
            it.url.encodedPath.endsWith("/compare/${BuildConfig.GIT_COMMIT}...${github.latestHash}")
        })
        assertEquals(4, github.requests.size)
    }

    @Test fun `download and browser failures stay in About state without toast`() = runBlocking {
        val checker = UpdateChecker(client, scope)
        checker.start(context).join()
        val unavailable = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.DOWNLOAD_SERVICE) throw SecurityException("Unavailable")
                return super.getSystemService(name)
            }

            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("Unavailable")
            }
        }
        checker.downloadUpdate(unavailable, checker.nightlyState.value.update!!.downloads.single())
        assertTrue(checker.nightlyState.value.error!!.contains("загрузку APK"))
        assertNull(ShadowToast.getLatestToast())
    }

    private class GitHubResponses : Interceptor {
        val requests = CopyOnWriteArrayList<okhttp3.Request>()
        var latestHash = if (BuildConfig.GIT_COMMIT.startsWith("ffffffff")) "eeeeeeee" else "ffffffff"
        var releaseCode = 200
        var status = "ahead"
        var totalCommits = 2

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val isRelease = request.url.encodedPath.endsWith("/releases/tags/nightly")
            val body = if (isRelease) {
                val name = "Rikka-Root-2.5.6-root.2-$latestHash-arm64-v8a.apk"
                """{"tag_name":"nightly","draft":false,"prerelease":true,"assets":[{"state":"uploaded","name":"$name","size":1024,"updated_at":"2026-10-11T10:00:00Z","browser_download_url":"https://github.com/mishaqp/Rikka-Root/releases/download/nightly/$name"}]}"""
            } else {
                val page = request.url.queryParameter("page")?.toInt() ?: 1
                val commits = (((page - 1) * 30 + 1)..minOf(page * 30, totalCommits))
                    .joinToString(",") { """{"commit":{"message":"Change $it\nDetails"}}""" }
                """{"status":"$status","total_commits":$totalCommits,"commits":[$commits]}"""
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (isRelease) releaseCode else 200)
                .message("Fixture")
                .body(body.toResponseBody())
                .build()
        }
    }
}
