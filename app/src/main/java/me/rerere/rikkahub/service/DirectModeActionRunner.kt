package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.db.entity.CronRunPolicy
import me.rerere.rikkahub.data.repository.CronAction

/** Direct and LLM schedules use the same trusted policy runner and one occurrence claim. */
class DirectModeActionRunner(private val runner: HeadlessTaskRunner) {
    suspend fun run(actions: List<CronAction>, context: RunExecutionContext): HeadlessTaskResult =
        runner.run(HeadlessTaskRequest.DirectActions(actions.map { HeadlessToolAction(it.tool, it.args) }), context)
}

data class CronExecutionOutcome(val status: String, val code: String?) {
    companion object {
        fun fromStatus(status: String, errorCode: String?): CronExecutionOutcome {
            val outcome = when (status) {
                "SUCCEEDED" -> "succeeded"
                "FAILED" -> "failed"
                "BLOCKED" -> "blocked"
                else -> "indeterminate"
            }
            val code = errorCode ?: when (status) {
                "TIMED_OUT" -> "deadline_exceeded"
                "CANCELLED" -> "execution_cancelled"
                "INDETERMINATE" -> "process_interrupted"
                else -> null
            }
            return CronExecutionOutcome(outcome, CronRunPolicy.safeOutcome(code))
        }
    }
}
