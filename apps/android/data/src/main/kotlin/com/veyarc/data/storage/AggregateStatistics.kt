package com.veyarc.data.storage

import java.io.Writer

data class StorageStatistics(
    val revisions: Long,
    val revisionBytes: Long,
    val templates: Long,
    val templateBytes: Long,
    val previews: Long,
    val previewBytes: Long,
    val chats: Long,
    val chatBytes: Long,
    val workflowFolders: Long,
    val runFolders: Long,
)

data class RunAggregateGroup(
    val workflowId: String,
    val workflowName: String,
    val revision: Int,
    val status: String,
    val runs: Long,
    val timedRuns: Long,
    val durationMs: Long,
    val shortestMs: Long?,
    val longestMs: Long?,
    val completedSteps: Long,
    val plannedSteps: Long?,
    val plannedRuns: Long,
    val firstRunAtMs: Long,
    val lastRunAtMs: Long,
)

/** Metadata aggregates only: never opens workflow bodies, chat messages or run logs. */
data class AggregateStatistics(
    val workflows: List<WorkflowSummary>,
    val storage: StorageStatistics,
    val runs: List<RunAggregateGroup>,
    val ai: List<AssistantUsageSummary>,
) {
    fun writeCsv(writer: Writer, price: (String) -> AssistantModelPrice?) {
        writer.write("\uFEFF")
        writer.csvRow("category", "workflow_id", "workflow", "revision", "status", "provider_id", "provider", "model_id", "model", "metric", "value", "unit")
        fun row(category: String, metric: String, value: Any?, unit: String, workflow: WorkflowSummary? = null, run: RunAggregateGroup? = null, model: AssistantUsageSummary? = null) {
            writer.csvRow(category, workflow?.workflowId ?: run?.workflowId, workflow?.name ?: run?.workflowName, run?.revision, run?.status, model?.providerId, model?.providerName, model?.modelId, model?.modelName, metric, value, unit)
        }
        row("workflows", "saved", workflows.size, "workflows")
        row("workflows", "favorites", workflows.count { it.isFavorite }, "workflows")
        listOf(
            "workflow_folders" to storage.workflowFolders,
            "run_folders" to storage.runFolders,
            "revisions" to storage.revisions,
            "templates" to storage.templates,
            "previews" to storage.previews,
            "chats" to storage.chats,
        ).forEach { (metric, count) -> row("storage", metric, count, "items") }
        listOf("encrypted_revision_bytes" to storage.revisionBytes, "encrypted_template_bytes" to storage.templateBytes, "encrypted_preview_bytes" to storage.previewBytes, "encrypted_chat_bytes" to storage.chatBytes)
            .forEach { (metric, count) -> row("storage", metric, count, "bytes") }
        workflows.forEach { workflow ->
            row("workflow", "latest_revision", workflow.latestRevision, "revision", workflow)
            row("workflow", "favorite", if (workflow.isFavorite) 1 else 0, "boolean", workflow)
            row("workflow", "created", workflow.createdAtMs, "Unix ms", workflow)
            row("workflow", "updated", workflow.updatedAtMs, "Unix ms", workflow)
        }
        val totalRuns = runs.sumOf { it.runs }
        val terminal = runs.filter { it.status != StoredRunStatus.RUNNING.name }.sumOf { it.runs }
        val timed = runs.sumOf { it.timedRuns }
        val duration = runs.sumOf { it.durationMs }
        row("runs", "total", totalRuns, "runs")
        StoredRunStatus.entries.forEach { status -> row("runs", status.name.lowercase(), runs.filter { it.status == status.name }.sumOf { it.runs }, "runs") }
        row("runs", "completion_rate", if (terminal == 0L) null else runs.filter { it.status == StoredRunStatus.COMPLETED.name }.sumOf { it.runs } * 100.0 / terminal, "% of finished runs")
        row("runs", "timed_runs", timed, "runs")
        row("runs", "total_duration", duration, "ms")
        row("runs", "average_duration", if (timed == 0L) null else duration.toDouble() / timed, "ms")
        row("runs", "completed_steps", runs.sumOf { it.completedSteps }, "steps")
        row("runs", "planned_steps", runs.mapNotNull { it.plannedSteps }.takeIf { it.isNotEmpty() }?.sum(), "steps")
        row("runs", "runs_with_planned_steps", runs.sumOf { it.plannedRuns }, "runs")
        runs.forEach { run ->
            row("run_group", "count", run.runs, "runs", run = run)
            row("run_group", "timed_runs", run.timedRuns, "runs", run = run)
            row("run_group", "total_duration", run.durationMs, "ms", run = run)
            row("run_group", "average_duration", if (run.timedRuns == 0L) null else run.durationMs.toDouble() / run.timedRuns, "ms", run = run)
            row("run_group", "shortest_duration", run.shortestMs, "ms", run = run)
            row("run_group", "longest_duration", run.longestMs, "ms", run = run)
            row("run_group", "completed_steps", run.completedSteps, "steps", run = run)
            row("run_group", "planned_steps", run.plannedSteps, "steps", run = run)
            row("run_group", "runs_with_planned_steps", run.plannedRuns, "runs", run = run)
            row("run_group", "first_run", run.firstRunAtMs, "Unix ms", run = run)
            row("run_group", "last_run", run.lastRunAtMs, "Unix ms", run = run)
        }
        row("ai", "completed_replies", ai.sumOf { it.replies }, "replies")
        row("ai", "measured_replies", ai.sumOf { it.measuredReplies }, "replies")
        ai.forEach { model ->
            row("ai_model", "completed_replies", model.replies, "replies", model = model)
            row("ai_model", "measured_replies", model.measuredReplies, "replies", model = model)
            listOf("input_tokens" to model.input, "output_tokens" to model.output, "total_tokens" to model.totalTokens, "cached_input_tokens" to model.cached, "cache_write_tokens" to model.cacheWrite, "reasoning_tokens" to model.reasoning)
                .forEach { (metric, count) -> row("ai_model", metric, count, "tokens", model = model) }
            val rates = price(model.key)
            val cost = model.estimate(rates)
            row("ai_model", "estimated_cost_min", cost?.first, "USD", model = model)
            row("ai_model", "estimated_cost_max", cost?.second, "USD", model = model)
            row("ai_model", "rate_source", rates?.source, "", model = model)
            rates?.let {
                listOf("input_rate" to it.input, "output_rate" to it.output, "cached_input_rate" to it.cached, "cache_write_rate" to it.cacheWrite).forEach { (metric, rate) -> row("ai_model", metric, rate, "USD per 1M tokens", model = model) }
            }
            row("ai_model", "first_used", model.firstUsedAtMs, "Unix ms", model = model)
            row("ai_model", "last_used", model.lastUsedAtMs, "Unix ms", model = model)
        }
        writer.flush()
    }
}

internal fun Writer.csvRow(vararg fields: Any?) {
    write(
        fields.joinToString(",") { field ->
            val text = field?.toString().orEmpty()
            // Quoting alone does not stop spreadsheet formulas in user-provided names.
            val safe = if (field is String && (text.trimStart { it.isWhitespace() || it == '\uFEFF' }.firstOrNull() in listOf('=', '+', '-', '@') || text.firstOrNull() in listOf('\t', '\r', '\n'))) "'$text" else text
            "\"${safe.replace("\"", "\"\"")}\""
        },
    )
    write("\r\n")
}
