package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/** Manifest receiver hands boot events to Android; this family never launches detached actions. */
internal class BootTriggerFamily : WorkflowTriggerFamily {
    override val name = "boot"
    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.BootCompleted
    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) = Unit
    override suspend fun shutdown() = Unit
}

object WorkflowBootDispatcher {
    /** No registry/DI dependency before accepting the event and committing WorkManager handoff. */
    suspend fun onBoot(context: Context, source: String = "BOOT") {
        WorkflowBootScheduling(context).accept(source)
    }
}
