package nl.toonshift.android

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private fun Context.alertVault() = Vault(this, "nas-alert-config", "toonshift-nas-alert-v1")
private fun Context.alertPrefs() = getSharedPreferences("nas-alert-status", Context.MODE_PRIVATE)

private fun TextToSpeech.offlineVoices(): List<Voice> =
    voices.orEmpty().filter { !it.isNetworkConnectionRequired &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        .sortedWith(compareByDescending<Voice> { it.locale == Locale.getDefault() }
            .thenByDescending { it.locale.language == Locale.getDefault().language }
            .thenByDescending { it.locale.language == "en" }
            .thenByDescending { it.quality }.thenBy { it.latency }.thenBy { it.name })

class BlissVoiceActivity : Activity() {
    private var engine: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,32,32,32) }
        val status = TextView(this).apply { text = "Loading offline voices…"; textSize = 18f }
        val options = android.widget.Spinner(this)
        val preview = Button(this).apply { text = "Preview voice"; isEnabled = false }
        val save = Button(this).apply { text = "Use this voice"; isEnabled = false }
        listOf(status,options,preview,save).forEach { panel.addView(it) }
        setContentView(panel)
        engine = TextToSpeech(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val tts = engine
                val voices = if (result == TextToSpeech.SUCCESS) tts?.offlineVoices().orEmpty() else emptyList()
                if (tts == null || voices.isEmpty()) {
                    status.text = "No offline voices are installed. Add an offline voice in Android’s text-to-speech settings."
                    return@runOnUiThread
                }
                status.text = "Bliss voice"
                val labels = listOf("Automatic · best match") + voices.map {
                    "${it.locale.getDisplayName(Locale.getDefault())} · ${it.name}"
                }
                options.adapter = android.widget.ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,labels)
                val savedVoice = alertPrefs().getString("bliss-voice",null)
                options.setSelection(voices.indexOfFirst { it.name == savedVoice } + 1)
                preview.isEnabled = true
                save.isEnabled = true
                preview.setOnClickListener {
                    val voice = voices.getOrNull(options.selectedItemPosition - 1) ?: voices.first()
                    tts.voice = voice
                    tts.setSpeechRate(0.94f)
                    val sample = if (voice.locale.language == "nl") "Dit is een voorbeeld van de stem van Bliss."
                        else "This is a preview of the Bliss voice."
                    tts.speak(sample,TextToSpeech.QUEUE_FLUSH,null,"bliss-preview")
                }
                save.setOnClickListener {
                    val selected = voices.getOrNull(options.selectedItemPosition - 1)
                    alertPrefs().edit().putString("bliss-voice",selected?.name).apply()
                    finish()
                }
            }
        }
    }

    override fun onPause() { engine?.stop(); super.onPause() }
    override fun onDestroy() { engine?.shutdown(); super.onDestroy() }
}

private data class AlertConfig(val url: String, val key: String, val pin: String) {
    fun client(): OkHttpClient {
        val builder = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(8, TimeUnit.SECONDS)
        if (pin.isNotEmpty()) {
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                override fun checkClientTrusted(chain: Array<X509Certificate>?, type: String?) {
                    throw CertificateException("Client certificate unsupported")
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>?, type: String?) {
                    val certificate = chain?.firstOrNull() ?: throw CertificateException("No NAS certificate")
                    certificate.checkValidity()
                    if (!MessageDigest.isEqual(sha256(certificate.encoded).toByteArray(), pin.toByteArray())) {
                        throw CertificateException("NAS certificate changed")
                    }
                }
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            val host = url.toHttpUrl().host
            builder.sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { name, _ -> name == host }
        }
        return builder.build()
    }

