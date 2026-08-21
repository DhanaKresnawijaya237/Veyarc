@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.veyarc.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Strict JSON codec for the versioned workflow document. */
object WorkflowJson {
    val format: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
        allowTrailingComma = false
        allowComments = false
        allowSpecialFloatingPointValues = false
    }

    fun encode(document: WorkflowDocument): String {
        val canonicalDocument = document.copy(
            allowedPackages = document.allowedPackages.sortedBy(PackageName::value),
            assets = document.assets.sortedBy { it.assetId.value },
            variables = document.variables.toSortedMap(),
        )
        val element = format.encodeToJsonElement(WorkflowDocument.serializer(), canonicalDocument)
        return format.encodeToString(JsonElement.serializer(), canonicalize(element))
    }

    fun decode(json: String): WorkflowDocument {
        val element = format.parseToJsonElement(json)
        rejectNulls(element, "")
        return format.decodeFromJsonElement(WorkflowDocument.serializer(), element)
    }

    fun decodeAndValidate(json: String): WorkflowValidationResult {
        val document = decode(json)
        return WorkflowValidationResult(document, WorkflowValidator.validate(document))
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            val ordered = LinkedHashMap<String, JsonElement>()
            element["type"]?.let { ordered["type"] = canonicalize(it) }
            element.keys
                .filterNot { it == "type" }
                .sorted()
                .forEach { key -> ordered[key] = canonicalize(element.getValue(key)) }
            JsonObject(ordered)
        }

        is JsonArray -> JsonArray(element.map(::canonicalize))

        is JsonPrimitive -> element
    }

    private fun rejectNulls(element: JsonElement, path: String) {
        when (element) {
            JsonNull -> throw SerializationException("Null values are not allowed at ${path.ifEmpty { "/" }}")
            is JsonObject -> element.forEach { (key, value) -> rejectNulls(value, "$path/${escape(key)}") }
            is JsonArray -> element.forEachIndexed { index, value -> rejectNulls(value, "$path/$index") }
            is JsonPrimitive -> Unit
        }
    }

    private fun escape(segment: String): String = segment.replace("~", "~0").replace("/", "~1")
}

data class WorkflowValidationResult(
    val document: WorkflowDocument,
    val errors: List<ValidationError>,
) {
    val isValid: Boolean
        get() = errors.isEmpty()
}
