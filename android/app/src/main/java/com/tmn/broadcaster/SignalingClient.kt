package com.tmn.broadcaster

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** シグナリングWorkerとのWebSocket接続を保持し、テキストメッセージの送受信を仲介する */
class SignalingClient(
    private val url: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed()
        fun onFailure(t: Throwable)
    }

    private val client = OkHttpClient()
    private var socket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t)
            }
        })
    }

    fun send(text: String) {
        socket?.send(text)
    }

    fun close() {
        socket?.close(1000, "closed by client")
        client.dispatcher.executorService.shutdown()
    }
}