    companion object {
        fun parse(text: String, address: String): AlertConfig {
            val data = JSONObject(text)
            val url = (address.ifBlank { data.getString("url") }).trim().toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
            require(url.query == null && url.fragment == null && url.encodedPath.endsWith("/toonshift-home/"))
            val key = data.getString("key")
            val pin = data.optString("pin").takeUnless { it == "null" }.orEmpty()
            require(Regex("[0-9a-f]{64}").matches(key))
            require(pin.isEmpty() || Regex("[0-9a-f]{64}").matches(pin))
            return AlertConfig(url.toString(),key,pin)
        }
    }
}

class NasAlertsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var delivery: TextView
    private val refreshDelivery = object : Runnable {
        override fun run() {
            val prefs = alertPrefs()
            val checked = prefs.getLong("last-check", 0)
            val received = prefs.getLong("last-ring", 0)
            val current = System.currentTimeMillis()
            val status = when {
                !getSystemService(NotificationManager::class.java).areNotificationsEnabled() ->
                    "Android has blocked ToonShift notifications."
                checked > 0 && current - checked > 60_000 ->
                    "No recent check-in. Start listening and check Tailscale."
                else -> prefs.getString("status", "Alerts are off.").orEmpty()
            }
            val lastRing = if (received == 0L) "No ring received on this phone yet."
                else "Last ring received " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(received))
            delivery.text = "$status\n$lastRing"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshDelivery)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshDelivery)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ToonShift Home"
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset,inset,inset,inset)
        }
        val titleView = TextView(this).apply { text = "NAS alerts"; textSize = 26f }
        val help = TextView(this).apply {
            text = "In ToonShift Home, choose Pair Android. Paste the pairing text here. Keep Tailscale connected when away."
        }
        val pairing = EditText(this).apply {
            hint = "Paste NAS pairing text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val address = EditText(this).apply {
            hint = "Optional HTTPS address over Tailscale"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val feedback = TextView(this)
        delivery = TextView(this)
        val enable = Button(this).apply { text = "Start listening" }
        val stop = Button(this).apply { text = "Stop listening" }
        val notificationSettings = Button(this).apply {
            text = "Incoming alert sound settings"
            setOnClickListener {
                val settings = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "nas-rings")
                startActivity(settings)
            }
        }
        val existing = runCatching { alertVault().load().singleOrNull() }.getOrNull()
        if (existing != null) address.setText(existing.host)
        enable.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                return@setOnClickListener
            }
            try {
                val pending = pairing.text.toString().trim()
                if (pending.isNotEmpty()) {
                    val config = AlertConfig.parse(pending,address.text.toString())
                    alertVault().save(listOf(Toon("nas","NAS",config.url,config.pin,config.key,"")))
                    alertPrefs().edit().remove("cursor").remove("cursor-time").apply()
                    pairing.text.clear()
                } else {
                    require(alertVault().load().isNotEmpty())
                }
                startForegroundService(Intent(this,NasAlertService::class.java))
                feedback.text = "Connecting. Use Test ring on the NAS dashboard."
            } catch (_: Exception) {
                feedback.text = "Could not start. Check the pairing text and HTTPS address."
            }
        }
        stop.setOnClickListener {
            stopService(Intent(this,NasAlertService::class.java))
            feedback.text = "Alerts are off."
        }
        val voiceSettings = Button(this).apply {
            text = "Bliss voice"
            setOnClickListener { startActivity(Intent(context,BlissVoiceActivity::class.java)) }
        }
        listOf(titleView,help,pairing,address,enable,stop,notificationSettings,voiceSettings,feedback,delivery).forEach { panel.addView(it) }
        setContentView(ScrollView(this).apply { addView(panel) })
    }
}

class NasAlertService : Service() {
    private var worker: Thread? = null
    private val manager get() = getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serviceChannel = NotificationChannel("nas-link", "ToonShift NAS connection", NotificationManager.IMPORTANCE_LOW)
        val ringChannel = NotificationChannel("nas-rings", "ToonShift incoming alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        }
        manager.createNotificationChannels(listOf(serviceChannel,ringChannel))
        startForeground(18080, linkNotification("Connecting to your NAS"))
        worker?.interrupt()
        worker = Thread({ listen() }, "toonshift-nas-alerts").also { it.start() }
        return START_NOT_STICKY
    }

