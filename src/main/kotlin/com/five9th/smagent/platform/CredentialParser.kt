package com.five9th.smagent.platform

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Credential JSON is parsed via JsonNode (no Kotlin module needed) so adapters
 * can produce precise error messages for missing fields.
 */
object CredentialParser {

    private val mapper: JsonMapper = JsonMapper.builder().build()

    fun parse(credentialsJson: String, vararg required: String): Map<String, String> {
        val root = readTree(credentialsJson)
        val result = mutableMapOf<String, String>()
        for (field in required) {
            val node = root.get(field)
            if (node == null || node.isNull) {
                throw PlatformException("Credential field '$field' is missing")
            }
            result[field] = node.asString()
        }
        return result
    }

    /** All string fields of the blob (used for masking). */
    fun parseAll(credentialsJson: String): Map<String, String> {
        val root = readTree(credentialsJson)
        val result = mutableMapOf<String, String>()
        root.properties().forEach { (key, node) -> result[key] = node.asString() }
        return result
    }

    private fun readTree(credentialsJson: String): JsonNode = try {
        mapper.readTree(credentialsJson)
    } catch (e: Exception) {
        throw PlatformException("Stored credentials are not valid JSON", e)
    }
}
