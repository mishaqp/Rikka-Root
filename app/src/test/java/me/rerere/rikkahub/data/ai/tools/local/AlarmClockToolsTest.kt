package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.AlarmClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AlarmClockToolsTest {
    private val started = mutableListOf<Intent>()
    private var foreground = true
    private var launchFailure: RuntimeException? = null
    private lateinit var context: ContextWrapper

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val lifecycle = mockk<Lifecycle>()
        every { lifecycle.currentState } answers {
            if (foreground) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        }
        val owner = mockk<LifecycleOwner>()
        every { owner.lifecycle } returns lifecycle
        mockkObject(ProcessLifecycleOwner.Companion)
        every { ProcessLifecycleOwner.get() } returns owner
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) {
                launchFailure?.let { throw it }
                started += intent
            }
        }
    }

    @After fun tearDown() {
        unmockkObject(ProcessLifecycleOwner.Companion)
        Dispatchers.resetMain()
    }

    @Test fun allThreeClockToolsRequireApproval() {
        val tools = alarmClockTools(context)
        assertEquals(setOf("set_alarm", "set_timer", "show_alarms"), tools.map { it.name }.toSet())
        assertEquals(3, tools.size)
        tools.forEach { assertTrue(it.name, it.needsApproval(Json.parseToJsonElement("{}"))) }
    }

    @Test fun alarmPassesAndroidWeekdaysAndDefaultsToSkippingClockUi() = runBlocking {
        val output = execute("set_alarm", """{"hour":7,"minute":30,"message":"Подъём","days":[1,2,7]}""")
        val intent = started.single()
        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(7, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("Подъём", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals(listOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.SATURDAY), intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(true, output["intent_fired"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(output.containsKey("alarm_id"))
        assertFalse(output.containsKey("created"))
    }

    @Test fun explicitSkipUiFalseAndNoRepeatDaysArePreserved() = runBlocking {
        execute("set_alarm", """{"hour":23,"minute":59,"skip_ui":false}""")
        val intent = started.single()
        assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
        assertFalse(intent.hasExtra(AlarmClock.EXTRA_DAYS))
        assertFalse(intent.hasExtra(AlarmClock.EXTRA_MESSAGE))
    }

    @Test fun timerPassesSecondsMessageAndDefaultSkipUi() = runBlocking {
        val output = execute("set_timer", """{"seconds":90,"message":"Чай"}""")
        val intent = started.single()
        assertEquals(AlarmClock.ACTION_SET_TIMER, intent.action)
        assertEquals(90, intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertEquals("Чай", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertEquals(true, output["intent_fired"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test fun timerAcceptsIntegerBoundariesAndExplicitUi() = runBlocking {
        for (seconds in listOf(1, Int.MAX_VALUE)) {
            execute("set_timer", """{"seconds":$seconds,"skip_ui":false}""")
            assertEquals(seconds, started.last().getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
            assertFalse(started.last().getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
        }
        assertEquals(2, started.size)
    }

    @Test fun showAlarmsOnlyDispatchesTheSystemScreenRequest() = runBlocking {
        val output = execute("show_alarms")
        val intent = started.single()
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(true, output["intent_fired"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(output.containsKey("alarms"))
    }

    @Test fun malformedAlarmArgumentsNeverLaunchAnyActivity() = runBlocking {
        for (input in listOf(
            "{}", """{"hour":7}""", """{"minute":30}""",
            """{"hour":-1,"minute":0}""", """{"hour":24,"minute":0}""",
            """{"hour":0,"minute":-1}""", """{"hour":0,"minute":60}""",
            """{"hour":"7","minute":0}""", """{"hour":7.5,"minute":0}""",
            """{"hour":7,"minute":null}""", """{"hour":7,"minute":0,"days":[0]}""",
            """{"hour":7,"minute":0,"days":[8]}""", """{"hour":7,"minute":0,"days":[1,"2"]}""",
            """{"hour":7,"minute":0,"days":[1,2.5]}""", """{"hour":7,"minute":0,"days":1}""",
            """{"hour":7,"minute":0,"days":null}""", """{"hour":7,"minute":0,"skip_ui":"true"}""",
            """{"hour":7,"minute":0,"message":123}""", "[]",
        )) assertRussianError(execute("set_alarm", input))
        assertTrue(started.isEmpty())
    }

    @Test fun malformedTimerArgumentsNeverLaunchAnyActivity() = runBlocking {
        for (input in listOf(
            "{}", """{"seconds":0}""", """{"seconds":-1}""", """{"seconds":2147483648}""",
            """{"seconds":"90"}""", """{"seconds":1.5}""", """{"seconds":null}""",
            """{"seconds":90,"skip_ui":1}""", """{"seconds":90,"skip_ui":null}""",
            """{"seconds":90,"message":false}""", """{"seconds":90,"message":null}""",
        )) assertRussianError(execute("set_timer", input))
        assertTrue(started.isEmpty())
    }

    @Test fun backgroundAppCannotDispatchClockRequests() = runBlocking {
        foreground = false
        for ((name, input) in validInputs) assertRussianError(execute(name, input))
        assertTrue(started.isEmpty())
    }

    @Test fun headlessRunCannotDispatchEvenWhileAppIsForeground() = runBlocking {
        val run = RunExecutionContext(Uuid.random(), Uuid.random(), RunOrigin.CRON)
        withContext(run) {
            for ((name, input) in validInputs) assertRussianError(execute(name, input))
        }
        assertTrue(started.isEmpty())
    }

    @Test fun missingClockApplicationReturnsRussianGuidanceWithoutClaimingSuccess() = runBlocking {
        launchFailure = ActivityNotFoundException("private Android failure")
        for ((name, input) in validInputs) {
            val output = execute(name, input)
            assertRussianError(output)
            assertTrue(output.getValue("error").jsonPrimitive.content.contains("час", ignoreCase = true))
            assertFalse(output.toString().contains("private Android failure"))
            assertFalse(output["success"]?.jsonPrimitive?.booleanOrNull == true)
        }
        assertTrue(started.isEmpty())
    }

    @Test fun androidLaunchDenialReturnsRussianError() = runBlocking {
        launchFailure = SecurityException("private denied component")
        for ((name, input) in validInputs) {
            val output = execute(name, input)
            assertRussianError(output)
            assertFalse(output.toString().contains("private denied component"))
        }
        assertTrue(started.isEmpty())
    }

    private suspend fun execute(name: String, input: String = "{}"): JsonObject {
        val tool = alarmClockTools(context).single { it.name == name }
        val parts = tool.execute(Json.parseToJsonElement(input))
        return Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    private fun assertRussianError(output: JsonObject) {
        val error = output["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(output.toString(), error.any { it in 'А'..'я' })
    }

    private val validInputs = listOf(
        "set_alarm" to """{"hour":0,"minute":0}""",
        "set_timer" to """{"seconds":1}""",
        "show_alarms" to "{}",
    )
}
