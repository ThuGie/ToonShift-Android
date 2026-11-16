package nl.toonshift.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class EnergyMeter(val id: String, val en: String, val nl: String) {
    Electricity("electricity", "Electricity", "Elektriciteit"),
    Gas("gas", "Gas", "Gas"),
    Export("export", "Grid export", "Teruglevering"),
    Solar("solar", "Solar", "Zonne-energie"),
    Water("water", "Water", "Water")
}

internal enum class EnergyPeriod(val id: String, val en: String, val nl: String) {
    Hour("hour", "1 hour", "1 uur"),
    Day("day", "24 hours", "24 uur"),
    Week("week", "7 days", "7 dagen"),
    Month("month", "31 days", "31 dagen"),
    Year("year", "365 days", "365 dagen")
}

internal data class EnergyChart(
    val bars: List<Double?>, val partialBars: List<Boolean>, val unit: String,
    val total: Double?, val partial: Boolean, val from: Long, val to: Long
) {
    fun boundary(index: Int): Long = from + ((to - from) * index + bars.size - 1) / bars.size

    companion object {
        fun parse(json: JSONObject): EnergyChart {
            val samples = json.getJSONArray("bars")
            val coverage = json.getJSONArray("partial_bars")
            val from = json.getLong("from")
            val to = json.getLong("to")
            val unit = json.getString("unit")
            require(samples.length() in 1..100 && coverage.length() == samples.length())
            require(from >= 0 && to > from && to <= 253402300799L && to - from <= 366L * 86400)
            require(unit in setOf("W", "L/h", "L/min", "kWh", "m³"))
            return EnergyChart(
                List(samples.length()) { i ->
                    if (samples.isNull(i)) null else samples.getDouble(i).also { require(it.isFinite() && it >= 0) }
                },
                List(coverage.length(), coverage::getBoolean),
                unit,
                if (json.isNull("total")) null else json.getDouble("total").also { require(it.isFinite() && it >= 0) },
                json.getBoolean("partial"), from, to
            )
        }
    }
}

