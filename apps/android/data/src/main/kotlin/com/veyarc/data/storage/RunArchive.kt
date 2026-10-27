package com.veyarc.data.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class RunArchive(val record: StoredRunRecord, val workflowName: String) {
    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 1024L * 1024
        private const val RUN_ENTRY = "run.json"
        private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun export(record: StoredRunRecord, workflowName: String): ByteArray {
            require(record.status != StoredRunStatus.RUNNING) { "Finish the run before exporting it." }
            val json = JSONObject()
                .put("version", 1).put("runId", record.runId).put("workflowId", record.workflowId)
                .put("workflowName", workflowName).put("revision", record.revision).put("status", record.status.name)
                .put("startedAtMs", record.startedAtMs).put("finishedAtMs", record.finishedAtMs ?: JSONObject.NULL)
                .put("name", record.name ?: JSONObject.NULL).put("interruptionReason", record.interruptionReason ?: JSONObject.NULL)
                .put("completedSteps", record.completedSteps).put("totalSteps", record.totalSteps ?: JSONObject.NULL)
                .put("hasStoredDetails", record.hasStoredDetails)
                .put(
                    "events",
                    JSONArray().apply {
                        record.eventHistory.forEach { put(JSONObject().put("atMs", it.atMs).put("message", it.message).put("blockLabel", it.blockLabel).put("contextLabel", it.contextLabel).put("stepId", it.stepId)) }
                    },
                )
            val metadata = json.toString().encodeToByteArray()
            require(metadata.size <= MAX_METADATA_BYTES) { "This run's activity is too large to export." }
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(RUN_ENTRY).apply { time = 0 })
                zip.write(metadata)
                zip.closeEntry()
            }
            return output.toByteArray().also { require(it.size <= MAX_BYTES) { "Run archive is too large." } }
        }

        fun import(input: InputStream): RunArchive {
            // Bound compressed input as well as each expanded entry before parsing untrusted data.
            val limited = object : FilterInputStream(input) {
                var consumed = 0L
                override fun read(): Int = super.read().also { if (it >= 0) checkCount(1) }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) checkCount(it) }
                fun checkCount(count: Int) {
                    consumed += count
                    require(consumed <= MAX_BYTES) { "Run archive exceeds its size limit." }
                }
            }
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(limited).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name == RUN_ENTRY) { "Not a Veyarc run archive." }
                    require(entry.name !in entries) { "Run archive contains duplicate entries." }
                    entries[entry.name] = readLimited(zip, MAX_METADATA_BYTES)
                    zip.closeEntry()
                }
                val remainder = ByteArray(8 * 1024)
                while (limited.read(remainder) >= 0) { /* Include trailing input in the compressed-size limit. */ }
            }
            val metadata = requireNotNull(entries[RUN_ENTRY]) { "Run details are missing." }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(metadata)).toString()
            var depth = 0
            var quoted = false
            var escaped = false
            text.forEach { ch ->
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> quoted = false
                    }
                } else {
                    when (ch) {
                        '"' -> quoted = true

                        '{', '[' -> {
                            depth++
                            require(depth <= 8) { "Run metadata is nested too deeply." }
                        }

                        '}', ']' -> depth--
                    }
                }
            }
            val json = JSONObject(text)
            require(json.longValue("version") == 1L) { "This run archive version is not supported." }
            val status = StoredRunStatus.valueOf(json.getString("status"))
            require(status != StoredRunStatus.RUNNING) { "An active run cannot be imported." }
            val record = StoredRunRecord(
                runId = json.getString("runId"), workflowId = json.getString("workflowId"), revision = json.intValue("revision"),
                status = status, startedAtMs = json.longValue("startedAtMs"), finishedAtMs = json.optionalLong("finishedAtMs"),
                interruptionReason = json.optionalString("interruptionReason"), completedSteps = json.intValue("completedSteps"),
                totalSteps = json.optionalLong("totalSteps"), hasStoredDetails = json.getBoolean("hasStoredDetails"), folderId = null,
                name = json.optionalString("name"),
                eventHistory = json.getJSONArray("events").let { events ->
                    require(events.length() <= 10_000) { "Run archive contains too much activity." }
                    List(events.length()) { index ->
                        val event = events.getJSONObject(index)
                        StoredRunEvent(event.longValue("atMs"), event.getString("message"), event.optionalString("blockLabel"), event.optionalString("contextLabel"), event.optionalString("stepId"))
                    }
                },
            )
            require(uuid.matches(record.runId) && uuid.matches(record.workflowId)) { "Run identifiers are invalid." }
            require(record.revision > 0 && record.startedAtMs >= 0 && record.finishedAtMs != null && record.finishedAtMs >= 0) { "Run dates or revision are invalid." }
            require(record.completedSteps >= 0 && (record.totalSteps == null || record.totalSteps >= record.completedSteps)) { "Run step counts are invalid." }
            require(record.name == null || (record.name.isNotBlank() && record.name.length <= 128)) { "Run name is invalid." }
            require(record.eventHistory.all { it.atMs >= 0 && (it.blockLabel == null || it.blockLabel.length <= 256) && (it.contextLabel == null || it.contextLabel.length <= 2048) }) { "Run activity times are invalid." }
            require(record.eventHistory.all { it.stepId == null || uuid.matches(it.stepId) }) { "Run block references are invalid." }
            require(record.hasStoredDetails || record.eventHistory.isEmpty()) { "Run activity metadata is inconsistent." }
            val workflowName = json.getString("workflowName")
            require(workflowName.isNotBlank() && workflowName.length <= 128) { "Workflow name is invalid." }
            return RunArchive(record, workflowName)
        }

        private fun readLimited(input: InputStream, limit: Long): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                require(output.size().toLong() + count <= limit) { "Run archive exceeds its size limit." }
                output.write(buffer, 0, count)
            }
        }

        private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)
        private fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else longValue(key)
        private fun JSONObject.longValue(key: String): Long {
            val value = get(key)
            require(value is Int || value is Long) { "Run field '$key' must be an integer." }
            return (value as Number).toLong()
        }
        private fun JSONObject.intValue(key: String): Int = longValue(key).also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Run field '$key' is too large." } }.toInt()
    }
}
