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
 *
 * Cada conexión tiene un número de "generación": si se abre otra o se cierra esta,
 * los avisos tardíos de la conexión vieja se ignoran.
 */
class GameClient(private val listener: Listener) {

    interface Listener {
        /** Se llama en un hilo de red: la Activity debe pasar al hilo de UI. */
        fun onMessage(m: JSONObject)
        fun onClosed(reason: String)
    }

    // Timeout de conexión largo: Render gratis puede tardar ~1 minuto en despertar.
    private val http = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null

    @Volatile
    private var generation = 0

    @Volatile
    var isOpen = false
        private set

    /** Ajuste acumulado del reloj (segundos) calculado por Berkeley. */
    @Volatile
    var offset = 0.0
        private set

    fun connect(url: String, onReady: () -> Unit) {
        val gen = synchronized(this) {
            ws?.cancel()
            ws = null
            isOpen = false
            generation += 1
            generation
        }
        val request = Request.Builder().url(url).build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            private fun current() = gen == generation

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!current()) return
                isOpen = true
                onReady()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!current()) return
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
                if (!current()) return
                isOpen = false
                listener.onClosed(t.message ?: "error de red")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!current()) return
                isOpen = false
                listener.onClosed(reason.ifEmpty { "cerrado" })
            }
        })
        synchronized(this) {
            if (gen == generation) ws = socket else socket.cancel()
        }
    }

    fun send(o: JSONObject) {
        ws?.send(o.toString())
    }

    fun send(type: String, vararg fields: Pair<String, Any?>) {
        val o = JSONObject().put("type", type)
        for ((k, v) in fields) o.put(k, v)
        send(o)
    }

    /** Cierre voluntario: no genera aviso a la interfaz. */
    fun close() {
        synchronized(this) {
            generation += 1
            isOpen = false
            ws?.close(1000, null)
            ws = null
        }
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
