package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/** Agent GeofenceTriggerFamily is gated off: no background-location special access or GMS is imported. */
internal class GeofenceTriggerFamily(context: Context, scope: CoroutineScope) : WorkflowTriggerFamily {
    override val name = "geofence"
    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.GeofenceEnter || spec is TriggerSpec.GeofenceExit
    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) = Unit
    override suspend fun shutdown() = Unit
}
