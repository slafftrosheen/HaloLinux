package com.halo.sdk.provider

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ImageHttpPayload(val code: Int, val body: String, val retryAfter: String?)
/** Cancelling a draft also cancels its socket; response bodies are always closed. */
suspend fun Call.awaitImagePayload(): ImageHttpPayload = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val payload = response.use {
                    val text = StringBuilder()
                    response.body?.charStream()?.use { reader ->
                        val buffer = CharArray(8192)
                        var count = reader.read(buffer)
                        while (count >= 0) {
                            if (text.length + count > 48_000_000) throw IOException("Image response exceeded the supported size")
                            text.append(buffer, 0, count); count = reader.read(buffer)
                        }
                    }
                    ImageHttpPayload(response.code, text.toString(), response.header("Retry-After"))
                }
                if (continuation.isActive) continuation.resume(payload)
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    })
}
