package nl.toonshift.android

import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class PortraitScanner : CaptureActivity()

class MainActivity : ComponentActivity() {
    private val pairing: PairingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = Vault(applicationContext)
        setContent {
            val dutch = LocalConfiguration.current.locales[0].language == "nl"
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF90DABC), secondaryContainer = Color(0xFF274A40))
            } else {
                lightColorScheme(primary = Color(0xFF14694F), secondaryContainer = Color(0xFFD1EEE0))
            }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) { ToonApp(vault, pairing, dutch) }
            }
        }
    }
}

internal class TextKit(val dutch: Boolean) {
    fun tr(en: String, nl: String) = if (dutch) nl else en
    val presets get() = listOf(tr("Comfort","Comfort"), tr("Home","Thuis"), tr("Sleep","Slapen"), tr("Away","Weg"))
    val days get() = if (dutch) listOf("Zo","Ma","Di","Wo","Do","Vr","Za") else listOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")
    fun failure(e: Exception): String = when {
        e is HttpFailure && e.status == 401 -> tr("Approval missing or phone access revoked", "Goedkeuring ontbreekt of toegang is ingetrokken")
        e is HttpFailure && e.status == 410 -> tr("Pairing expired. Start again on the Toon.", "Koppeling verlopen. Begin opnieuw op de Toon.")
        e is HttpFailure && e.status == 409 -> tr("Schedule changed. Review the refreshed schedule.", "Programma gewijzigd. Controleer het vernieuwde programma.")
        e is HttpFailure && e.status == 422 -> tr("Toon rejected the change. Check the selected times.", "Toon weigerde de wijziging. Controleer de tijden.")
        e is javax.net.ssl.SSLException -> tr("Toon identity could not be verified. Pair again at home.", "Toon-identiteit kon niet worden gecontroleerd. Koppel thuis opnieuw.")
        else -> tr("Toon unreachable. Check Wi-Fi or Tailscale.", "Toon onbereikbaar. Controleer wifi of Tailscale.")
    }
}

