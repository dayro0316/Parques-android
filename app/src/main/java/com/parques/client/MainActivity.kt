package com.parques.client

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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

    // Reconexión en partida: el servidor guarda el asiento ~60 s, así que reintentamos con las mismas credenciales.
    private var lastCreds: Pair<String, String>? = null
    private var reconnectTries = 0
    private val maxReconnects = 15

    private val RULES = """
        • Juegan de 2 a 4 personas, cada una con 4 fichas de un color. Puedes completar la mesa con bots.
        • Lanza los dos dados: cada dado se usa por separado para mover una ficha.
        • Con un par (dados iguales) puedes sacar una ficha de la cárcel y repites turno.
        • Tres pares seguidos: eliges una ficha y la llevas directo a la meta.
        • Las casillas marrones (salidas y seguros) protegen tus fichas.
        • Si caes sobre una ficha rival fuera de un seguro, la mandas a la cárcel.
        • Gana quien lleve sus 4 fichas a la meta, en el centro del tablero.
    """.trimIndent()

    // Para vibración y sonido
    private var wasMyRoll = false
    private var lastMover: String? = null

    private var loginStatus: TextView? = null
    private var btnLogin: Button? = null
    private var btnRegister: Button? = null

    private lateinit var boardView: BoardView
    private lateinit var tvLobby: TextView
    private lateinit var tvDice: TextView
    private lateinit var tvHint: TextView
    private lateinit var diceView: DiceView
    private var canMoveNow = false
    private val rollRegex = Regex("""lanza \d+ y \d+""")
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
        val brown = Color.parseColor("#5B2C13")
        val cream = Color.parseColor("#FDF3D0")
        val root = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        // Logo, nombre y frase
        root.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(110), dp(110)))
        root.addView(TextView(this).apply {
            text = "Parqués Online"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(brown)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Juega con tus amigos desde cualquier lugar"
            textSize = 14f
            setTextColor(Color.parseColor("#6D5A4A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(10))
        })

        // Franja con los cuatro colores del juego
        val stripe = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in listOf("#E60000", "#FFE600", "#0A7D0A", "#0A0AE6")) {
            stripe.addView(View(this).apply { setBackgroundColor(Color.parseColor(c)) }, LinearLayout.LayoutParams(0, dp(6), 1f))
        }
        root.addView(stripe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))

        // Datos de acceso
        val etUser = EditText(this).apply {
            hint = "Usuario"
            setSingleLine()
            setText(prefs.getString("user", ""))
        }
        val etPass = EditText(this).apply {
            hint = "Clave"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass", ""))
        }
        root.addView(etUser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(etPass, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bLogin = button("Ingresar") {
            startAuth("login", etUser.text.toString().trim(), etPass.text.toString())
        }.apply {
            setBackgroundColor(Color.parseColor("#0A7D0A"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        val bRegister = button("Crear cuenta") {
            startAuth("register", etUser.text.toString().trim(), etPass.text.toString())
        }
        root.addView(bLogin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bRegister, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Estado de la conexión (Conectando…, Reintentando…)
        val status = TextView(this).apply {
            textSize = 14f
            setTextColor(brown)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)

        root.addView(button("Cómo se juega") { dialog("Cómo se juega", RULES) })

        loginStatus = status
        btnLogin = bLogin
        btnRegister = bRegister
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(cream)
            addView(root)
        })
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
        lastCreds = null
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
        diceView = DiceView(this)
        tvDice = TextView(this).apply { text = "Dados: -"; textSize = 13f; gravity = Gravity.CENTER_HORIZONTAL }
        tvHint = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0a7d0a"))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        tvPlayers = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        root.addView(diceView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tvDice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tvHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tvPlayers)
        btnRoll = button("Lanzar dados") { client.send("roll") }.apply { isEnabled = false }
        btnRec = button("Recomendar") { client.send("recommend") }.apply { isEnabled = false }
        root.addView(row(btnRoll, btnRec))
        movesGroup = RadioGroup(this)
        movesGroup.setOnCheckedChangeListener { group, checkedId ->
            val rb = group.findViewById<RadioButton>(checkedId)
            boardView.selected = if (rb != null) moves.getJSONObject(rb.tag as Int) else null
        }
        movesGroup.visibility = View.GONE      // las jugadas se hacen tocando la ficha en el tablero
        root.addView(movesGroup)
        btnPlay = button("Jugar seleccionada") { play() }.apply { isEnabled = false; visibility = View.GONE }
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

    /**
     * Tocar una ficha propia la mueve directamente. Si esa ficha puede jugarse con cualquiera de los dos dados,
     * pregunta con cuál. Una ficha en la cárcel usa la salida disponible (vale cualquiera de las que están en la cárcel).
     */
    private fun onPieceTap(slot: Int) {
        if (!canMoveNow) return
        val opts = ArrayList<Int>()
        for (i in 0 until moves.length()) if (moves.getJSONObject(i).getInt("piece") == slot) opts.add(i)
        if (opts.isEmpty()) {
            val inJail = myPieces()?.let { slot < it.length() && it.getInt(slot) == Board.JAIL } ?: false
            if (inJail) {
                for (i in 0 until moves.length()) if (moves.getJSONObject(i).getString("kind") == "exit") opts.add(i)
            }
        }
        when {
            opts.isEmpty() -> tvHint.text = "Esa ficha no se puede mover con estos dados"
            opts.size == 1 -> client.send("move", "index" to opts[0])
            else -> {
                val labels = opts.map {
                    val mv = moves.getJSONObject(it)
                    if (mv.getString("kind") == "move") "Avanzar ${mv.getInt("die")}" else describe(mv)
                }.toTypedArray<CharSequence>()
                AlertDialog.Builder(this)
                    .setTitle("¿Con cuál dado?")
                    .setItems(labels) { _, which -> if (canMoveNow) client.send("move", "index" to opts[which]) }
                    .show()
            }
        }
    }

    private fun myPieces(): JSONArray? {
        val players = state?.getJSONArray("players") ?: return null
        for (i in 0 until players.length()) {
            val p = players.getJSONObject(i)
            if (p.getString("name") == user) return p.getJSONArray("pieces")
        }
        return null
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
            val creds = lastCreds
            if (user != null && creds != null && reconnectTries < maxReconnects) {
                reconnectTries += 1
                toast("Conexión perdida; reconectando… ($reconnectTries/$maxReconnects)")
                handler.postDelayed({
                    if (user != null && !client.isOpen) {
                        pending = Triple("login", creds.first, creds.second)
                        attempt = 1
                        sendAuth()
                    }
                }, 4000)
            } else if (user != null) {
                // Se cayó la conexión y no se pudo recuperar.
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
                    lastCreds = it.second to it.third
                }
                reconnectTries = 0
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
                canMoveNow = false
                boardView.movable = emptySet()
                tvHint.text = ""
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
        var freshRoll = false      // hubo un lanzamiento nuevo (de cualquier jugador): animar los dados
        if (n > 0) {
            for (i in maxOf(0, lines.length() - n) until lines.length()) {
                val line = lines.getString(i)
                log(line)
                react(line)
                if (n <= 3 && rollRegex.containsMatchIn(line)) freshRoll = true
            }
        }
        seen = total

        val current = if (m.isNull("current")) null else m.getString("current")
        val phase = m.getString("phase")
        val mine = !m.getBoolean("over") && current == user
        val canMove = mine && phase == "move"
        canMoveNow = canMove
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
        }

        // fichas tocables (con la salida de cárcel valen todas las que están en la cárcel)
        val movable = HashSet<Int>()
        if (canMove) {
            var hasExit = false
            for (i in 0 until moves.length()) {
                val mv = moves.getJSONObject(i)
                movable.add(mv.getInt("piece"))
                if (mv.getString("kind") == "exit") hasExit = true
            }
            val own = myPieces()
            if (hasExit && own != null) for (s in 0 until own.length()) if (own.getInt(s) == Board.JAIL) movable.add(s)
        }
        boardView.movable = movable
        tvHint.text = when {
            canMove && m.optBoolean("special") -> "¡Tres pares! Toca la ficha que quieres sacar del juego"
            canMove -> "👆 Toca una ficha resaltada para moverla"
            mine && phase == "roll" -> "Lanza los dados"
            else -> ""
        }

        val dice = m.optJSONArray("dice")
        if (dice != null) {
            val pend = m.optJSONArray("pending")
            val pendList = ArrayList<Int>()
            if (pend != null) for (i in 0 until pend.length()) pendList.add(pend.getInt(i))
            if (freshRoll) diceView.roll(dice.getInt(0), dice.getInt(1), pendList)
            else diceView.show(dice.getInt(0), dice.getInt(1), pendList)
        }
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
