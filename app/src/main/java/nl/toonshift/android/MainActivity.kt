package nl.toonshift.android

import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        enableEdgeToEdge()
        val vault = Vault(applicationContext)
        setContent {
            val dutch = LocalConfiguration.current.locales[0].language == "nl"
            ToonTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ToonApp(vault, pairing, dutch)
                }
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
    var revokeId by rememberSaveable { mutableStateOf<String?>(null) }
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
    var reachableAt by remember(device) { mutableStateOf<Instant?>(null) }
    var pendingTarget by remember(device) { mutableStateOf<Int?>(null) }
    var working by remember(device) { mutableStateOf(false) }
    val needsStatus = screen == 0 || screen == 1
    LaunchedEffect(api, lifecycle, needsStatus) {
        if (api == null || !needsStatus) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var backoff = 1000L
            while (true) {
                try {
                    api.states().collect { event ->
                        reachableAt = Instant.now()
                        backoff = 1000L
                        if (event == null) {
                            status = null
                            connected = false
                        } else {
                            if (event.length() > 0) {
                                status = event
                                last = reachableAt
                                if (!connected) message = ""
                                connected = true
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
            val requested = if (path == "/v1/setpoint") json.getInt("centi_celsius") else null
            pendingTarget = requested
            scope.launch {
                var failure: Exception? = null
                try {
                    api.call(path, "PUT", json)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e }
                try {
                    status = JSONObject(api.call("/v1/status"))
                    last = Instant.now()
                    reachableAt = last
                    val confirmed = status?.optString("currentSetpoint")?.toIntOrNull()
                    message = when {
                        requested != null && confirmed == requested -> ""
                        failure == null && requested == null -> ""
                        else -> t.tr("Change was not confirmed. Showing the Toon’s current state.", "Wijziging niet bevestigd. De actuele Toon-status wordt getoond.")
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { connected = false; message = t.failure(e) }
                finally { pendingTarget = null; working = false }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                AppMasthead(device, t) { screen = 2; message = "" }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0, 0, 0, 0)) {
                listOf(
                    0 to t.tr("Home", "Thuis"),
                    3 to t.tr("Energy", "Energie"),
                    1 to t.tr("Schedule", "Programma"),
                    2 to t.tr("Devices", "Apparaten")
                ).forEach { (index, label) ->
                    NavigationBarItem(selected = screen == index, onClick = { screen = index; message = "" },
                        icon = { NavSymbol(index) },
                        label = { Text(label, maxLines = 1) }, enabled = index == 2 || device != null)
                }
            }
        }
    ) { padding ->
        val scroll = key(screen) { rememberScrollState() }
        Box(Modifier.padding(padding).fillMaxSize().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 660.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            if (message.isNotBlank() && screen != 3) Surface(
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) { Text(message, Modifier.padding(14.dp)) }
            when (screen) {
                0 -> if (device != null) Thermostat(device, t, status, last, reachableAt, connected, working, pendingTarget, write)
                1 -> if (api != null && device != null) Programme(api, device, connected, t) { message = it }
                3 -> if (api != null && device != null) EnergyScreen(api, device, t)
                else -> {
                    PageHeading(t.tr("Devices","Apparaten"),
                        t.tr("Manage paired Toons and phone access.", "Beheer gekoppelde Toons en telefoontoegang."))
                    val context = androidx.compose.ui.platform.LocalContext.current
                    OutlinedButton(onClick = { context.startActivity(android.content.Intent(context, NasAlertsActivity::class.java)) }) {
                        Text(t.tr("NAS alerts", "NAS-meldingen"))
                    }
                    toons.forEach { item ->
                        WorkspacePanel {
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(modifier = Modifier.weight(1f), onClick = { selectedId = item.id; screen = 0 }) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                                        Text(item.host, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                TextButton(onClick = { revokeId = item.id }) { Text(t.tr("Revoke","Intrekken")) }
                            }
                        }
                    }
                    when (val progress = phase) {
                        PairingPhase.Connecting -> {
                            WorkspacePanel {
                                SectionHeading(t.tr("Connecting to your Toon","Verbinden met je Toon"))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                TextButton(onClick={pairing.cancel()}) { Text(t.tr("Cancel","Annuleren")) }
                            }
                        }
                        is PairingPhase.Approval -> {
                            WorkspacePanel(emphasized = true) {
                                SectionHeading(t.tr("Confirm on your Toon","Bevestig op je Toon"))
                                Text(progress.code,style=MaterialTheme.typography.displaySmall)
                                Text(t.tr("Compare this code with the one on your Toon, then tap Allow control there.",
                                    "Vergelijk deze code met die op je Toon en tik daar op Bediening toestaan."))
                                TextButton(onClick={pairing.cancel()}) { Text(t.tr("Cancel","Annuleren")) }
                            }
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
                            WorkspacePanel(emphasized = toons.isEmpty()) {
                            SectionHeading(t.tr("Pair a phone","Telefoon koppelen"))
                            PairingStep("1", t.tr("Open the QR code", "Open de QR-code"),
                                t.tr("On your Toon go to Settings → Remote access → Pair phone.",
                                    "Ga op je Toon naar Instellingen → Externe toegang → Telefoon koppelen."))
                            PairingStep("2", t.tr("Scan and approve", "Scan en keur goed"),
                                t.tr("Use the same Wi-Fi, scan below, and confirm on the Toon.",
                                    "Gebruik hetzelfde wifi-netwerk, scan hieronder en bevestig op de Toon."))
                            OutlinedTextField(value=phoneName,onValueChange={ if (it.toByteArray().size <= 32) phoneName=it },
                                label={Text(t.tr("Phone name","Telefoonnaam"))},singleLine=true, modifier = Modifier.fillMaxWidth())
                            Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick={
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
    }
    toons.firstOrNull { it.id == revokeId }?.let { item ->
        AlertDialog(
            onDismissRequest = { revokeId = null },
            title = { Text(t.tr("Remove access to ${item.name}?", "Toegang tot ${item.name} verwijderen?")) },
            confirmButton = {
                TextButton(onClick = {
                    revokeId = null
                    scope.launch {
                        try {
                            val peer = ToonApi(item.host, item.pin, item.token)
                            try { peer.call("/v1/device", "DELETE") }
                            catch (e: HttpFailure) { if (e.status != 401) throw e }
                            val remaining = toons.filterNot { it.id == item.id }
                            vault.save(remaining)
                            toons = remaining
                            if (selectedId == item.id) selectedId = remaining.firstOrNull()?.id
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = t.failure(e) }
                    }
                }) { Text(t.tr("Revoke access", "Toegang intrekken")) }
            },
            dismissButton = { TextButton(onClick = { revokeId = null }) { Text(t.tr("Cancel", "Annuleren")) } }
        )
    }
}

@Composable
private fun PairingStep(number: String, label: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusPill(number, true)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Thermostat(toon: Toon, t: TextKit, status: JSONObject?, last: Instant?, reachableAt: Instant?,
                       connected: Boolean, working: Boolean, pendingTarget: Int?, write: (String, JSONObject) -> Unit) {
    val target = status?.optString("currentSetpoint")?.toIntOrNull()
    val room = status?.optString("currentTemp")?.toIntOrNull()
    fun temperature(value: Int?) = value?.let {
        String.format(if (t.dutch) Locale.forLanguageTag("nl-NL") else Locale.UK,"%.1f °C",it / 100.0)
    } ?: "—"
    fun time(value: Instant) = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(toon.zone)).format(value)
    var hold by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        PageHeading(t.tr("Climate", "Klimaat"))
        StatusPill(if (connected) t.tr("Connected","Verbonden") else t.tr("Offline","Offline"), connected)
    }
    WorkspacePanel(emphasized = true) {
            Text(t.tr("Room temperature","Kamertemperatuur"))
            Text(temperature(room),style=MaterialTheme.typography.displayMedium)
            if (last != null) Text("${t.tr("Reading received","Meting ontvangen")} ${time(last)}",
                style = MaterialTheme.typography.bodySmall)
            if (!connected && reachableAt != null) Text("${t.tr("Last contact", "Laatste contact")} ${time(reachableAt)}",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(t.tr("Target temperature","Doeltemperatuur"),style=MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                for (step in listOf(-50,50)) {
                    if (step > 0) Text(temperature(target), Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    val next = target?.plus(step)?.coerceIn(600,3000)
                    val description = if (step < 0) t.tr("Decrease target","Doel verlagen") else t.tr("Increase target","Doel verhogen")
                    Button(onClick={ if (next != null) write("/v1/setpoint",JSONObject().put("centi_celsius",next)) },
                        enabled=connected && !working && next!=null && next!=target,
                        contentPadding = PaddingValues(0.dp), modifier=Modifier.size(52.dp).semantics { contentDescription = description }) {
                        Text(if(step<0) "−" else "+",style=MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            if (pendingTarget != null) Text("${t.tr("Setting target to", "Doel instellen op")} ${temperature(pendingTarget)}",
                style = MaterialTheme.typography.bodySmall)
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    val heating = status?.optString("burnerInfo")?.toIntOrNull()
    val heatingLabel = when (heating.takeIf { connected }) {
        0 -> t.tr("Heating idle","Verwarming in rust")
        1 -> t.tr("Heating","Verwarming actief")
        2 -> t.tr("Heating hot water","Warm water opwarmen")
        3 -> t.tr("Preheating","Voorverwarmen")
        else -> t.tr("Heating status unavailable","Verwarmingsstatus onbekend")
    }
    val active = status?.optString("activeState")?.toIntOrNull()
    val programmed = status?.optString("programState")
    val programmeLabel = when(programmed) {
        "1" -> t.tr("Schedule active","Programma actief")
        "2" -> t.tr("Hold until next period","Vasthouden tot volgende periode")
        "8" -> t.tr("Indefinite hold","Onbeperkt vasthouden")
        "0" -> t.tr("Manual control","Handmatige regeling")
        else -> t.tr("Schedule status unavailable","Programmastatus onbekend")
    }
    WorkspacePanel {
        Text(programmeLabel, style = MaterialTheme.typography.titleMedium)
        StatusPill(heatingLabel, connected && (heating ?: 0) in 1..3)
        status?.optString("nextTime")?.toLongOrNull()?.takeIf { it in 1..2147483647 }?.let { next ->
            Text("${t.tr("Next change","Volgende wijziging")} ${time(Instant.ofEpochSecond(next))} · ${temperature(status.optString("nextSetpoint").toIntOrNull())}",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    SectionHeading(t.tr("Presets","Temperatuurstanden"))
    for (row in 0..1) {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            for (id in row*2..row*2+1) {
                FilterChip(selected = id == active, onClick = { write("/v1/preset", JSONObject().put("id", id)) },
                    enabled = connected && !working, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    label = { Text(t.presets[id], style = MaterialTheme.typography.titleMedium) })
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
    var day by rememberSaveable(toon.id) { mutableIntStateOf(0) }
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
    PageHeading(t.tr("Weekly schedule","Weekprogramma"), toon.zone)
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        itemsIndexed(t.days) { index, name ->
            FilterChip(selected=day==index,onClick={day=index},label={Text(name)})
        }
    }
    if (!connected) StatusPill(t.tr("Disconnected","Niet verbonden"), false)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val daily = remember(slots, day) { slots.filter { it.start / 1440 == day } }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={original=null;editing=true},enabled=ready, modifier = Modifier.weight(1f)) {Text(t.tr("Add period","Periode toevoegen"))}
        OutlinedButton(onClick={copying=true},enabled=ready && daily.isNotEmpty(), modifier = Modifier.weight(1f)) {Text(t.tr("Copy day","Dag kopiëren"))}
    }
    if (revision.isNotBlank()) WorkspacePanel {
        if (daily.isEmpty()) Text(t.tr("No periods on this day","Geen perioden op deze dag"))
        daily.forEachIndexed { index, slot ->
            if (index > 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${minutesText(slot.start)} – ${minutesText(slot.end)}" + if(slot.end/1440 != day) " · ${t.days[(slot.end/1440)%7]}" else "",
                        style=MaterialTheme.typography.titleMedium)
                    Text(t.presets.getOrNull(slot.preset) ?: "—", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick={original=slot;editing=true},enabled=ready) {Text(t.tr("Edit","Bewerken"))}
                TextButton(onClick={removing=slot},enabled=ready) {Text(t.tr("Delete","Verwijderen"))}
            }
        }
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
