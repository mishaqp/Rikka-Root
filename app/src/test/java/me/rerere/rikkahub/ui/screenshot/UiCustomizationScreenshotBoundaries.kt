package me.rerere.rikkahub.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.IdlingResource
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.compose.AsyncImagePainter
import coil3.decode.BitmapFactoryDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.serviceLoaderEnabled
import coil3.svg.SvgDecoder
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import ru.noties.jlatexmath.JLatexMathAndroid
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

/** Real asset/file decoders with synchronous offline HTTP, including terminal painter states. */
@OptIn(DelicateCoilApi::class)
internal class UiCustomizationScreenshotBoundaries {
    private val active = AtomicInteger()
    private val painters = ConcurrentHashMap<AsyncImagePainter, ImageRequest>()
    lateinit var imageLoader: ImageLoader
        private set
    val wallpaper = File("/tmp/rikka-ui-customization/wallpaper.png")
    val imagesIdle = object : IdlingResource {
        override val isIdleNow: Boolean get() {
            if (active.get() != 0) return false
            painters.entries.removeIf { (painter, request) ->
                when (val state = painter.state.value) {
                    is AsyncImagePainter.State.Success -> state.result.request === request
                    is AsyncImagePainter.State.Error -> state.result.request === request
                    else -> false
                }
            }
            return active.get() == 0 && painters.isEmpty()
        }
        override fun getDiagnosticMessageIfBusy() = "Waiting for ${active.get()} Coil requests and ${painters.size} painter results"
    }

    fun install() {
        val context = RuntimeEnvironment.getApplication()
        JLatexMathAndroid.init(context)
        requireNotNull(wallpaper.parentFile).mkdirs()
        val bitmap = Bitmap.createBitmap(411, 891, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(53, 92, 151))
            drawCircle(355f, 140f, 185f, Paint().apply { color = Color.rgb(195, 121, 165) })
            drawCircle(65f, 570f, 285f, Paint().apply { color = Color.rgb(46, 150, 153) })
            drawCircle(380f, 825f, 240f, Paint().apply { color = Color.rgb(241, 178, 109) })
        }
        wallpaper.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val offlineClient = OkHttpClient.Builder().dispatcher(Dispatcher(InlineScreenshotExecutor())).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404)
                .message("Screenshot fixture: offline").body(ByteArray(0).toResponseBody()).build()
        }.build()
        imageLoader = ImageLoader.Builder(context)
            .serviceLoaderEnabled(false)
            .diskCache(null)
            .interceptorCoroutineContext(EmptyCoroutineContext)
            .fetcherCoroutineContext(EmptyCoroutineContext)
            .decoderCoroutineContext(EmptyCoroutineContext)
            .eventListenerFactory {
                object : EventListener() {
                    private val started = AtomicBoolean()
                    private var painter: AsyncImagePainter? = null
                    override fun onStart(request: ImageRequest) {
                        if (!started.compareAndSet(false, true)) return
                        active.incrementAndGet()
                        val target = request.target
                        val field = target?.javaClass?.declaredFields?.singleOrNull {
                            AsyncImagePainter::class.java.isAssignableFrom(it.type)
                        }
                        check(target?.javaClass?.name?.startsWith("coil3.compose.AsyncImagePainter") != true || field != null)
                        painter = field?.let { it.isAccessible = true; it.get(target) as AsyncImagePainter }
                        painter?.let { painters[it] = request }
                    }
                    private fun complete() { if (started.compareAndSet(true, false)) active.decrementAndGet() }
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) = complete()
                    override fun onError(request: ImageRequest, result: ErrorResult) = complete()
                    override fun onCancel(request: ImageRequest) {
                        painter?.let { value -> painters.computeIfPresent(value) { _, current -> if (current === request) null else current } }
                        complete()
                    }
                }
            }
            .components {
                add(SvgDecoder.Factory(scaleToDensity = true))
                add(BitmapFactoryDecoder.Factory())
                add(OkHttpNetworkFetcherFactory(callFactory = { offlineClient }))
            }.build()
        SingletonImageLoader.setUnsafe(imageLoader)
        for (data in listOf(wallpaper.toURI().toString(), "file:///android_asset/icons/openai.svg")) {
            val decoded = runBlocking { imageLoader.execute(ImageRequest.Builder(context).data(data).build()) }
            assertTrue("Local fixture must decode through production Coil: $data ${(decoded as? ErrorResult)?.throwable}", decoded is SuccessResult)
        }
    }

    fun close() {
        if (::imageLoader.isInitialized) imageLoader.shutdown()
        SingletonImageLoader.reset()
    }
}

private class InlineScreenshotExecutor : AbstractExecutorService() {
    @Volatile private var closed = false
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { closed = true }
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { closed = true }
    override fun isShutdown() = closed
    override fun isTerminated() = closed
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