    private fun settingsIntent() = PendingIntent.getActivity(this, 0,
        Intent(this,NasAlertsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun linkNotification(text: String): Notification = Notification.Builder(this,"nas-link")
        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("ToonShift Home alerts")
        .setContentText(text).setContentIntent(settingsIntent()).setOngoing(true).build()

    private fun updateConnection(text: String) {
        if (Thread.currentThread().isInterrupted) return
        alertPrefs().edit().putString("status",text).apply()
        manager.notify(18080,linkNotification(text))
    }

    private fun listen() {
        val saved = runCatching { alertVault().load().singleOrNull() }.getOrNull()
        if (saved == null) { stopSelf(); return }
        val config = AlertConfig(saved.host,saved.token,saved.pin)
        val client = config.client()
        val route = config.url.toHttpUrl().resolve("api/v1/phone/alerts") ?: return
        var failures = 0
        while (!Thread.currentThread().isInterrupted) {
            try {
                val request = Request.Builder().url(route).header("Authorization","Bearer ${config.key}").build()
                client.newCall(request).execute().use { response ->
                    if (Thread.currentThread().isInterrupted) return
                    if (response.code == 401) {
                        updateConnection("NAS access revoked. Pair again in Devices.")
                        return
                    }
                    check(response.isSuccessful)
                    alertPrefs().edit().putLong("last-check",System.currentTimeMillis()).apply()
                    val body = JSONObject(response.body?.string().orEmpty())
                    val alerts = body.getJSONArray("alerts")
                    val serverTime = body.getLong("server_time")
                    var cursor = alertPrefs().getLong("cursor",0)
                    var cursorTime = alertPrefs().getLong("cursor-time",0)
                    for (index in alerts.length() - 1 downTo 0) {
                        val alert = alerts.getJSONObject(index)
                        val id = alert.getLong("id")
                        val received = alert.getLong("received")
                        if (received < cursorTime || received == cursorTime && id <= cursor) continue
                        if (serverTime - received in 0..120) {
                            alertPrefs().edit().putLong("last-ring",System.currentTimeMillis()).apply()
                            if (notificationIssue() == null) ring(alert)
                            else break
                        }
                        cursor = id
                        cursorTime = received
                    }
                    alertPrefs().edit().putLong("cursor",cursor).putLong("cursor-time",cursorTime).apply()
                }
                failures = 0
                updateConnection(notificationIssue() ?: "Connected. Listening for home alerts.")
            } catch (_: Exception) {
                failures = (failures + 1).coerceAtMost(5)
                updateConnection("NAS unavailable. Check Tailscale or the HTTPS certificate.")
            }
            try { Thread.sleep((10L + 5L * failures) * 1000) }
            catch (_: InterruptedException) { return }
        }
    }

    private fun notificationIssue(): String? {
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel("nas-rings")?.importance == NotificationManager.IMPORTANCE_NONE) {
            return "Connected, but Android is blocking incoming alerts. Open sound settings."
        }
        return null
    }

    private fun ring(alert: JSONObject) {
        val id = (alert.getLong("id") % 100000).toInt() + 20000
        val rule = alert.getString("rule")
        if (rule !in setOf("electricity_high", "gas_high", "heat_away", "test_ring")) return
        val greeting = alert.optString("greeting").takeUnless { it.isBlank() || it == "null" }
        val answer = PendingIntent.getActivity(this,id,Intent(this,NasAlertAnswerActivity::class.java)
            .putExtra("text",greeting).putExtra("notification",id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val decline = PendingIntent.getBroadcast(this,id, Intent(this,NasDeclineReceiver::class.java)
            .putExtra("notification",id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this,"nas-rings").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentTitle("Bliss · ToonShift Home").setContentText(greeting ?: "Voice greeting unavailable")
            .setContentIntent(answer).setAutoCancel(true).setTimeoutAfter(45_000)
        if (Build.VERSION.SDK_INT >= 31) {
            val caller = android.app.Person.Builder().setName("Bliss").setImportant(true).build()
            builder.setStyle(Notification.CallStyle.forIncomingCall(caller,decline,answer))
        } else {
            builder.addAction(Notification.Action.Builder(null,"Decline",decline).build())
                .addAction(Notification.Action.Builder(null,"Answer",answer).build())
        }
        manager.notify(id,builder.build().apply { flags = flags or Notification.FLAG_INSISTENT })
    }

    override fun onDestroy() {
        worker?.interrupt()
        alertPrefs().edit().putString("status","Alerts are off.").apply()
        super.onDestroy()
    }
}

class NasDeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("notification",-1))
    }
}

class NasAlertAnswerActivity : Activity() {
    private var speech: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var offlineModel: org.vosk.Model? = null
    private var offlineRecognizer: org.vosk.Recognizer? = null
    private var offlineSpeech: org.vosk.android.SpeechService? = null
    private lateinit var downloadModel: Button
    private val speechLanguage get() = Locale.getDefault().language
    private var speaking = false
    private var pendingId: String? = null
    private var handsFree = false
    private var active = false
    private val history = ArrayDeque<JSONObject>()
    private lateinit var alert: String
    private lateinit var reply: EditText
    private lateinit var status: TextView
    private lateinit var send: Button
    private lateinit var approve: Button
    private lateinit var dismissProposal: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alert = intent.getStringExtra("text").orEmpty()
        getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("notification",-1))
        val heading = TextView(this).apply { text = alert.ifBlank { "Bliss voice greeting unavailable" }; textSize = 24f }
        status = TextView(this).apply { textSize = 17f }
        reply = EditText(this).apply {
            hint = "Reply, e.g. lower the room temperature"
            filters = arrayOf(android.text.InputFilter.LengthFilter(450))
        }
        send = Button(this).apply { text = "Send reply"; setOnClickListener { sendReply() } }
        approve = Button(this).apply {
            text = "Approve change"
            visibility = android.view.View.GONE
            setOnClickListener { pendingId?.let { approveTarget(it) } }
        }
        dismissProposal = Button(this).apply {
            text = "Dismiss proposal"
            visibility = android.view.View.GONE
            setOnClickListener { clearProposal(); status.text = "Proposal dismissed." }
        }
        downloadModel = Button(this).apply {
            text = "Download offline speech (about 40 MB)"
            visibility = android.view.View.GONE
            setOnClickListener {
                isEnabled = false
                status.text = "Downloading the speech model…"
                Thread {
                    val result = runCatching { OfflineSpeechModel.download(applicationContext,speechLanguage) }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        isEnabled = true
                        if (result.isSuccess) {
                            visibility = android.view.View.GONE
                            status.text = "Offline speech is ready. Tap Speak reply."
                        } else status.text = "Speech model download failed. Check your connection and try again."
                    }
                }.start()
            }
        }
        val voice = Button(this).apply { text = "Speak reply"; setOnClickListener { listenForReply() } }
        val automatic = android.widget.Switch(this).apply {
            text = "Hands-free conversation"
            setOnCheckedChangeListener { _, enabled ->
                handsFree = enabled
                if (enabled && speech?.isSpeaking != true && send.isEnabled && pendingId == null) listenForReply()
                if (!enabled) stopListening()
            }
        }
        val close = Button(this).apply { text = "End call"; setOnClickListener { finish() } }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset,inset,inset,inset)
        }
        listOf(heading,automatic,reply,voice,downloadModel,send,status,approve,dismissProposal,close).forEach { panel.addView(it) }
        setContentView(ScrollView(this).apply { addView(panel) })
        speech = TextToSpeech(this) { result ->
            val engine = speech
            if (result == TextToSpeech.SUCCESS && engine != null) {
                val offline = engine.offlineVoices()
                val savedVoice = alertPrefs().getString("bliss-voice",null)
                val selected = offline.firstOrNull { it.name == savedVoice } ?: offline.firstOrNull()
                if (selected != null) {
                    engine.voice = selected
                    engine.setSpeechRate(0.94f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onError(id: String?) = Unit
                        override fun onDone(id: String?) {
                            runOnUiThread { if (active && handsFree && send.isEnabled && pendingId == null) listenForReply() }
                        }
                    })
                    speaking = true
                    if (active && alert.isNotBlank()) speak(alert)
                }
            }
        }
    }

    private fun speak(text: String) {
        if (active && speaking) speech?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"toonshift-alert")
    }

    private fun listenForReply() {
        if (!active || !send.isEnabled || pendingId != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        speech?.stop()
        stopListening()
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            startOfflineReply()
            return
        }
        recognizer?.destroy()
        val local = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }.getOrElse {
            startOfflineReply()
            return
        }
        recognizer = local
        local.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                acceptTranscript(text)
            }
            override fun onError(error: Int) {
                if (recognizer !== local || !active || error == SpeechRecognizer.ERROR_CLIENT) return
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    status.text = "No speech recognized. Tap Speak reply to try again."
                else startOfflineReply()
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        local.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true))
    }

    private fun acceptTranscript(text: String?) {
        if (!active) return
        if (text.isNullOrBlank()) {
            status.text = "No speech recognized. Try again or type a reply."
            return
        }
        reply.setText(text.take(450))
        if (handsFree && send.isEnabled && pendingId == null) sendReply()
        else status.text = "Review the text and tap Send reply."
    }

    private fun stopListening() {
        recognizer?.cancel()
        offlineSpeech?.cancel()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startOfflineReply() {
        val modelPath = OfflineSpeechModel.installed(this,speechLanguage)
        if (modelPath == null) {
            downloadModel.visibility = android.view.View.VISIBLE
            status.text = "Android offline speech is unavailable for this language. Download the local speech model or type a reply."
            return
        }
        recognizer?.destroy()
        recognizer = null
        offlineSpeech?.shutdown()
        offlineRecognizer?.close()
        offlineSpeech = null
        offlineRecognizer = null
        try {
            val model = offlineModel ?: org.vosk.Model(modelPath.absolutePath).also { offlineModel = it }
            val decoder = org.vosk.Recognizer(model,16_000f).also { offlineRecognizer = it }
            val service = org.vosk.android.SpeechService(decoder,16_000f).also { offlineSpeech = it }
            val listener = object : org.vosk.android.RecognitionListener {
                private var finished = false
                private fun accept(hypothesis: String?) {
                    if (finished || offlineSpeech !== service || !active) return
                    val text = runCatching { JSONObject(hypothesis.orEmpty()).optString("text") }.getOrNull()
                    if (text.isNullOrBlank()) return
                    finished = true
                    service.cancel()
                    acceptTranscript(text)
                }
                override fun onPartialResult(hypothesis: String?) = Unit
                override fun onResult(hypothesis: String?) = accept(hypothesis)
                override fun onFinalResult(hypothesis: String?) = accept(hypothesis)
                override fun onError(exception: Exception?) {
                    if (offlineSpeech === service && active) status.text = "Could not start offline recognition."
                }
                override fun onTimeout() {
                    if (!finished && offlineSpeech === service && active) status.text = "No speech recognized. Tap Speak reply again."
                }
            }
            if (service.startListening(listener,12_000)) status.text = "Listening offline…"
        } catch (_: Exception) {
            status.text = "Could not start offline recognition. You can type a reply."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,results)
        if (requestCode == 2 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) listenForReply()
    }

    private fun clearProposal() {
        pendingId = null
        approve.visibility = android.view.View.GONE
        dismissProposal.visibility = android.view.View.GONE
    }

    private fun sendReply() {
        val text = reply.text.toString().trim()
        if (text.isEmpty() || !send.isEnabled || !active) return
        stopListening()
        clearProposal()
        perform("reply", JSONObject().put("question",text)
            .put("alert_context",alert.take(180)).put("language",Locale.getDefault().language)
            .put("history",org.json.JSONArray(history.toList()))) { result ->
            val answer = result.optString("answer")
            if (result.optBoolean("generated")) {
                history.addLast(JSONObject().put("role","user").put("text",text))
                history.addLast(JSONObject().put("role","assistant").put("text",answer))
                while (history.size > 6) history.removeFirst()
                reply.text.clear()
            }
            val proposed = result.optJSONObject("proposal")
            if (proposed == null) {
                status.text = answer
                if (result.optBoolean("generated")) speak(answer)
            } else {
                pendingId = proposed.getString("id")
                val before = degrees(proposed.getInt("current_centi_celsius"))
                val target = degrees(proposed.getInt("target_centi_celsius"))
                val change = "Change room target from $before to $target?"
                status.text = "$answer\n\n$change"
                approve.visibility = android.view.View.VISIBLE
                dismissProposal.visibility = android.view.View.VISIBLE
                if (result.optBoolean("generated")) speak(answer)
            }
        }
    }

    private fun degrees(value: Int) = java.text.NumberFormat.getNumberInstance().format(value / 100.0) + " °C"

    private fun approveTarget(id: String) {
        if (getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) {
            status.text = "Unlock your phone to approve the room target."
            return
        }
        clearProposal()
        perform("approve", JSONObject().put("id",id)) { result ->
            val actual = result.optInt("actual_centi_celsius",0)
            val message = when {
                result.optBoolean("grant_rejected") -> "The Toon rejected control access."
                result.optBoolean("confirmed") -> "Toon confirmed ${degrees(actual)}."
                actual > 0 -> "Change unconfirmed. Toon reports ${degrees(actual)}."
                else -> "The Toon could not confirm the change."
            }
            status.text = message

        }
    }

    private fun perform(action: String, data: JSONObject, onSuccess: (JSONObject) -> Unit) {
        send.isEnabled = false
        status.text = if (action == "reply") "Waiting for your NAS…" else "Checking the Toon…"
        Thread {
            val result = runCatching {
                val saved = alertVault().load().single()
                val config = AlertConfig(saved.host,saved.token,saved.pin)
                val endpoint = requireNotNull(config.url.toHttpUrl().resolve("api/v1/phone/$action"))
                val request = Request.Builder().url(endpoint).header("Authorization","Bearer ${config.key}")
                    .post(data.toString().toRequestBody("application/json".toMediaType())).build()
                config.client().newBuilder().callTimeout(32,TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { response ->
                        val message = when (response.code) {
                            401 -> "Phone access expired. Pair again from the NAS dashboard."
                            403 -> "Allow phone replies from the NAS dashboard."
                            409, 410 -> "The target changed or the proposal expired. Send a fresh reply."
                            429 -> "The NAS daily AI allowance is used. Check Settings in the ToonShift Home dashboard for the count and reset time."
                            503 -> "DeepSeek is disabled or unavailable. Check the NAS dashboard."
                            else -> "The NAS could not complete the request."
                        }
                        if (!response.isSuccessful) throw IllegalStateException(message)
                        JSONObject(response.body?.string().orEmpty())
                    }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                send.isEnabled = true
                result.fold(
                    onSuccess = onSuccess,
                    onFailure = { error ->
                        status.text = if (error is IllegalStateException) error.message
                            else "Cannot reach the NAS. Check Tailscale."
                    })
            }
        }.start()
    }
    override fun onResume() {
        super.onResume()
        active = true
    }

    override fun onPause() {
        active = false
        stopListening()
        speech?.stop()
        super.onPause()
    }
    override fun onDestroy() {
        recognizer?.destroy()
        offlineSpeech?.shutdown()
        offlineRecognizer?.close()
        offlineModel?.close()
        speech?.shutdown()
        super.onDestroy()
    }
}
