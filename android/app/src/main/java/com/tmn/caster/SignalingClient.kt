package com.tmn.caster

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * シグナリングWorkerとのWebSocket接続を保持し、テキストメッセージの送受信を仲介する。
 * 接続が予期せず切れた場合(viewer/app.jsと同様)は指数バックオフで自動的に再接続する。
 */
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private var socket: WebSocket? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var stopped = false

    fun connect() {
        stopped = false
        openSocket()
    }

    private fun openSocket() {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        mainHandler.postDelayed({
            if (!stopped) openSocket()
        }, delay)
    }

    fun send(text: String) {
        socket?.send(text)
    }

    /** サービス終了時など、意図的に切断する場合に呼ぶ。以後は再接続しない */
    fun close() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        socket?.close(1000, "closed by client")
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 15000L
    }
}