private data class LiveEnergy(val value: Double, val unit: String, val age: Long) {
    companion object {
        fun parse(body: JSONObject, meter: EnergyMeter, expectedUnit: String): LiveEnergy? {
            val item = body.optJSONObject(meter.id) ?: return null
            val value = item.optDouble("value")
            val age = item.optLong("age_seconds", -1)
            return if (value.isFinite() && value >= 0 && age in 0..120 && item.optString("unit") == expectedUnit)
                LiveEnergy(value, expectedUnit, age) else null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EnergyScreen(api: ToonApi, toon: Toon, t: TextKit) {
    var meter by rememberSaveable(toon.id) { mutableStateOf(EnergyMeter.Electricity) }
    var period by rememberSaveable(toon.id) { mutableStateOf(EnergyPeriod.Day) }
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var live by remember(api) { mutableStateOf<JSONObject?>(null) }
    var liveAt by remember(api) { mutableLongStateOf(0) }
    var liveError by remember(api) { mutableStateOf<Exception?>(null) }
    var liveLoading by remember(api) { mutableStateOf(true) }
    var chart by remember(api, meter, period) { mutableStateOf<EnergyChart?>(null) }
    var historyLoading by remember(api, meter, period) { mutableStateOf(true) }
    var historyError by remember(api, meter, period) { mutableStateOf<Exception?>(null) }
    var selected by remember(api, meter, period, chart?.to) { mutableStateOf<Int?>(null) }
    var showReadings by rememberSaveable { mutableStateOf(false) }
    val locale = if (t.dutch) Locale.forLanguageTag("nl-NL") else Locale.UK
    val format = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 } }
    val zone = remember(toon.zone) { ZoneId.of(toon.zone) }

    LaunchedEffect(api, lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    live = JSONObject(api.call("/v1/energy/live"))
                    liveAt = Instant.now().epochSecond
                    liveError = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { live = null; liveError = e }
                finally { liveLoading = false }
                delay(10_000)
            }
        }
    }
    LaunchedEffect(api, lifecycle, meter, period, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                historyLoading = true
                try {
                    chart = EnergyChart.parse(JSONObject(api.call("/v1/energy?meter=${meter.id}&period=${period.id}")))
                    historyError = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { historyError = e }
                finally { historyLoading = false }
                delay(if (historyError is HttpFailure && (historyError as HttpFailure).status == 503) 5_000 else 60_000)
            }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(t.tr("Energy", "Energie"), style = MaterialTheme.typography.headlineMedium)
        TextButton(onClick = { refresh++ }) { Text(t.tr("Refresh", "Vernieuwen")) }
    }
    Text(t.tr("Live usage", "Actueel verbruik"), style = MaterialTheme.typography.titleLarge)
    val current = live
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((type, unit) in listOf(EnergyMeter.Electricity to "W", EnergyMeter.Gas to "L/h")) {
            val reading = current?.let { LiveEnergy.parse(it, type, unit) }
            val sampledAt = reading?.let { Instant.ofEpochSecond(liveAt - it.age) }
            FilledEnergyCard(Modifier.weight(1f), t.tr(type.en, type.nl),
                reading?.let { "${format.format(it.value)} ${it.unit}" } ?: "—",
                sampledAt?.let { date(it.epochSecond, zone, locale, "HH:mm:ss") }
                    ?: if (liveLoading) t.tr("Loading…", "Laden…") else t.tr("Unavailable", "Niet beschikbaar"))
        }
    }
    liveError?.let { Text(energyError(it, t), color = MaterialTheme.colorScheme.error) }
    HorizontalDivider()
    Text(t.tr("Usage history", "Verbruiksgeschiedenis"), style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnergyMeter.entries.forEach { option ->
            FilterChip(selected = meter == option, onClick = { meter = option }, label = { Text(t.tr(option.en, option.nl)) })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnergyPeriod.entries.forEach { option ->
            FilterChip(selected = period == option, onClick = { period = option }, label = { Text(t.tr(option.en, option.nl)) })
        }
    }
    val history = chart
    if (historyLoading && history == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    historyError?.let { failure ->
        Text(if (history != null) t.tr("Showing the last downloaded chart.", "De laatst opgehaalde grafiek wordt getoond.")
        else energyError(failure, t), color = MaterialTheme.colorScheme.error)
    }
    if (history != null) {
        val start = date(history.from, zone, locale, "d MMM HH:mm")
        val end = date(history.to, zone, locale, "d MMM HH:mm")
        val summary = if (period == EnergyPeriod.Hour) history.bars.filterNotNull().takeIf { it.isNotEmpty() }?.average()
            else history.total
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (period == EnergyPeriod.Hour) t.tr("Average of recorded flow", "Gemiddelde gemeten stroom")
                    else t.tr("Recorded total", "Gemeten totaal"), style = MaterialTheme.typography.labelLarge)
                Text(summary?.let { "${format.format(it)} ${history.unit}" } ?: "—",
                    style = MaterialTheme.typography.headlineMedium)
                Text("$start – $end", style = MaterialTheme.typography.bodySmall)
                if (history.bars.any { it != null }) {
                    EnergyBars(history, selected, t, format) { selected = it }
                    Text(t.tr("Tap a bar for details. Gaps mean missing readings.",
                        "Tik op een balk voor details. Lege plekken zijn ontbrekende metingen."),
                        style = MaterialTheme.typography.bodySmall)
                } else Text(t.tr("No measurements for this period.", "Geen metingen voor deze periode."))
                if (history.partial && history.bars.any { it != null }) Text(
                    t.tr("Incomplete period. Amber bars contain partial data.",
                        "Onvolledige periode. Gele balken bevatten onvolledige gegevens."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                selected?.let { index -> ReadingRow(history, index, zone, locale, t, format) }
            }
        }
        if (history.bars.any { it != null }) {
            TextButton(onClick = { showReadings = !showReadings }) {
                Text(if (showReadings) t.tr("Hide readings", "Metingen verbergen") else t.tr("Show readings", "Metingen tonen"))
            }
            if (showReadings) history.bars.indices.forEach { i ->
                ReadingRow(history, i, zone, locale, t, format)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FilledEnergyCard(modifier: Modifier, title: String, value: String, detail: String) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EnergyBars(chart: EnergyChart, selected: Int?, t: TextKit, format: NumberFormat, select: (Int) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outlineVariant
    val selectedColor = MaterialTheme.colorScheme.onSurface
    val partial = androidx.compose.ui.graphics.Color(0xFFB67928)
    val maximum = chart.bars.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 0.0
    val description = t.tr("Usage chart. Show readings for a text view.", "Verbruiksgrafiek. Toon metingen voor een tekstweergave.")
    Text("${format.format(maximum)} ${chart.unit}", style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(144.dp).semantics { contentDescription = description }
        .pointerInput(chart) { detectTapGestures { point ->
            select((point.x / size.width * chart.bars.size).toInt().coerceIn(chart.bars.indices))
        } }) {
        val slot = size.width / chart.bars.size
        val floor = size.height - 2.dp.toPx()
        for (fraction in listOf(0f, 0.5f, 1f)) {
            val y = floor * fraction
            drawLine(muted, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        chart.bars.forEachIndexed { index, value ->
            if (value != null) {
                val height = if (maximum > 0) ((value / maximum).toFloat() * floor).coerceAtLeast(2.dp.toPx())
                    else 2.dp.toPx()
                val brush = when {
                    selected == index -> selectedColor
                    chart.partialBars[index] -> partial
                    else -> color
                }
                drawRect(brush, Offset((index + 0.13f) * slot, floor - height), Size(slot * 0.74f, height))
            }
        }
    }
    Text("0", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun ReadingRow(chart: EnergyChart, index: Int, zone: ZoneId, locale: Locale, t: TextKit, format: NumberFormat) {
    val from = date(chart.boundary(index), zone, locale, "d MMM HH:mm")
    val to = date(chart.boundary(index + 1), zone, locale, "d MMM HH:mm")
    val value = chart.bars[index]?.let {
        "${format.format(it)} ${chart.unit}" + if (chart.partialBars[index]) " · " + t.tr("partial", "onvolledig") else ""
    } ?: t.tr("Missing", "Ontbreekt")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$from – $to", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

private fun date(time: Long, zone: ZoneId, locale: Locale, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.ofEpochSecond(time))

private fun energyError(error: Exception, t: TextKit): String = when {
    error is HttpFailure && error.status == 404 -> t.tr("Update ToonShift on your Toon to view energy here.", "Werk ToonShift op je Toon bij om hier energie te bekijken.")
    error is HttpFailure && error.status == 503 -> t.tr("The Toon is busy or these readings are unavailable.", "De Toon is bezig of deze metingen zijn niet beschikbaar.")
    error is IllegalArgumentException || error is org.json.JSONException -> t.tr("The Toon returned unreadable energy data.", "De Toon gaf onleesbare energiegegevens terug.")
    else -> t.failure(error)
}
