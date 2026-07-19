package com.example.prisonconnect.repository

import com.example.prisonconnect.config.SupabaseConfig
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

/**
 * Centralized database service replacing FirebaseFirestore.
 * All calls route through SupabaseConfig.client (Supabase Postgrest).
 */
object DbService {

    @PublishedApi
    internal val client get() = SupabaseConfig.client

    // ─────────────────────────────────────────
    //  READ: Get a single document by ID (UUID)
    // ─────────────────────────────────────────
    suspend inline fun <reified T : Any> getDocument(
        table: String,
        id: String
    ): T? = withContext(Dispatchers.IO) {
        client.from(table).select {
            filter {
                eq("id", id)
            }
            limit(1)
        }.decodeSingleOrNull<T>()
    }

    // ─────────────────────────────────────────
    //  READ: Get a single document by any column
    //  (e.g. query call_rooms by room_id TEXT column)
    // ─────────────────────────────────────────
    suspend inline fun <reified T : Any> getDocumentByColumn(
        table: String,
        column: String,
        value: String
    ): T? = withContext(Dispatchers.IO) {
        client.from(table).select {
            filter {
                eq(column, value)
            }
            limit(1)
        }.decodeSingleOrNull<T>()
    }

    // ─────────────────────────────────────────
    //  READ: Query documents with an equality filter
    // ─────────────────────────────────────────
    suspend inline fun <reified T : Any> queryDocuments(
        table: String,
        field: String,
        value: Any
    ): List<T> = withContext(Dispatchers.IO) {
        client.from(table).select {
            filter {
                eq(field, value.toString())
            }
        }.decodeList<T>()
    }

    // ─────────────────────────────────────────
    //  READ: Get all documents from a table
    // ─────────────────────────────────────────
    suspend inline fun <reified T : Any> getAllDocuments(
        table: String,
        orderBy: String? = null,
        ascending: Boolean = true
    ): List<T> = withContext(Dispatchers.IO) {
        client.from(table).select {
            orderBy?.let { order(it, if (ascending) Order.ASCENDING else Order.DESCENDING) }
        }.decodeList<T>()
    }

    // ─────────────────────────────────────────
    //  WRITE: Upsert a document (insert or update)
    // ─────────────────────────────────────────
    suspend inline fun <reified T : Any> upsertDocument(
        table: String,
        body: T
    ): Unit = withContext(Dispatchers.IO) {
        client.from(table).upsert(listOf(body))
    }

    // ─────────────────────────────────────────
    //  WRITE: Insert a document
    // ─────────────────────────────────────────
    suspend inline fun <reified T : Any> insertDocument(
        table: String,
        body: T
    ): Unit = withContext(Dispatchers.IO) {
        client.from(table).insert(listOf(body))
    }

    // ─────────────────────────────────────────
    //  WRITE: Insert raw data as JsonObject
    //  (for dynamic maps that can't be @Serializable)
    // ─────────────────────────────────────────
    suspend fun insertRaw(
        table: String,
        data: Map<String, Any>
    ): Unit = withContext(Dispatchers.IO) {
        val jsonObject = buildJsonObject {
            data.forEach { (key, value) ->
                put(key, jsonValue(value))
            }
        }
        client.from(table).insert(listOf(jsonObject))
    }

    // ─────────────────────────────────────────
    //  WRITE: Update specific fields by UUID id
    //  Uses JsonObject to avoid serialization issues with Map<String, Any>
    // ─────────────────────────────────────────
    suspend fun updateFields(
        table: String,
        id: String,
        fields: Map<String, Any>
    ): Unit = withContext(Dispatchers.IO) {
        val jsonObject = buildJsonObject {
            fields.forEach { (key, value) ->
                put(key, jsonValue(value))
            }
        }
        client.from(table).update(jsonObject) {
            filter {
                eq("id", id)
            }
        }
    }

    // ─────────────────────────────────────────
    //  WRITE: Update specific fields by any column
    //  (e.g. update call_rooms where room_id = 'xxx')
    // ─────────────────────────────────────────
    suspend fun updateFieldsByColumn(
        table: String,
        column: String,
        columnValue: String,
        fields: Map<String, Any>
    ): Unit = withContext(Dispatchers.IO) {
        val jsonObject = buildJsonObject {
            fields.forEach { (key, value) ->
                put(key, jsonValue(value))
            }
        }
        client.from(table).update(jsonObject) {
            filter {
                eq(column, columnValue)
            }
        }
    }

    // ─────────────────────────────────────────
    //  DELETE: Remove a document
    // ─────────────────────────────────────────
    suspend fun deleteDocument(
        table: String,
        id: String
    ): Unit = withContext(Dispatchers.IO) {
        client.from(table).delete {
            filter {
                eq("id", id)
            }
        }
    }

    // ─────────────────────────────────────────
    //  Real-time listener (polling-based fallback)
    // ─────────────────────────────────────────
    inline fun <reified T : Any> documentFlow(
        table: String,
        id: String,
        pollIntervalMs: Long = 3000L
    ): kotlinx.coroutines.flow.Flow<T?> {
        return kotlinx.coroutines.flow.flow {
            while (true) {
                val doc: T? = getDocument(table, id)
                emit(doc)
                kotlinx.coroutines.delay(pollIntervalMs)
            }
        }
    }

    inline fun <reified T : Any> queryFlow(
        table: String,
        field: String,
        value: Any,
        pollIntervalMs: Long = 3000L
    ): kotlinx.coroutines.flow.Flow<List<T>> {
        return kotlinx.coroutines.flow.flow {
            while (true) {
                val docs: List<T> = queryDocuments(table, field, value)
                emit(docs)
                kotlinx.coroutines.delay(pollIntervalMs)
            }
        }
    }

    // ─────────────────────────────────────────
    //  Helper: Convert Any to JsonElement
    // ─────────────────────────────────────────
    private fun jsonValue(value: Any?): kotlinx.serialization.json.JsonElement {
        return when (value) {
            null -> JsonPrimitive("")
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                @Suppress("UNCHECKED_CAST")
                (value as Map<String, Any?>).forEach { (k, v) ->
                    put(k, jsonValue(v))
                }
            }
            is List<*> -> kotlinx.serialization.json.buildJsonArray {
                value.forEach { v ->
                    if (v != null) add(jsonValue(v))
                }
            }
            is kotlinx.serialization.json.JsonElement -> value
            else -> JsonPrimitive(value.toString())
        }
    }
}