@Composable
private fun ToonApp(vault: Vault, pairing: PairingViewModel, dutch: Boolean) {
    val t = remember(dutch) { TextKit(dutch) }
    val stored = remember { runCatching { vault.load() } }
    var toons by remember { mutableStateOf(stored.getOrDefault(emptyList())) }
    var selectedId by rememberSaveable { mutableStateOf(toons.firstOrNull()?.id) }
    val selected = toons.firstOrNull { it.id == selectedId }
    var screen by rememberSaveable { mutableIntStateOf(if (selected == null) 2 else 0) }
    var message by rememberSaveable { mutableStateOf(if (stored.isFailure) t.tr("Stored credentials could not be opened. Pair again.", "Opgeslagen gegevens konden niet worden geopend. Koppel opnieuw.") else "") }
    var phoneName by rememberSaveable { mutableStateOf("Android") }
    val phase by pairing.phase.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val payload = result.contents
        if (payload == null) {
            pairing.scanCancelled()
        } else {
            message = ""
            pairing.connect(vault, payload, phoneName)
        }
    }
    LaunchedEffect(phase) {
        if (phase is PairingPhase.Paired) {
            val ready = phase as PairingPhase.Paired
            toons = ready.devices
            selectedId = ready.selected
            screen = 0
            pairing.cancel()
        }
    }
    val device = selected
    val api = remember(device) { device?.let { ToonApi(it.host, it.pin, it.token) } }
    var status by remember(device) { mutableStateOf<JSONObject?>(null) }
    var last by remember(device) { mutableStateOf<Instant?>(null) }
    var connected by remember(device) { mutableStateOf(false) }
    var working by remember(device) { mutableStateOf(false) }
    LaunchedEffect(api, lifecycle) {
        if (api == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var backoff = 1000L
            while (true) {
                try {
                    api.states().collect { event ->
                        if (event == null) {
                            status = null
                            connected = false
                        } else {
                            if (event.length() > 0) status = event
                            if (status != null) {
                                connected = true
                                last = Instant.now()
                                backoff = 1000L
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = t.failure(e) }
                finally { connected = false }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15000)
            }
        }
    }
    val write: (String, JSONObject) -> Unit = { path, json ->
        if (api != null && connected && !working) {
            working = true
            scope.launch {
                var failure: Exception? = null
                try {
                    api.call(path, "PUT", json)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e }
                try {
                    status = JSONObject(api.call("/v1/status"))
                    last = Instant.now()
                    message = if (failure == null) "" else
                        t.tr("Change was not confirmed. Showing the Toon’s current state.", "Wijziging niet bevestigd. De actuele Toon-status wordt getoond.")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { connected = false; message = t.failure(e) }
                finally { working = false }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(0, t.tr("Thermostat", "Thermostaat"), "°"),
                    Triple(3, t.tr("Energy", "Energie"), "ϟ"),
                    Triple(1, t.tr("Schedule", "Programma"), "▤"),
                    Triple(2, t.tr("Devices", "Apparaten"), "▣")
                ).forEach { (index, label, glyph) ->
                    NavigationBarItem(selected = screen == index, onClick = { screen = index; message = "" },
                        icon = { Text(glyph, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(label, maxLines = 1) }, enabled = index == 2 || device != null)
                }
            }
        }
    ) { padding ->
        val scroll = key(screen) { rememberScrollState() }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            if (screen != 3) Text("ToonShift", style = MaterialTheme.typography.headlineLarge)
            if (message.isNotBlank() && screen != 3) Text(message, color = MaterialTheme.colorScheme.error)
            when (screen) {
                0 -> if (device != null) Thermostat(device, t, status, last, connected, working, write)
                1 -> if (api != null && device != null) Programme(api, device, connected, t) { message = it }
                3 -> if (api != null && device != null) EnergyScreen(api, device, t)
                else -> {
                    Text(t.tr("Paired devices","Gekoppelde apparaten"), style=MaterialTheme.typography.titleLarge)
                    toons.forEach { item ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { selectedId = item.id; screen = 0 }) { Text("${item.name} · ${item.host}") }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            val peer = ToonApi(item.host, item.pin, item.token)
                                            try { peer.call("/v1/device","DELETE") }
                                            catch (e: HttpFailure) { if (e.status != 401) throw e }
                                            val remaining = toons.filterNot { it.id == item.id }
                                            vault.save(remaining)
                                            toons = remaining
                                            if (selectedId == item.id) selectedId = remaining.firstOrNull()?.id
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { message = t.failure(e) }
                                    }
                                }) { Text(t.tr("Revoke","Intrekken")) }
                            }
                        }
                    }
                    when (val progress = phase) {
                        PairingPhase.Connecting -> {
                            Text(t.tr("Connecting securely to the Toon…","Veilige verbinding met de Toon maken…"))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick={pairing.cancel()}) { Text(t.tr("Cancel","Annuleren")) }
                        }
                        is PairingPhase.Approval -> {
                            Text(t.tr("Approve this phone on the Toon","Keur deze telefoon goed op de Toon"),
                                style=MaterialTheme.typography.titleLarge)
                            Text(progress.code,style=MaterialTheme.typography.displaySmall)
                            Text(t.tr("Check that the same code appears on the Toon, then tap Allow control there.",
                                "Controleer of dezelfde code op de Toon staat en tik daar op Bediening toestaan."))
                            TextButton(onClick={pairing.cancel()}) { Text(t.tr("Cancel","Annuleren")) }
                        }
                        is PairingPhase.Paired -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        PairingPhase.Idle, is PairingPhase.Failed -> {
                            if (progress is PairingPhase.Failed) {
                                val error = progress.error
                                Text(when {
                                    error is IllegalArgumentException || error is org.json.JSONException ->
                                        t.tr("Scan a valid code from your Toon's Remote access screen.", "Scan een geldige code van het Toon-scherm Externe toegang.")
                                    error is ScanCancelled ->
                                        t.tr("No QR code was received. Open the scanner and try again.",
                                            "Geen QR-code ontvangen. Open de scanner en probeer opnieuw.")
                                    error is PairingExpired || error is HttpFailure && error.status == 410 ->
                                        t.tr("This pairing session ended. Tap Pair phone on the Toon for a fresh code.",
                                            "Deze koppelsessie is afgelopen. Tik op Telefoon koppelen op de Toon voor een nieuwe code.")
                                    else -> t.failure(error)
                                }, color=MaterialTheme.colorScheme.error)
                            }
                            Text(t.tr("Use the same Wi-Fi as your Toon during setup.",
                                "Gebruik tijdens het koppelen hetzelfde wifi-netwerk als je Toon."))
                            Text(t.tr("On the Toon: Settings → Remote access → Pair phone.",
                                "Op de Toon: Instellingen → Externe toegang → Telefoon koppelen."))
                            OutlinedTextField(value=phoneName,onValueChange={ if (it.toByteArray().size <= 32) phoneName=it },
                                label={Text(t.tr("Phone name","Telefoonnaam"))},singleLine=true)
                            Button(onClick={
                                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setCaptureActivity(PortraitScanner::class.java)
                                    .setOrientationLocked(true)
                                    .setPrompt(t.tr("Scan Toon pairing code", "Scan de koppelcode")).setBeepEnabled(false))
                            }) { Text(t.tr("Scan pairing code","Scan koppelcode")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thermostat(toon: Toon, t: TextKit, status: JSONObject?, last: Instant?, connected: Boolean,
                       working: Boolean, write: (String, JSONObject) -> Unit) {
    val target = status?.optString("currentSetpoint")?.toIntOrNull()
    val room = status?.optString("currentTemp")?.toIntOrNull()
    fun temperature(value: Int?) = value?.let {
        String.format(if (t.dutch) Locale.forLanguageTag("nl-NL") else Locale.UK,"%.1f °C",it / 100.0)
    } ?: "—"
    fun time(value: Instant) = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(toon.zone)).format(value)
    var hold by remember { mutableStateOf(false) }
    Text(toon.name,style=MaterialTheme.typography.titleLarge)
    Text(if (connected) t.tr("Connected","Verbonden") else t.tr("Disconnected","Niet verbonden"))
    last?.let { Text("${t.tr("Last confirmed","Laatst bevestigd")} ${time(it)}") }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(t.tr("Room temperature","Kamertemperatuur"))
            Text(temperature(room),style=MaterialTheme.typography.displayMedium)
            Text("${t.tr("Target","Doel")} ${temperature(target)}",style=MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                for (step in listOf(-50,50)) {
                    val next = target?.plus(step)?.coerceIn(600,3000)
                    Button(onClick={ if (next != null) write("/v1/setpoint",JSONObject().put("centi_celsius",next)) },
                        enabled=connected && !working && next!=null && next!=target,
                        modifier=Modifier.weight(1f)) { Text(if(step<0) "−" else "+",style=MaterialTheme.typography.headlineSmall) }
                }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    val heating = status?.optString("currentModulationLevel")?.toIntOrNull()
    Text(when { heating == null -> t.tr("Heating status unavailable","Verwarmingsstatus onbekend")
        heating > 0 -> t.tr("Heat requested","Warmtevraag")
        else -> t.tr("Heating idle","Verwarming in rust") })
    val active = status?.optString("activeState")?.toIntOrNull()?.let(t.presets::getOrNull)
    if (active != null) Text("${t.tr("Active preset","Actieve stand")} · $active")
    val programmed = status?.optString("programState")
    Text(when(programmed) {
        "1" -> t.tr("Schedule active","Programma actief")
        "2" -> t.tr("Hold until next period","Vasthouden tot volgende periode")
        "8" -> t.tr("Indefinite hold","Onbeperkt vasthouden")
        "0" -> t.tr("Manual control","Handmatige regeling")
        else -> t.tr("Schedule status unavailable","Programmastatus onbekend")
    })
    status?.optString("nextTime")?.toLongOrNull()?.takeIf { it in 1..2147483647 }?.let { next ->
        Text("${t.tr("Next change","Volgende wijziging")} ${time(Instant.ofEpochSecond(next))} · ${temperature(status.optString("nextSetpoint").toIntOrNull())}")
    }
    Text(t.tr("Presets","Temperatuurstanden"),style=MaterialTheme.typography.titleLarge)
    for (row in 0..1) {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            for (id in row*2..row*2+1) {
                OutlinedButton(onClick={write("/v1/preset",JSONObject().put("id",id))},
                    enabled=connected && !working,modifier=Modifier.weight(1f)) { Text(t.presets[id]) }
            }
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={hold=true}, enabled=connected && !working) { Text(t.tr("Hold preset","Stand vasthouden")) }
        TextButton(onClick={write("/v1/schedule",JSONObject().put("enabled", programmed!="1"))},
            enabled=connected && !working && programmed!=null) {
            Text(if (programmed=="1") t.tr("Pause schedule","Programma pauzeren") else t.tr("Resume schedule","Programma hervatten"))
        }
    }
    if (hold) {
        var preset by remember { mutableIntStateOf(1) }
        AlertDialog(onDismissRequest={hold=false},title={Text(t.tr("Hold preset","Stand vasthouden"))},
            text={
                Column {
                    t.presets.forEachIndexed { i, name ->
                        Row { RadioButton(i==preset,{preset=i}); Text(name,Modifier.padding(top=13.dp)) }
                    }
                    TextButton(onClick={hold=false;write("/v1/hold",JSONObject().put("preset",preset).put("mode","indefinite"))},
                        enabled=connected && !working) { Text(t.tr("Until I resume","Tot ik hervat")) }
                }
            },
            confirmButton={TextButton(onClick={hold=false;write("/v1/hold",JSONObject().put("preset",preset).put("mode","until_next_period"))},
                enabled=connected && !working) { Text(t.tr("Until next period","Tot volgende periode")) }},
            dismissButton={TextButton(onClick={hold=false}) {Text(t.tr("Cancel","Annuleren"))}})
    }
}

@Composable
private fun Programme(api: ToonApi, toon: Toon, connected: Boolean, t: TextKit, message: (String) -> Unit) {
    var day by remember(toon.id) { mutableIntStateOf(0) }
    var slots by remember(toon.id) { mutableStateOf(emptyList<Slot>()) }
    var revision by remember(toon.id) { mutableStateOf("") }
    var busy by remember(toon.id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<Slot?>(null) }
    var removing by remember { mutableStateOf<Slot?>(null) }
    var copying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        val data = JSONObject(api.call("/v1/program?revision=1"))
        val periods = data.getJSONArray("periods")
        slots = (0 until periods.length()).map { Slot.parse(periods.getJSONObject(it)) }.sortedBy { it.start }
        revision = data.getString("revision")
    }
    LaunchedEffect(api, connected) {
        if (connected) {
            try { load() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(t.failure(e)) }
        }
    }
    fun update(edit: JSONObject) {
        if (!connected || revision.isBlank() || busy) return
        busy = true
        scope.launch {
            try {
                api.call("/v1/program","PUT",JSONObject().put("revision",revision).put("edit",edit))
                message("")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(t.failure(e)) }
            finally {
                revision = ""
                try { load() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
                busy = false
            }
        }
    }
    val ready=connected && revision.isNotBlank() && !busy
    Text(t.tr("Weekly schedule","Weekprogramma"),style=MaterialTheme.typography.headlineSmall)
    Text(toon.zone)
    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
        t.days.forEachIndexed { index, name ->
            FilterChip(selected=day==index,onClick={day=index},label={Text(name)})
        }
    }
    if (!connected) Text(t.tr("Disconnected","Niet verbonden"))
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val daily = slots.filter { it.start / 1440 == day }
    if (daily.isEmpty() && revision.isNotBlank()) Text(t.tr("No periods on this day","Geen perioden op deze dag"))
    daily.forEach { slot ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("${minutesText(slot.start)} – ${minutesText(slot.end)}" + if(slot.end/1440 != day) " · ${t.days[slot.end/1440]}" else "",
                    style=MaterialTheme.typography.titleLarge)
                Text(t.presets.getOrNull(slot.preset) ?: "—")
                Row {
                    TextButton(onClick={original=slot;editing=true},enabled=ready) {Text(t.tr("Edit","Bewerken"))}
                    TextButton(onClick={removing=slot},enabled=ready) {Text(t.tr("Delete","Verwijderen"))}
                }
            }
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={original=null;editing=true},enabled=ready) {Text(t.tr("Add period","Periode toevoegen"))}
        OutlinedButton(onClick={copying=true},enabled=ready && daily.isNotEmpty()) {Text(t.tr("Copy day","Dag kopiëren"))}
    }
    if (editing) {
        PeriodDialog(day,original,t,connected,{editing=false}) { new ->
            editing=false
            update(if(original==null) JSONObject().put("operation","add").put("slot",new.toJson())
                else JSONObject().put("operation","replace").put("original",original!!.toJson()).put("new",new.toJson()))
        }
    }
    removing?.let { slot ->
        AlertDialog(onDismissRequest={removing=null},title={Text(t.tr("Delete this period?","Deze periode verwijderen?"))},
            confirmButton={TextButton(onClick={removing=null;update(JSONObject().put("operation","remove").put("slot",slot.toJson()))},
                enabled=ready){Text(t.tr("Delete","Verwijderen"))}},
            dismissButton={TextButton(onClick={removing=null}){Text(t.tr("Cancel","Annuleren"))}})
    }
    if (copying) {
        var targets by remember { mutableIntStateOf(0) }
        AlertDialog(onDismissRequest={copying=false},title={Text(t.tr("Replace periods on selected days","Vervang perioden op gekozen dagen"))},
            text={Column {t.days.forEachIndexed {i,name -> if (i!=day) Row {
                Checkbox(checked=targets and (1 shl i)!=0,onCheckedChange={targets=targets xor (1 shl i)})
                Text(name,Modifier.padding(top=14.dp))
            }}}},
            confirmButton={TextButton(onClick={copying=false;update(JSONObject().put("operation","copy_day").put("source",day).put("days",targets))},
                enabled=ready && targets!=0){Text(t.tr("Copy","Kopiëren"))}},
            dismissButton={TextButton(onClick={copying=false}){Text(t.tr("Cancel","Annuleren"))}})
    }
}

