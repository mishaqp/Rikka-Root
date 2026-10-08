package me.rerere.rikkahub.workflow.condition

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class WorkflowContextPrivacyTest {
    @Test fun `background workflow snapshot never probes granted foreground location`() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        var locationLookups = 0
        val context = object : ContextWrapper(application) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.LOCATION_SERVICE) {
                    locationLookups++
                    error("Location must not be read by background workflow conditions")
                }
                return super.getSystemService(name)
            }
        }
        val snapshot = ContextProvider(context).snapshot(needsLocation = true)
        assertNull(snapshot.latitude)
        assertNull(snapshot.longitude)
        assertEquals(0, locationLookups)
    }
    @Test fun `Bluetooth denial remains denied and only Bluetooth triggers report missing access`() {
        val application = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(application)
        shadow.denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val availability = me.rerere.rikkahub.workflow.execution.WorkflowAvailability
        val trigger = me.rerere.rikkahub.workflow.model.TriggerSpec.BluetoothDeviceConnected()
        repeat(2) {
            assertTrue(availability.runtimeReason(application, trigger).orEmpty().contains("Нет доступа к Bluetooth"))
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                androidx.core.content.ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT))
        }
        assertNull(availability.runtimeReason(application, me.rerere.rikkahub.workflow.model.TriggerSpec.Manual))
        assertNull(availability.runtimeReason(application, me.rerere.rikkahub.workflow.model.TriggerSpec.TimeCron(timeOfDay = "09:00")))
    }

}
