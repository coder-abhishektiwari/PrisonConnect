package com.example.prisonconnect.data.remote

import com.example.prisonconnect.config.SupabaseConfig
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object StorageService {

    @PublishedApi
    internal val client get() = SupabaseConfig.client

    /**
     * Upload a file to the specified Supabase Storage bucket.
     * @param bucket The storage bucket name
     * @param path The destination path within the bucket
     * @param file The local file to upload
     * @return The public URL of the uploaded file
     */
    suspend fun uploadFile(
        bucket: String,
        path: String,
        file: File
    ): String = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        client.storage.from(bucket).upload(path, bytes, true)
        client.storage.from(bucket).publicUrl(path)
    }
}