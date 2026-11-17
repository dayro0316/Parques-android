package com.parques.client

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Capa de red: WebSocket contra el servidor (mismo protocolo JSON que los demás clientes).
 * Responde por sí misma a `time_req` (sincronización de Berkeley), sin pasar por la interfaz.
 */
class GameClient(private val listener: Listener) {

    interface Listener {
        /** Se llama en un hilo de red: la Activity debe pasar al hilo de UI. */
        fun onMessage(m: JSONObject)
        fun onClosed(reason: String)
    }

    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null

    @Volatile
    var isOpen = false
        private set

    /** Ajuste acumulado del reloj (segundos) calculado por Berkeley. */
    @Volatile
    var offset = 0.0
        private set

    fun connect(url: String, onReady: () -> Unit) {
        val request = Request.Builder().url(url).build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                onReady()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (e: Exception) { return }
                when (m.optString("type")) {
                    "time_req" -> send(
                        JSONObject().put("type", "time_resp").put("id", m.getString("id"))
                            .put("time", System.currentTimeMillis() / 1000.0 + offset)
                    )
                    "time_adjust" -> {
                        offset += m.getDouble("delta")
                        listener.onMessage(m)
                    }
                    else -> listener.onMessage(m)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                listener.onClosed(t.message ?: "error de red")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                listener.onClosed(reason.ifEmpty { "cerrado" })
            }
        })
    }

    fun send(o: JSONObject) {
        ws?.send(o.toString())
    }

    fun send(type: String, vararg fields: Pair<String, Any?>) {
        val o = JSONObject().put("type", type)
        for ((k, v) in fields) o.put(k, v)
        send(o)
    }

    fun close() {
        isOpen = false
        ws?.close(1000, null)
    }

    companion object {
        /** "192.168.1.5:8080" -> ws://192.168.1.5:8080/ws ; "wss://app.com" -> wss://app.com/ws */
        fun normalizeUrl(input: String): String {
            var s = input.trim()
            if (!s.startsWith("ws://") && !s.startsWith("wss://")) s = "ws://$s"
            s = s.trimEnd('/')
            if (!s.endsWith("/ws")) s += "/ws"
            return s
        }
    }
}
