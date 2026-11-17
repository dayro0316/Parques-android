package com.parques.client

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Cliente Android nativo del parqués distribuido (interfaz construida por código, sin XML de layouts). */
class MainActivity : Activity(), GameClient.Listener {

    private val client = GameClient(this)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("parques", MODE_PRIVATE) }
    private val feedback by lazy { Feedback(this) }

    private var user: String? = null
    private var state: JSONObject? = null
    private var moves = JSONArray()
    private var seen = 0

    // Inicio de sesión en curso: (tipo, usuario, clave)
    private var pending: Triple<String, String, String>? = null
    private var attempt = 0
    private val maxAttempts = 3

    // Para vibración y sonido
    private var wasMyRoll = false
    private var lastMover: String? = null

    private var loginStatus: TextView? = null
    private var btnLogin: Button? = null
    private var btnRegister: Button? = null

    private lateinit var boardView: BoardView
    private lateinit var tvLobby: TextView
    private lateinit var tvDice: TextView
    private lateinit var tvPlayers: TextView
    private lateinit var tvSync: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnRoll: Button
    private lateinit var btnRec: Button
    private lateinit var btnPlay: Button
    private lateinit var movesGroup: RadioGroup
    private lateinit var etChat: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLogin()
        // Si ya hay usuario guardado, entra solo.
        val u = prefs.getString("user", "") ?: ""
        val p = prefs.getString("pass", "") ?: ""
        if (u.isNotEmpty() && p.isNotEmpty()) startAuth("login", u, p)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        client.close()
        feedback.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- utilidades de UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun dialog(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private fun log(line: String) {
        tvLog.append(line + "\n")
        if (tvLog.text.length > 6000) tvLog.text = tvLog.text.takeLast(4000)
    }

    // ---------------------------------------------------------------- pantalla de login
    private fun showLogin() {
        val root = column()
        root.addView(TextView(this).apply { text = "Parqués Online"; textSize = 24f })
        val etUser = EditText(this).apply {
            hint = "Usuario"
            setText(prefs.getString("user", ""))
        }
        val etPass = EditText(this).apply {
            hint = "Clave"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass", ""))
        }
        root.addView(etUser)
        root.addView(etPass)

        val status = TextView(this).apply {
            textSize = 14f
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)

        val bLogin = button("Ingresar") {
            startAuth("login", etUser.text.toString().trim(), etPass.text.toString())
        }
        val bRegister = button("Registrarse") {
            startAuth("register", etUser.text.toString().trim(), etPass.text.toString())
        }
        root.addView(row(bLogin, bRegister))

        loginStatus = status
        btnLogin = bLogin
        btnRegister = bRegister
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Muestra u oculta el mensaje de estado y bloquea los botones mientras se conecta. */
    private fun setBusy(busy: Boolean, msg: String?) {
        loginStatus?.let {
            it.visibility = if (busy) View.VISIBLE else View.GONE
            it.text = msg ?: ""
        }
        btnLogin?.isEnabled = !busy
        btnRegister?.isEnabled = !busy
    }

    private fun startAuth(kind: String, u: String, p: String) {
        if (u.isEmpty() || p.isEmpty()) {
            toast("Escribe usuario y clave")
            return
        }
        if (Config.SERVER_URL.contains("CAMBIA")) {
            toast("Falta poner la dirección del servidor en Config.kt")
            return
        }
        pending = Triple(kind, u, p)
        attempt = 1
        sendAuth()
    }

    private fun sendAuth() {
        val (kind, u, p) = pending ?: return
        val send = { client.send(kind, "user" to u, "password" to p) }
        if (client.isOpen) {
            send()
            return
        }
        val msg = if (attempt == 1) {
            "Conectando con el servidor…\n(si estaba dormido puede tardar hasta 1 minuto)"
        } else {
            "Reintentando… ($attempt/$maxAttempts)\n(el servidor se está despertando)"
        }
        setBusy(true, msg)
        client.connect(GameClient.normalizeUrl(Config.SERVER_URL)) { send() }
    }

    private fun logout() {
        prefs.edit().clear().apply()
        handler.removeCallbacksAndMessages(null)
        client.close()
        user = null
        state = null
        pending = null
        moves = JSONArray()
        wasMyRoll = false
        showLogin()
    }

    // ---------------------------------------------------------------- pantalla de juego
    private fun showGame() {
        loginStatus = null
        btnLogin = null
        btnRegister = null

        val root = column()

        boardView = BoardView(this).also {
            it.user = user ?: ""
            it.onPieceTap = { slot -> onPieceTap(slot) }
        }
        root.addView(boardView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tvSync = TextView(this).apply { text = "Reloj: sin sincronizar"; textSize = 11f }
        root.addView(tvSync)

        // Sala
        root.addView(TextView(this).apply { text = "Sala"; textSize = 16f; setTypeface(null, Typeface.BOLD) })
        val first = Board.COLORS.take(2)
        val second = Board.COLORS.drop(2)
        for (group in listOf(first, second)) {
            root.addView(row(*group.map { c ->
                button("Unirse: ${Board.ES.getValue(c)}") { client.send("join", "color" to c) }
            }.toTypedArray()))
        }
        root.addView(row(button("+ Bot") { client.send("add_bot") }, button("Listo") { client.send("ready") }))
        tvLobby = TextView(this).apply { text = "Sala vacía" }
        root.addView(tvLobby)

        // Partida
        root.addView(TextView(this).apply { text = "Partida"; textSize = 16f; setTypeface(null, Typeface.BOLD) })
        tvDice = TextView(this).apply { text = "Dados: -"; textSize = 20f }
        tvPlayers = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        root.addView(tvDice)
        root.addView(tvPlayers)
        btnRoll = button("Lanzar dados") { client.send("roll") }.apply { isEnabled = false }
        btnRec = button("Recomendar") { client.send("recommend") }.apply { isEnabled = false }
        root.addView(row(btnRoll, btnRec))
        movesGroup = RadioGroup(this)
        movesGroup.setOnCheckedChangeListener { group, checkedId ->
            val rb = group.findViewById<RadioButton>(checkedId)
            boardView.selected = if (rb != null) moves.getJSONObject(rb.tag as Int) else null
        }
        root.addView(movesGroup)
        btnPlay = button("Jugar seleccionada") { play() }.apply { isEnabled = false }
        root.addView(btnPlay)

        // Estadísticas
        root.addView(row(
            button("Ranking") { client.send("stats_top") },
            button("Mi prob.") { client.send("stats_prob") },
            button("Historial") { client.send("history") }
        ))

        // Registro y chat
        tvLog = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }
        root.addView(tvLog)
        etChat = EditText(this).apply { hint = "Chat..." }
        root.addView(row(etChat, button("Enviar") { sendChat() }))

        // Cerrar sesión (borra el usuario guardado)
        root.addView(button("Cerrar sesión") { logout() })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sendChat() {
        val t = etChat.text.toString().trim()
        if (t.isNotEmpty()) {
            client.send("chat", "text" to t)
            etChat.setText("")
        }
    }

    private fun play() {
        val id = movesGroup.checkedRadioButtonId
        if (id != -1) {
            val idx = movesGroup.findViewById<RadioButton>(id).tag as Int
            client.send("move", "index" to idx)
        }
    }

    /** Tocar una ficha propia selecciona su primera jugada legal. */
    private fun onPieceTap(slot: Int) {
        for (i in 0 until moves.length()) {
            if (moves.getJSONObject(i).getInt("piece") == slot) {
                (movesGroup.getChildAt(i) as? RadioButton)?.isChecked = true
                return
            }
        }
    }

    private fun describe(mv: JSONObject): String {
        val n = mv.getInt("piece") + 1
        return when (mv.getString("kind")) {
            "exit" -> "Ficha $n: salir de la cárcel"
            "goal" -> "Ficha $n: llevar a la meta"
            else -> "Ficha $n: avanzar ${mv.getInt("die")}"
        }
    }

    // ---------------------------------------------------------------- mensajes del servidor
    override fun onMessage(m: JSONObject) {
        runOnUiThread { handle(m) }
    }

    override fun onClosed(reason: String) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            if (user != null) {
                // Se cayó la conexión durante la partida.
                toast("Conexión cerrada: $reason")
                user = null
                state = null
                pending = null
                wasMyRoll = false
                showLogin()
            } else if (pending != null) {
                // Falló al conectar: reintenta (Render puede estar despertando).
                if (attempt < maxAttempts) {
                    attempt += 1
                    setBusy(true, "Reintentando… ($attempt/$maxAttempts)\n(el servidor se está despertando)")
                    handler.postDelayed({
                        if (pending != null && user == null) sendAuth()
                    }, 3000)
                } else {
                    pending = null
                    setBusy(false, null)
                    toast("No se pudo conectar con el servidor. Revisa tu internet e inténtalo de nuevo.")
                }
            }
        }
    }

    private fun handle(m: JSONObject) {
        when (m.optString("type")) {
            "auth_ok" -> {
                user = m.getString("user")
                pending?.let {
                    prefs.edit().putString("user", it.second).putString("pass", it.third).apply()
                }
                pending = null
                wasMyRoll = false
                showGame()
            }
            "error" -> if (user == null) {
                toast(m.optString("message"))
                pending = null
                setBusy(false, null)
            } else {
                log("⚠ " + m.optString("message"))
            }
            "lobby" -> if (user != null) onLobby(m)
            "game_start" -> if (user != null) {
                seen = 0
                wasMyRoll = false
                lastMover = null
                log("Partida iniciada. Ajustes Berkeley: " + m.optJSONObject("sync"))
            }
            "time_adjust" -> if (user != null) {
                tvSync.text = "Reloj sincronizado (Berkeley): ajuste %+.4fs".format(m.getDouble("delta"))
            }
            "state" -> if (user != null) onState(m)
            "game_over" -> if (user != null) {
                state = null
                boardView.state = null
                btnRoll.isEnabled = false
                btnRec.isEnabled = false
                btnPlay.isEnabled = false
                wasMyRoll = false
                dialog("Fin de la partida", "Ganó: " + (if (m.isNull("winner")) "nadie" else m.getString("winner")))
            }
            "recommendation" -> if (user != null) {
                log("💡 " + m.getString("why"))
                (movesGroup.getChildAt(m.getInt("move")) as? RadioButton)?.isChecked = true
            }
            "chat" -> if (user != null) log("💬 ${m.getString("user")}: ${m.getString("text")}")
            "stats_top" -> if (user != null) {
                val rows = m.getJSONArray("rows")
                val sb = StringBuilder()
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    sb.append("${i + 1}. ${r.getString("user")}: ${r.getInt("wins")} victorias / ${r.getInt("games")} partidas\n")
                }
                dialog("Ranking", if (sb.isEmpty()) "Sin datos" else sb.toString())
            }
            "stats_prob" -> if (user != null) dialog(
                "Probabilidad",
                "%s: %.1f%% de ganar\n(%d victorias en %d partidas)".format(
                    m.getString("user"), m.getDouble("probability") * 100, m.getInt("wins"), m.getInt("games")
                )
            )
            "history" -> if (user != null) {
                val rows = m.getJSONArray("rows")
                val sb = StringBuilder()
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    sb.append("${r.getString("finished_at")}  ganó ${r.getString("winner")}${if (r.getBoolean("won")) " (tú)" else ""}\n")
                }
                dialog("Últimas partidas", if (sb.isEmpty()) "Aún no has jugado" else sb.toString())
            }
        }
    }

    private fun onLobby(m: JSONObject) {
        val seats = m.getJSONObject("seats")
        val lines = ArrayList<String>()
        for (c in seats.keys()) {
            val s = seats.getJSONObject(c)
            lines.add(
                "${Board.ES[c] ?: c}: ${s.getString("name")}" +
                    (if (s.getBoolean("bot")) " (bot)" else "") + (if (s.getBoolean("ready")) " ✔" else "")
            )
        }
        if (m.getBoolean("playing")) lines.add("— partida en curso, ingreso bloqueado —")
        tvLobby.text = if (lines.isEmpty()) "Sala vacía" else lines.joinToString("\n")
    }

    /** Vibración y sonido según las líneas nuevas del registro de la partida. */
    private fun react(line: String) {
        val me = user ?: return
        when {
            line.contains(" mueve la ficha") -> lastMover = line.substringBefore(" mueve la ficha")
            line.contains(" saca la ficha") -> lastMover = line.substringBefore(" saca la ficha")
            line.startsWith("$me lanza") && line.contains("¡par!") -> feedback.pair()
            line.contains(" regresa la ficha") && line.contains("a la cárcel") -> {
                val victim = line.removePrefix("¡").substringBefore(" regresa la ficha")
                if (victim == me) feedback.lost() else if (lastMover == me) feedback.captured()
            }
        }
    }

    private fun onState(m: JSONObject) {
        state = m
        boardView.state = m

        val total = m.getInt("log_total")
        val n = total - seen
        val lines = m.getJSONArray("log")
        if (n > 0) {
            for (i in maxOf(0, lines.length() - n) until lines.length()) {
                val line = lines.getString(i)
                log(line)
                react(line)
            }
        }
        seen = total

        val current = if (m.isNull("current")) null else m.getString("current")
        val phase = m.getString("phase")
        val mine = !m.getBoolean("over") && current == user
        val canMove = mine && phase == "move"
        btnRoll.isEnabled = mine && phase == "roll"
        btnRec.isEnabled = canMove
        btnPlay.isEnabled = canMove

        // Aviso cuando pasa a ser tu turno de lanzar
        val myRoll = mine && phase == "roll"
        if (myRoll && !wasMyRoll) feedback.myTurn()
        wasMyRoll = myRoll

        moves = m.getJSONArray("moves")
        movesGroup.removeAllViews()
        boardView.selected = null
        if (canMove) {
            for (i in 0 until moves.length()) {
                movesGroup.addView(RadioButton(this).apply {
                    text = describe(moves.getJSONObject(i))
                    tag = i
                    id = View.generateViewId()
                })
            }
            (movesGroup.getChildAt(0) as? RadioButton)?.isChecked = true
        }

        val dice = m.optJSONArray("dice")
        tvDice.text = if (dice != null) "Dados: ${dice.getInt(0)} + ${dice.getInt(1)}" else "Dados: -"

        val players = m.getJSONArray("players")
        val sb = StringBuilder()
        for (i in 0 until players.length()) {
            val p = players.getJSONObject(i)
            val pieces = p.getJSONArray("pieces")
            var jail = 0
            var goal = 0
            for (k in 0 until pieces.length()) {
                if (pieces.getInt(k) == Board.JAIL) jail++
                if (pieces.getInt(k) == Board.GOAL) goal++
            }
            sb.append(if (p.getString("name") == current) "▶ " else "   ")
                .append("${p.getString("name")} (${Board.ES[p.getString("color")]}) cárcel $jail · meta $goal")
                .append(if (p.getBoolean("active")) "" else " [fuera]")
                .append('\n')
        }
        tvPlayers.text = sb.toString().trimEnd()
    }
}
