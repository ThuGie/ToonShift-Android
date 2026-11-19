package nl.toonshift.android

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
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
        val enable = Button(this).apply { text = "Start listening" }
        val stop = Button(this).apply { text = "Stop listening" }
        val existing = runCatching { alertVault().load().singleOrNull() }.getOrNull()
        if (existing != null) address.setText(existing.host)
        feedback.text = alertPrefs().getString("status", "Alerts are off.")
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
        listOf(titleView,help,pairing,address,enable,stop,feedback).forEach { panel.addView(it) }
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
                        if (serverTime - received in 0..120) ring(alert)
                        cursor = id
                        cursorTime = received
                    }
                    alertPrefs().edit().putLong("cursor",cursor).putLong("cursor-time",cursorTime).apply()
                }
                failures = 0
                updateConnection("Connected. Listening for home alerts.")
            } catch (_: Exception) {
                failures = (failures + 1).coerceAtMost(5)
                updateConnection("NAS unavailable. Check Tailscale or the HTTPS certificate.")
            }
            try { Thread.sleep((10L + 5L * failures) * 1000) }
            catch (_: InterruptedException) { return }
        }
    }

    private fun ring(alert: JSONObject) {
        val id = (alert.getLong("id") % 100000).toInt() + 20000
        val evidence = alert.optJSONObject("evidence")
        val text = when (alert.getString("rule")) {
            "electricity_high" -> "High electricity usage. The Toon measured ${evidence?.optInt("watts") ?: 0} watts."
            "gas_high" -> "High gas usage. The Toon measured ${evidence?.optInt("liters") ?: 0} liters in about five minutes."
            "heat_away" -> "The Toon is requesting heat while the household is set to Away."
            "test_ring" -> "This is a ToonShift Home test call."
            else -> return
        }
        val answer = PendingIntent.getActivity(this,id,Intent(this,NasAlertAnswerActivity::class.java)
            .putExtra("text",text).putExtra("notification",id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(id,Notification.Builder(this,"nas-rings").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentTitle("ToonShift Home is calling").setContentText(text).setContentIntent(answer).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null,"Answer",answer).build()).build())
    }

    override fun onDestroy() {
        worker?.interrupt()
        alertPrefs().edit().putString("status","Alerts are off.").apply()
        super.onDestroy()
    }
}

class NasAlertAnswerActivity : Activity() {
    private var speech: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra("text").orEmpty()
        getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("notification",-1))
        val status = TextView(this).apply { this.text = text; textSize = 24f; gravity = Gravity.CENTER }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32,32,32,32)
            addView(status)
            addView(Button(context).apply { this.text = "Dismiss"; setOnClickListener { finish() } })
        })
        speech = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val engine = speech ?: return@TextToSpeech
                val voice = engine.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language }
                if (voice != null) { engine.voice = voice; engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,"toonshift-alert") }
            }
        }
    }
    override fun onDestroy() { speech?.shutdown(); super.onDestroy() }
}
