package com.example.prisonconnect.repository

import com.example.prisonconnect.config.SupabaseConfig
import android.util.Log
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

object DbService {

    @PublishedApi
    internal const val TAG = "DbService"

    @PublishedApi
    internal val client get() = SupabaseConfig.client

    //  READ: Get a single document by ID (UUID)
    suspend inline fun <reified T : Any> getDocument(
        table: String,
        id: String
    ): T? = withContext(Dispatchers.IO) {
        try {
            client.from(table).select {
                filter {
                    eq("id", id)
                }
                limit(1)
            }.decodeSingleOrNull<T>()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching document from $table by ID $id", e)
            null
        }
    }

    //  READ: Get a single document by any column
    suspend inline fun <reified T : Any> getDocumentByColumn(
        table: String,
        column: String,
        value: String
    ): T? = withContext(Dispatchers.IO) {
        try {
            client.from(table).select {
                filter {
                    eq(column, value)
                }
                limit(1)
            }.decodeSingleOrNull<T>()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching document from $table where $column=$value", e)
            null
        }
    }

    //  READ: Query documents with an equality filter
    suspend inline fun <reified T : Any> queryDocuments(
        table: String,
        field: String,
        value: Any
    ): List<T> = withContext(Dispatchers.IO) {
        try {
            client.from(table).select {
                filter {
                    eq(field, value.toString())
                }
            }.decodeList<T>()
        } catch (e: Exception) {
            Log.e(TAG, "Error querying documents from $table where $field=$value", e)
            emptyList()
        }
    }

    //  READ: Get all documents from a table
    suspend inline fun <reified T : Any> getAllDocuments(
        table: String,
        orderBy: String? = null,
        ascending: Boolean = true
    ): List<T> = withContext(Dispatchers.IO) {
        try {
            client.from(table).select {
                orderBy?.let { order(it, if (ascending) Order.ASCENDING else Order.DESCENDING) }
            }.decodeList<T>()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching all documents from $table", e)
            emptyList()
        }
    }

    //  WRITE: Upsert a document (insert or update)
    suspend inline fun <reified T : Any> upsertDocument(
        table: String,
        body: T
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.from(table).upsert(listOf(body))
            Unit
        }.onFailure { Log.e(TAG, "Error upserting to $table", it) }
    }

    //  WRITE: Insert a document
    suspend inline fun <reified T : Any> insertDocument(
        table: String,
        body: T
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.from(table).insert(listOf(body))
            Unit
        }.onFailure { Log.e(TAG, "Error inserting to $table", it) }
    }

    //  WRITE: Insert raw data as JsonObject
    suspend fun insertRaw(
        table: String,
        data: Map<String, Any>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonObject = buildJsonObject {
                data.forEach { (key, value) ->
                    put(key, jsonValue(value))
                }
            }
            client.from(table).insert(listOf(jsonObject))
            Unit
        }.onFailure { Log.e(TAG, "Error inserting raw to $table", it) }
    }

    //  WRITE: Update specific fields by UUID id
    suspend fun updateFields(
        table: String,
        id: String,
        fields: Map<String, Any>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
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
            Log.d(TAG, "Updated fields for $table ID $id")
            Unit
        }.onFailure { Log.e(TAG, "Error updating fields for $table ID $id", it) }
    }

    /**
     * Specifically updates the balance for a user.
     */
    suspend fun updateUserBalance(userId: String, balanceSeconds: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonObject = buildJsonObject {
                put("balance_remaining_seconds", JsonPrimitive(balanceSeconds))
            }
            client.from("users").update(jsonObject) {
                filter {
                    eq("id", userId)
                }
            }
            Log.d(TAG, "Synced balance to DB: $balanceSeconds s for user: $userId")
            Unit
        }.onFailure { Log.e(TAG, "Failed to update balance for user $userId", it) }
    }

    //  WRITE: Update specific fields by any column
    suspend fun updateFieldsByColumn(
        table: String,
        column: String,
        columnValue: String,
        fields: Map<String, Any>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
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
            Unit
        }.onFailure { Log.e(TAG, "Error updating $table where $column=$columnValue", it) }
    }

    //  DELETE: Remove a document
    suspend fun deleteDocument(
        table: String,
        id: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.from(table).delete {
                filter {
                    eq("id", id)
                }
            }
            Unit
        }.onFailure { Log.e(TAG, "Error deleting from $table ID $id", it) }
    }

    //  DELETE: Remove a document by any column
    suspend fun deleteByColumn(
        table: String,
        column: String,
        value: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.from(table).delete {
                filter {
                    eq(column, value)
                }
            }
            Unit
        }.onFailure { Log.e(TAG, "Error deleting from $table where $column=$value", it) }
    }

    //  Real-time listener (polling-based fallback)
    inline fun <reified T : Any> documentFlow(table: String, id: String, pollIntervalMs: Long = 3000L): kotlinx.coroutines.flow.Flow<T?> {
        return kotlinx.coroutines.flow.flow {
            while (true) {
                val doc: T? = getDocument(table, id)
                emit(doc)
                kotlinx.coroutines.delay(pollIntervalMs)
            }
        }
    }

    inline fun <reified T : Any> queryFlow(table: String, field: String, value: Any, pollIntervalMs: Long = 3000L): kotlinx.coroutines.flow.Flow<List<T>> {
        return kotlinx.coroutines.flow.flow {
            while (true) {
                val docs: List<T> = queryDocuments(table, field, value)
                emit(docs)
                kotlinx.coroutines.delay(pollIntervalMs)
            }
        }
    }

    //  Helper: Convert Any to JsonElement
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