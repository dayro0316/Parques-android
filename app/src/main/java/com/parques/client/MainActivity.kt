package com.parques.client

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
        • Con un par (dados iguales) salen TODAS tus fichas de la cárcel y repites turno. Si en tu salida hay un rival, lo mandas a la cárcel.
        • Tres pares seguidos: eliges una ficha y la llevas directo a la meta.
        • Las casillas marrones (salidas y seguros) protegen tus fichas, salvo al salir de la cárcel. No hay bloqueos.
        • Soplada: si podías comer una ficha rival y no lo hiciste, pierdes la ficha que podía comer.
        • Si caes sobre una ficha rival fuera de un seguro, la mandas a la cárcel.
        • Gana quien lleve sus 4 fichas a la meta, en el centro del tablero.
    """.trimIndent()

    // Para vibración y sonido
    private var wasMyRoll = false
    private var lastMover: String? = null

    private var loginStatus: TextView? = null
    private var btnLogin: Button? = null
    private var btnRegister: Button? = null
    private var onRegistered: (() -> Unit)? = null     // la pantalla de inicio vuelve a «Ingresar» tras crear la cuenta
    private var remember = true                         // «Recordarme en este teléfono»
    private val friendly = mapOf(
        "Ese usuario ya existe" to "Ese usuario ya existe. Prueba con otro, o usa «Ingresar» si ya tienes cuenta.",
        "Usuario o clave incorrectos" to "Usuario o clave incorrectos. Revisa que estés en «Ingresar» y no en «Crear cuenta»."
    )

    private lateinit var boardView: BoardView
    private lateinit var tvLobby: TextView
    private lateinit var tvDice: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvTimer: TextView

    // Cuenta regresiva del turno (el servidor manda `turn_left` en cada estado)
    private var turnDeadline = 0L          // SystemClock.elapsedRealtime() en que vence; 0 = sin cuenta
    private val timerTick = object : Runnable {
        override fun run() {
            if (turnDeadline == 0L) {
                tvTimer.text = ""
                return
            }
            val left = maxOf(0L, (turnDeadline - SystemClock.elapsedRealtime() + 999) / 1000)
            tvTimer.text = "⏱ $left s"
            tvTimer.setTextColor(Color.parseColor(if (left <= 5) "#d50000" else "#1E2A44"))
            handler.postDelayed(this, 250)
        }
    }
    private lateinit var diceView: DiceView
    private var canMoveNow = false
    private val rollRegex = Regex("""lanza \d+ y \d+""")
    private lateinit var tvPlayers: TextView
    private lateinit var tvSync: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnRoll: Button
    private lateinit var btnRec: Button
    private lateinit var movesBox: LinearLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var spLevel: Spinner
    private lateinit var tvRoom: TextView
    private lateinit var btnLeave: Button
    private var recIndex = -1
    private var myColor = "red"
    private var sessionToken = ""
    private var roomName = "principal"
    private var roomsRequested = false
    private val levelKeys = listOf("easy", "normal", "hard")
    private val levelEs = mapOf("easy" to "fácil", "normal" to "normal", "hard" to "difícil")
    private lateinit var etChat: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLogin()
        // Si ya hay usuario guardado, entra solo.
        val u = prefs.getString("user", "") ?: ""
        val t = prefs.getString("token", "") ?: ""
        prefs.edit().remove("pass").apply()     // versiones anteriores guardaban la clave: ya no
        if (u.isNotEmpty() && t.isNotEmpty()) startAuth("resume_session", u, t)
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

    private fun icon(l: String) = when {
        l.contains("ganó la partida") -> "🏆"
        l.contains("Soplada") -> "🌬"
        l.contains("regresa la ficha") -> "💥"
        l.contains(" lanza ") -> "🎲"
        l.contains("saca la ficha") || l.contains("saca las fichas") -> "🔓"
        l.contains("Tiempo agotado") -> "⏱"
        l.contains("abandonó") || l.contains("se desconectó") -> "🚪"
        else -> "•"
    }

    private fun log(line: String) {
        tvLog.append((if (line.startsWith("⚠") || line.startsWith("💡")) "" else icon(line) + " ") + line + "\n")
        if (tvLog.text.length > 6000) tvLog.text = tvLog.text.takeLast(4000)
    }

    // ---------------------------------------------------------------- pantalla de login
    private fun showLogin() {
        val brown = Color.parseColor("#1E2A44")
        val cream = Color.parseColor("#EEF2FA")
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
            setTextColor(Color.parseColor("#5B6780"))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(10))
        })

        // Franja con los cuatro colores del juego
        val stripe = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in listOf("#E60000", "#FFE600", "#0A7D0A", "#0A0AE6")) {
            stripe.addView(View(this).apply { setBackgroundColor(Color.parseColor(c)) }, LinearLayout.LayoutParams(0, dp(6), 1f))
        }
        root.addView(stripe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))

        // ---- Campos ----
        var mode = "login"
        val nameRe = Regex("^[A-Za-z0-9_]{3,16}$")
        val grayText = Color.parseColor("#5B6780")

        val etUser = EditText(this).apply {
            hint = "3-16 letras, números o _"
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(16))
            setText(prefs.getString("user", ""))
        }
        val etPass = EditText(this).apply {
            hint = "Clave"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etPass2 = EditText(this).apply {
            hint = "Repite la clave"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun hintView() = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#7B8799")) }
        val hUser = hintView()
        val hPass = hintView()
        val hPass2 = hintView()

        // Clave con «ojo» para mostrarla u ocultarla
        fun passRow(et: EditText): LinearLayout {
            val eye = TextView(this).apply {
                text = "👁"
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(6), 0)
            }
            var shown = false
            eye.setOnClickListener {
                shown = !shown
                et.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                et.setSelection(et.text.length)
                eye.text = if (shown) "🙈" else "👁"
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(eye, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        fun field(label: String, input: View, hint: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(grayText)
            })
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val userField = field("Usuario", etUser, hUser)
        val passField = field("Clave", passRow(etPass), hPass)
        val confirmField = field("Repite la clave", passRow(etPass2), hPass2).apply { visibility = View.GONE }

        val cbRemember = CheckBox(this).apply {
            text = "Recordarme en este teléfono"
            isChecked = true
            textSize = 13f
            setTextColor(grayText)
        }

        // ---- Validación mientras se escribe (mismas reglas del servidor) ----
        fun setHint(h: TextView, msg: String, kind: Int) {   // kind: 0 neutro, 1 error, 2 correcto
            h.text = msg
            h.setTextColor(Color.parseColor(when (kind) { 1 -> "#C62828"; 2 -> "#0A7D0A"; else -> "#7B8799" }))
        }
        fun validate(): Boolean {
            val u = etUser.text.toString()
            val p = etPass.text.toString()
            val p2 = etPass2.text.toString()
            val reg = mode == "register"
            var ok = true
            val uBad = u.isNotEmpty() && !nameRe.matches(u)
            setHint(hUser, if (uBad) "Usa 3-16 letras, números o _ (sin espacios)" else "", 1)
            if (u.isEmpty() || uBad) ok = false
            if (reg) {
                val short = p.isNotEmpty() && p.length < 4
                setHint(hPass, if (short) "Mínimo 4 caracteres" else if (p.isNotEmpty()) "✔ Clave válida" else "", if (short) 1 else 2)
                if (p.isEmpty() || short) ok = false
                val diff = p2.isNotEmpty() && p2 != p
                setHint(hPass2, if (diff) "Las claves no coinciden" else if (p2.isNotEmpty()) "✔ Coinciden" else "", if (diff) 1 else 2)
                if (p2.isEmpty() || diff) ok = false
            } else {
                setHint(hPass, "", 0)
                setHint(hPass2, "", 0)
                if (p.isEmpty()) ok = false
            }
            return ok
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { validate() }
        }
        etUser.addTextChangedListener(watcher)
        etPass.addTextChangedListener(watcher)
        etPass2.addTextChangedListener(watcher)

        // ---- Botón principal ----
        val bGo = button("Ingresar") {
            if (!validate()) {
                toast(if (mode == "register") "Revisa los campos marcados" else "Escribe tu usuario y clave")
                return@button
            }
            remember = cbRemember.isChecked
            startAuth(mode, etUser.text.toString().trim(), etPass.text.toString())
        }.apply {
            setBackgroundColor(Color.parseColor("#0A7D0A"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        // ---- Pestañas «Ingresar» / «Crear cuenta» ----
        val tabLogin = Button(this).apply { text = "Ingresar"; isAllCaps = false }
        val tabReg = Button(this).apply { text = "Crear cuenta"; isAllCaps = false }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#DDE4F2"))
            }
            addView(tabLogin, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tabReg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        fun styleTab(b: Button, active: Boolean) {
            b.background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (active) Color.WHITE else Color.TRANSPARENT)
            }
            b.setTextColor(if (active) brown else grayText)
            b.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        fun setMode(m: String) {
            mode = m
            val reg = m == "register"
            styleTab(tabLogin, !reg)
            styleTab(tabReg, reg)
            confirmField.visibility = if (reg) View.VISIBLE else View.GONE
            bGo.text = if (reg) "Crear cuenta" else "Ingresar"
            loginStatus?.visibility = View.GONE
            validate()
        }
        tabLogin.setOnClickListener { setMode("login") }
        tabReg.setOnClickListener { setMode("register") }

        val full = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        root.addView(tabs, full())
        root.addView(userField, full())
        root.addView(passField, full())
        root.addView(confirmField, full())
        root.addView(cbRemember, full())
        root.addView(bGo, full())

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
        btnLogin = bGo
        btnRegister = tabReg
        setMode("login")
        // Registrarse no inicia sesión: se vuelve a «Ingresar» con el usuario ya escrito
        onRegistered = {
            setMode("login")
            etPass.setText("")
            etPass2.setText("")
            loginStatus?.let {
                it.visibility = View.VISIBLE
                it.text = "✔ Cuenta creada. Ahora inicia sesión con tu clave."
                it.setTextColor(Color.parseColor("#0A7D0A"))
            }
            etPass.requestFocus()
        }
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
            it.setTextColor(Color.parseColor("#1E2A44"))
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
        val send = {
            if (kind == "resume_session") client.send(kind, "user" to u, "token" to p)
            else client.send(kind, "user" to u, "password" to p)
        }
        if (client.isOpen) {
            send()
            return
        }
        val msg = if (attempt == 1) "🎲 Preparando la mesa…" else "Reintentando… ($attempt/$maxAttempts)"
        setBusy(true, msg)
        val mine = attempt
        handler.postDelayed({
            if (pending != null && user == null && attempt == mine && !client.isOpen) setBusy(true, "Ya casi, acomodando las fichas…")
        }, 6000)
        client.connect(GameClient.normalizeUrl(Config.SERVER_URL)) { send() }
    }

    private fun logout() {
        if (sessionToken.isNotEmpty()) client.send("logout", "token" to sessionToken)
        sessionToken = ""
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
        onRegistered = null

        val root = column()

        boardView = BoardView(this).also {
            it.user = user ?: ""
            it.onPieceTap = { slot -> onPieceTap(slot) }
        }
        root.addView(boardView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tvSync = TextView(this).apply { text = "Reloj: sin sincronizar"; textSize = 11f }
        root.addView(tvSync)

        // Sala
        tvRoom = TextView(this).apply { text = "🏠 $roomName"; textSize = 16f; setTypeface(null, Typeface.BOLD) }
        root.addView(row(tvRoom, button("🏠 Cambiar de sala") { roomsRequested = true; client.send("rooms") }))
        val first = Board.COLORS.take(2)
        val second = Board.COLORS.drop(2)
        for (group in listOf(first, second)) {
            root.addView(row(*group.map { c ->
                button("Unirse: ${Board.ES.getValue(c)}") { client.send("join", "color" to c) }
            }.toTypedArray()))
        }
        spLevel = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                levelKeys.map { "🤖 " + levelEs.getValue(it) })
            setSelection(1)
        }
        root.addView(row(
            spLevel,
            button("+ Bot") { client.send("add_bot", "level" to levelKeys[spLevel.selectedItemPosition]) },
            button("Listo") { client.send("ready") }
        ))
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
        tvTimer = TextView(this).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        tvPlayers = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        root.addView(diceView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tvDice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tvHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tvTimer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tvPlayers)
        btnRoll = button("Lanzar dados") { client.send("roll") }.apply { isEnabled = false }
        btnRec = button("Recomendar") { client.send("recommend") }.apply { isEnabled = false }
        root.addView(row(btnRoll, btnRec))
        root.addView(TextView(this).apply { text = "Jugadas posibles"; textSize = 14f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(8), 0, dp(2)) })
        movesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(movesBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        renderMoves(false)

        // Estadísticas
        root.addView(row(
            button("Ranking") { client.send("stats_top") },
            button("Mi prob.") { client.send("stats_prob") },
            button("Historial") { client.send("history") }
        ))

        // Chat en nubes (panel aparte del registro de la partida)
        root.addView(TextView(this).apply { text = "☁️ Chat"; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(10), 0, dp(2)) })
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        chatScroll = ScrollView(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#E3EAF7")) }
            setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
            addView(chatBox)
        }
        root.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        etChat = EditText(this).apply { hint = "Escribe un mensaje…"; setSingleLine() }
        root.addView(row(etChat, button("Enviar") { sendChat() }))

        // Historial de la partida
        root.addView(TextView(this).apply { text = "📜 Historial"; textSize = 14f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(10), 0, 0) })
        tvLog = TextView(this).apply { textSize = 12f }
        root.addView(tvLog)

        btnLeave = button("🚪 Salir de la partida") { askLeave() }.apply { visibility = View.GONE }
        root.addView(btnLeave)
        // Cerrar sesión (borra el usuario y el token guardados)
        root.addView(button("Cerrar sesión") { logout() })

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#EEF2FA")); addView(root) })
    }

    private fun sendChat() {
        val t = etChat.text.toString().trim()
        if (t.isNotEmpty()) {
            client.send("chat", "text" to t)
            etChat.setText("")
        }
    }

    private fun roundBg(fill: String, stroke: String? = null, r: Int = 12) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(Color.parseColor(fill))
        if (stroke != null) setStroke(dp(2), Color.parseColor(stroke))
    }

    /** Lista visible de jugadas: cada una es una tarjeta; al presionarla se resalta la ficha en el tablero. */
    private fun renderMoves(canMove: Boolean) {
        movesBox.removeAllViews()
        if (!canMove) {
            movesBox.addView(TextView(this).apply { text = "Aquí verás tus jugadas cuando lances los dados"; textSize = 12f; setTextColor(Color.parseColor("#7B8799")) })
            return
        }
        for (i in 0 until moves.length()) {
            val mv = moves.getJSONObject(i)
            val rec = i == recIndex
            val b = Button(this).apply {
                text = describe(mv) + if (rec) "   💡 recomendada" else ""
                isAllCaps = false
                textSize = 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(Color.parseColor("#1E2A44"))
                background = roundBg(if (rec) "#E4F7E9" else "#FFFFFF", if (rec) "#00C853" else "#C9D3E6")
                setOnClickListener { if (canMoveNow) client.send("move", "index" to i) }
                setOnTouchListener { _, ev ->
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> boardView.selected = mv
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> boardView.selected = null
                    }
                    false
                }
            }
            movesBox.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
    }

    /** Burbuja de chat con forma de nube: redondeada, con color según quién habla. */
    private fun addChat(who: String, text: String) {
        val me = who == user
        val bot = who.startsWith("Bot-")
        val fill = when {
            me -> "#CFE3FF"
            bot -> Board.HEX[who.removePrefix("Bot-")]?.let { blend(it) } ?: "#FFFFFF"
            else -> "#FFFFFF"
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            this.text = (if (bot) "🤖 " else "") + who
            textSize = 10f
            setTextColor(Color.parseColor("#5B6780"))
            gravity = if (me) Gravity.END else Gravity.START
            setPadding(dp(8), 0, dp(8), 0)
        })
        col.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#1E2A44"))
            background = roundBg(fill, null, 20)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        })
        chatBox.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (me) Gravity.END else Gravity.START
            topMargin = dp(4)
        })
        while (chatBox.childCount > 60) chatBox.removeViewAt(0)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Mezcla un color #rrggbb con blanco para el fondo pastel de las burbujas de los bots. */
    private fun blend(hex: String): String {
        val c = Color.parseColor(hex)
        fun mix(v: Int) = (v * 0.25 + 255 * 0.75).toInt()
        return String.format("#%02X%02X%02X", mix(Color.red(c)), mix(Color.green(c)), mix(Color.blue(c)))
    }

    private fun askLeave() {
        AlertDialog.Builder(this)
            .setTitle("🚪 ¿Salir de la partida?")
            .setMessage("Perderás tu lugar y no podrás volver a esta partida.")
            .setPositiveButton("Salir") { _, _ -> client.send("leave_game") }
            .setNegativeButton("Seguir jugando", null).show()
    }

    private fun resetGameUi() {
        state = null
        moves = JSONArray()
        seen = 0
        boardView.state = null
        boardView.movable = emptySet()
        boardView.selected = null
        btnRoll.isEnabled = false
        btnRec.isEnabled = false
        btnLeave.visibility = View.GONE
        canMoveNow = false
        tvHint.text = ""
        turnDeadline = 0L
        tvTimer.text = ""
        tvPlayers.text = ""
        wasMyRoll = false
        recIndex = -1
        renderMoves(false)
    }

    private fun showRooms(m: JSONObject) {
        val rows = m.getJSONArray("rows")
        val names = ArrayList<String>()
        val labels = ArrayList<CharSequence>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            names.add(r.getString("name"))
            labels.add("${r.getString("name")}  ·  👥 ${r.getInt("online")}  ·  " +
                (if (r.getBoolean("playing")) "en juego" else "abierta") +
                (if (r.getString("name") == roomName) "  (aquí)" else ""))
        }
        AlertDialog.Builder(this)
            .setTitle("🏠 Salas")
            .setItems(labels.toTypedArray()) { _, which -> client.send("change_room", "name" to names[which]) }
            .setPositiveButton("Crear sala") { _, _ ->
                val et = EditText(this).apply { hint = "Nombre (2-16 letras, números o _)"; setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(16)) }
                AlertDialog.Builder(this).setTitle("Nueva sala").setView(et)
                    .setPositiveButton("Entrar") { _, _ ->
                        val n = et.text.toString().trim()
                        if (n.isNotEmpty()) client.send("change_room", "name" to n)
                    }.setNegativeButton("Cancelar", null).show()
            }
            .setNegativeButton("Cerrar", null).show()
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
            "exit" -> if (mv.optInt("count", 1) > 1) "Sacar las ${mv.getInt("count")} fichas de la cárcel" else "Ficha $n: salir de la cárcel"
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
                        pending = Triple("resume_session", creds.first, creds.second)
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
                    setBusy(true, "Reintentando… ($attempt/$maxAttempts)")
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
                val pd = pending
                val tk = m.optString("token", "").ifEmpty { if (pd?.first == "resume_session") pd.third else sessionToken }
                sessionToken = tk
                if (remember && tk.isNotEmpty()) prefs.edit().putString("user", user).putString("token", tk).apply()
                else prefs.edit().clear().apply()
                lastCreds = (user ?: "") to tk
                roomName = m.optString("room", roomName)
                reconnectTries = 0
                pending = null
                wasMyRoll = false
                showGame()
            }
            "session_expired" -> {
                prefs.edit().remove("token").apply()
                val wasIn = user != null
                pending = null
                sessionToken = ""
                lastCreds = null
                if (wasIn) { user = null; state = null; client.close(); showLogin() }
                setBusy(false, null)
                toast("Tu sesión venció: inicia sesión de nuevo")
            }
            "rooms" -> if (user != null && roomsRequested) { roomsRequested = false; showRooms(m) }
            "room_changed" -> if (user != null) {
                roomName = m.getString("room")
                resetGameUi()
                tvLog.text = ""
                chatBox.removeAllViews()
                tvRoom.text = "🏠 $roomName"
                toast("Estás en la sala «$roomName»")
            }
            "left_game" -> if (user != null) { resetGameUi(); toast("Saliste de la partida") }
            "registered" -> {
                // registrarse no inicia sesión: el usuario debe pulsar «Ingresar»
                pending = null
                setBusy(false, null)
                onRegistered?.invoke()
            }
            "error" -> if (user == null) {
                val raw = m.optString("message")
                toast(friendly[raw] ?: raw)
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
                val winner = if (m.isNull("winner")) "nadie" else m.getString("winner")
                resetGameUi()
                AlertDialog.Builder(this)
                    .setTitle(if (winner == user) "🏆 ¡Ganaste!" else "🎉 Fin de la partida")
                    .setMessage(if (winner == user) "¡Felicitaciones! ¿Otra partida?" else "Ganó: $winner\n¿Otra partida?")
                    .setPositiveButton("🔁 Jugar otra vez") { _, _ -> client.send("join", "color" to myColor) }
                    .setNeutralButton("🏆 Ranking") { _, _ -> client.send("stats_top") }
                    .setNegativeButton("Cerrar", null).show()
            }
            "recommendation" -> if (user != null) {
                log("💡 " + m.getString("why"))
                recIndex = m.getInt("move")
                renderMoves(canMoveNow)
            }
            "chat" -> if (user != null) addChat(m.getString("user"), m.getString("text"))
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
        if (m.has("room")) { roomName = m.getString("room"); tvRoom.text = "🏠 $roomName" }
        for (c in seats.keys()) {
            val s = seats.getJSONObject(c)
            if (s.getString("name") == user) myColor = c
            lines.add(
                "${Board.ES[c] ?: c}: ${s.getString("name")}" +
                    (if (s.getBoolean("bot")) " 🤖 ${levelEs[s.optString("level", "normal")] ?: "normal"}" else "") +
                    (if (s.getBoolean("ready")) " ✔" else "")
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
            line.contains(" saca las fichas") -> lastMover = line.substringBefore(" saca las fichas")
            line.startsWith("$me lanza") && line.contains("¡par!") -> feedback.pair()
            line.contains(" regresa la ficha") && line.contains("a la cárcel") -> {
                val victim = line.removePrefix("¡").substringBefore(" regresa la ficha")
                if (victim == me) feedback.lost() else if (lastMover == me) feedback.captured()
            }
        }
    }

    private var seenEv = 0

    /** Avisos en pantalla cuando te comen una ficha, comes a alguien o te soplan. */
    private fun notify(e: JSONObject) {
        when (e.optString("kind")) {
            "capture" -> when {
                e.getString("victim") == user -> toast("💥 ¡${e.getString("by")} te comió la ficha ${e.getInt("piece")}!")
                e.getString("by") == user -> toast("🎯 ¡Mandaste a la cárcel a ${e.getString("victim")}!")
            }
            "blow" -> toast(
                if (e.getString("player") == user) "🌬 ¡Te soplaron! Pudiste comer y no comiste: pierdes la ficha ${e.getInt("piece")}"
                else "🌬 ¡${e.getString("player")} fue soplado!"
            )
        }
    }

    private fun onState(m: JSONObject) {
        val hadState = state != null
        state = m
        val evTotal = m.optInt("events_total", 0)
        if (!hadState) seenEv = evTotal
        else {
            val ne = evTotal - seenEv
            val evs = m.optJSONArray("events")
            if (ne > 0 && evs != null) for (i in maxOf(0, evs.length() - minOf(ne, 6)) until evs.length()) notify(evs.getJSONObject(i))
            seenEv = evTotal
        }
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

        // Aviso cuando pasa a ser tu turno de lanzar
        val myRoll = mine && phase == "roll"
        if (myRoll && !wasMyRoll) feedback.myTurn()
        wasMyRoll = myRoll

        moves = m.getJSONArray("moves")
        boardView.selected = null
        recIndex = -1
        renderMoves(canMove)
        val meP = myPieces()
        val inGame = !m.getBoolean("over") && meP != null
        btnLeave.visibility = if (inGame) View.VISIBLE else View.GONE

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
            canMove -> "👆 Toca una ficha resaltada, o elige una jugada de la lista"
            mine && phase == "roll" -> "Lanza los dados"
            else -> ""
        }

        // cuenta regresiva del turno
        handler.removeCallbacks(timerTick)
        turnDeadline = if (m.isNull("turn_left")) 0L
            else SystemClock.elapsedRealtime() + (m.getDouble("turn_left") * 1000).toLong()
        timerTick.run()

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
