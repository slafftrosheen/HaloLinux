package com.halo.sdk.runtime.embodiment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * An [EmbodimentTransport] implementation backed by an OkHttp [WebSocket].
 * Connects directly to BuddyBot's WebSocket server (e.g. ws://192.168.4.1/ws).
 */
class WebSocketEmbodimentTransport(
    val url: String,
    private val client: OkHttpClient = defaultClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : EmbodimentTransport {

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(5000, TimeUnit.MILLISECONDS)
                .pingInterval(10, TimeUnit.SECONDS)
                .build()
        }
    }

    private val _connectionState = MutableStateFlow(TransportConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<TransportConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    private var messageHandler: ((String) -> Unit)? = null
    private var bytesHandler: ((ByteArray) -> Unit)? = null

    override suspend fun connect() {
        if (_connectionState.value == TransportConnectionState.CONNECTING ||
            _connectionState.value == TransportConnectionState.CONNECTED
        ) {
            return
        }

        _connectionState.value = TransportConnectionState.CONNECTING

        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            _connectionState.value = TransportConnectionState.ERROR
            return
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = TransportConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    messageHandler?.invoke(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch {
                    bytesHandler?.invoke(bytes.toByteArray())
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = TransportConnectionState.DISCONNECTED
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = TransportConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = TransportConnectionState.ERROR
            }
        })
    }

    override suspend fun disconnect() {
        webSocket?.close(1000, "Disconnect requested")
        webSocket = null
        _connectionState.value = TransportConnectionState.DISCONNECTED
    }

    override suspend fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    override suspend fun send(bytes: ByteArray): Boolean {
        return webSocket?.send(bytes.toByteString()) ?: false
    }

    override fun onMessageReceived(handler: (String) -> Unit) {
        messageHandler = handler
    }

    override fun onBytesReceived(handler: (ByteArray) -> Unit) {
        bytesHandler = handler
    }
}