private fun minutesText(time: Int) = String.format(Locale.ROOT,"%02d:%02d",time % 1440 / 60,time % 60)

@Composable
private fun PeriodDialog(day:Int, original:Slot?, t:TextKit, connected:Boolean, close:()->Unit, save:(Slot)->Unit) {
    var start by remember {mutableIntStateOf(original?.start?.rem(1440)?:480)}
    var end by remember {mutableIntStateOf(original?.end?.rem(1440)?:540)}
    var overnight by remember {mutableStateOf(original!=null && original.end/1440!=day)}
    var preset by remember {mutableIntStateOf(original?.preset?:1)}
    val context=LocalContext.current
    fun pick(value:Int, setter:(Int)->Unit) {
        TimePickerDialog(context,{_,hours,minutes->setter(hours*60+minutes)},value/60,value%60,true).show()
    }
    AlertDialog(onDismissRequest=close,title={Text(t.tr("Heating period","Verwarmingsperiode"))},
        text={Column {
            TextButton(onClick={pick(start){start=it}}){Text("${t.tr("Start","Start")} ${minutesText(start)}")}
            TextButton(onClick={pick(end){end=it}}){Text("${t.tr("End","Einde")} ${minutesText(end)}")}
            Row{Checkbox(overnight,{overnight=it});Text(t.tr("Ends next day","Eindigt volgende dag"),Modifier.padding(top=14.dp))}
            t.presets.forEachIndexed {i,name->Row{RadioButton(i==preset,{preset=i});Text(name,Modifier.padding(top=13.dp))}}
        }},
        confirmButton={TextButton(enabled=connected && (if(overnight) end<start else end>start),
            onClick={save(Slot(day*1440+start,((day+(if(overnight)1 else 0))*1440+end)%10080,preset))}){Text(t.tr("Save","Opslaan"))}},
        dismissButton={TextButton(onClick=close){Text(t.tr("Cancel","Annuleren"))}})
}
