package nl.toonshift.android

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class NasAlertsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ToonTheme { NasSettings() } }
    }

    @Composable
    private fun NasSettings() {
        val prefs = remember { alertPrefs() }
        val nl = Locale.getDefault().language == "nl"
        fun tr(en: String, dutch: String) = if (nl) dutch else en
        var saved by remember { mutableStateOf(runCatching { alertVault().load().singleOrNull() }.getOrNull()) }
        var pairing by remember { mutableStateOf("") }
        var address by remember { mutableStateOf(saved?.host.orEmpty()) }
        var mode by remember { mutableStateOf(prefs.getString("bliss-voice-mode","offline") ?: "offline") }
        var status by remember { mutableStateOf("") }
        var received by remember { mutableLongStateOf(0) }
        var connected by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var editConnection by remember { mutableStateOf(saved == null) }

        LaunchedEffect(Unit) {
            while (true) {
                val last = prefs.getLong("last-check",0)
                val state = prefs.getString("status","Alerts are off.").orEmpty()
                val recent = last > 0 && System.currentTimeMillis() - last < 60_000
                val allowed = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                connected = recent && state.startsWith("Connected") && allowed
                status = when {
                    !allowed -> tr("Android notifications are disabled.","Android-meldingen staan uit.")
                    !recent && state.startsWith("Connected") -> tr("No recent check-in.","Geen recente verbinding.")
                    else -> state
                }
                received = prefs.getLong("last-ring",0)
                delay(1000)
            }
        }

        fun start() {
            try {
                val data = if (pairing.isNotBlank()) pairing.trim() else saved?.let {
                    JSONObject().put("url",it.host).put("key",it.token).put("pin",it.pin).toString()
                } ?: error("Missing pairing")
                val config = AlertConfig.parse(data,address)
                val next = Toon("nas","NAS",config.url,config.pin,config.key,"")
                if (next != saved) {
                    alertVault().save(listOf(next))
                    prefs.edit().remove("cursor").remove("cursor-time").apply()
                }
                saved = next
                address = next.host
                pairing = ""
                editConnection = false
                startForegroundService(Intent(this,NasAlertService::class.java))
                message = tr("Connecting to your NAS.","Verbinden met je NAS.")
            } catch (_: Exception) {
                message = tr("Check the pairing text and HTTPS address.","Controleer de koppeltekst en het HTTPS-adres.")
            }
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) start() else message = tr("Allow notifications to receive calls.","Sta meldingen toe om oproepen te ontvangen.")
        }

        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { finish() }) { Text(tr("‹ Devices","‹ Apparaten")) }
                    PageHeading(tr("NAS alerts","NAS-meldingen"),tr("Connection, incoming calls, and Bliss’s voice.","Verbinding, inkomende oproepen en de stem van Bliss."))
                    WorkspacePanel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            SectionHeading(tr("Connection","Verbinding"))
                            StatusPill(if (connected) tr("Connected","Verbonden") else tr("Offline","Offline"),connected)
                        }
                        if (saved != null) Text(saved?.host.orEmpty(),style=MaterialTheme.typography.bodySmall)
                        Text(status,style=MaterialTheme.typography.bodyMedium)
                        if (received > 0) Text(tr("Last ring: ","Laatste oproep: ") + DateFormat.getDateTimeInstance().format(Date(received)),
                            style=MaterialTheme.typography.bodySmall)
                        if (editConnection) {
                            Text(tr("In ToonShift Home, select Pair Android and paste the pairing text here.","Kies Android koppelen in ToonShift Home en plak de koppeltekst hier."))
                            OutlinedTextField(pairing,{pairing=it},Modifier.fillMaxWidth(),
                                label={Text(tr("Pairing text","Koppeltekst"))},visualTransformation=PasswordVisualTransformation())
                            OutlinedTextField(address,{address=it},Modifier.fillMaxWidth(),
                                label={Text(tr("HTTPS address (optional)","HTTPS-adres (optioneel)"))},singleLine=true)
                        }
                        if (message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else start()
                            }) { Text(tr("Start listening","Luisteren starten")) }
                            OutlinedButton(onClick = {
                                stopService(Intent(this@NasAlertsActivity,NasAlertService::class.java))
                                message = tr("Alerts stopped.","Meldingen gestopt.")
                            }) { Text(tr("Stop","Stop")) }
                        }
                        if (saved != null && !editConnection)
                            TextButton(onClick = { editConnection = true }) { Text(tr("Edit connection","Verbinding aanpassen")) }
                    }
                    WorkspacePanel {
                        SectionHeading(tr("Incoming calls","Inkomende oproepen"))
                        Text(tr("Use Test ring in the NAS dashboard to check delivery.","Gebruik Testoproep op het NAS-dashboard om de verbinding te controleren."))
                        OutlinedButton(onClick = {
                            val manager = getSystemService(NotificationManager::class.java)
                            val intent = if (manager.getNotificationChannel("nas-rings") != null)
                                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID,"nas-rings")
                            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
                        }) { Text(tr("Ringtone and notifications","Beltoon en meldingen")) }
                    }
                    WorkspacePanel {
                        SectionHeading(tr("Bliss voice","Stem van Bliss"))
                        val voices = listOf(
                            "stella" to tr("Stella · Neural English","Stella · Neuraal Engels"),
                            "tide" to tr("Tide · Neural English","Tide · Neuraal Engels"),
                            "offline" to tr("On-device voice","Stem op dit apparaat"),
                        )
                        voices.forEach { (id,label) ->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                RadioButton(selected=id == mode,onClick={
                                    mode = id
                                    prefs.edit().putString("bliss-voice-mode",id).apply()
                                })
                                Text(label)
                            }
                        }
                        Text(tr("Neural voices speak English. They need Home 0.1.18 and phone replies enabled. Each playback uses one request.",
                            "Neurale stemmen spreken Engels. Ze vereisen Home 0.1.18 en telefoonreacties. Elke afspeelbeurt gebruikt één verzoek."),
                            style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={startActivity(Intent(this@NasAlertsActivity,BlissVoiceActivity::class.java))}) {
                            Text(tr("Preview installed voices","Geïnstalleerde stemmen beluisteren"))
                        }
                    }
                }
            }
        }
    }
}
