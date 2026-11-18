package nl.toonshift.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
    Hour("hour", "1h", "1u"),
    Day("day", "24h", "24u"),
    Week("week", "7d", "7d"),
    Month("month", "31d", "31d"),
    Year("year", "365d", "365d")
}

internal data class EnergyChart(
    val bars: List<Double?>, val partialBars: List<Boolean>, val unit: String,
    val total: Double?, val partial: Boolean, val from: Long, val to: Long,
    val estimatedCost: Double?, val unitPrice: Double?
) {
    fun intervalCost(index: Int): Double? {
        if (unit !in setOf("kWh", "m³")) return null
        val price = unitPrice ?: return null
        return bars[index]?.times(price)?.takeIf { it.isFinite() }
    }

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
                json.getBoolean("partial"), from, to,
                json.optDouble("estimated_cost_eur").takeIf { it.isFinite() && it >= 0 },
                json.optDouble("price_eur_per_unit").takeIf { it.isFinite() && it >= 0 }
            )
        }
    }
}

private data class LiveEnergy(val value: Double, val unit: String, val measuredAt: Long) {
    companion object {
        fun parse(body: JSONObject, key: String, expectedUnit: String, receivedAt: Long): LiveEnergy? {
            val item = body.optJSONObject(key) ?: return null
            val value = item.optDouble("value")
            val age = item.optLong("age_seconds", -1)
            return if (value.isFinite() && value >= 0 && age >= 0 && age <= receivedAt && item.optString("unit") == expectedUnit)
                LiveEnergy(value, expectedUnit, receivedAt - age) else null
        }
    }
}

private data class UsageSnapshot(val electricity: LiveEnergy?, val gas: LiveEnergy?, val recentGasSupported: Boolean)

@Composable
internal fun EnergyScreen(api: ToonApi, toon: Toon, t: TextKit) {
    var meter by rememberSaveable(toon.id) { mutableStateOf(EnergyMeter.Electricity) }
    var period by rememberSaveable(toon.id) { mutableStateOf(EnergyPeriod.Day) }
    var showCost by rememberSaveable(toon.id) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var live by remember(api) { mutableStateOf<UsageSnapshot?>(null) }
    var liveError by remember(api) { mutableStateOf<Exception?>(null) }
    var liveLoading by remember(api) { mutableStateOf(true) }
    var chart by remember(api, meter, period) { mutableStateOf<EnergyChart?>(null) }
    var historyLoading by remember(api, meter, period) { mutableStateOf(true) }
    var historyError by remember(api, meter, period) { mutableStateOf<Exception?>(null) }
    var selected by remember(api, meter, period, chart?.to) { mutableStateOf<Int?>(null) }
    var showReadings by rememberSaveable { mutableStateOf(false) }
    var readingLimit by remember(meter, period) { mutableIntStateOf(12) }
    val locale = if (t.dutch) Locale.forLanguageTag("nl-NL") else Locale.UK
    val format = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 } }
    val currency = remember(locale) { NumberFormat.getCurrencyInstance(locale).apply { this.currency = java.util.Currency.getInstance("EUR") } }
    val rate = remember(locale) { NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 5; maximumFractionDigits = 5 } }
    val zone = remember(toon.zone) { ZoneId.of(toon.zone) }

    LaunchedEffect(api, lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val payload = JSONObject(api.call("/v1/energy/live"))
                    val receivedAt = Instant.now().epochSecond
                    val recent = payload.has("gas_recent")
                    live = UsageSnapshot(
                        LiveEnergy.parse(payload, "electricity", "W", receivedAt),
                        if (recent) LiveEnergy.parse(payload, "gas_recent", "L", receivedAt)
                        else LiveEnergy.parse(payload, "gas", "L/h", receivedAt),
                        recent
                    )
                    liveError = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { liveError = e }
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
        PageHeading(t.tr("Energy", "Energie"))
        TextButton(onClick = { refresh++ }) { Text(t.tr("Refresh", "Vernieuwen")) }
    }
    val current = live
    fun readingDetail(value: LiveEnergy): String {
        val timestamp = date(value.measuredAt, zone, locale, "HH:mm:ss")
        return if (liveError == null) timestamp else "${t.tr("Stale", "Verouderd")} · $timestamp"
    }
    WorkspacePanel(emphasized = true) {
        SectionHeading(t.tr("Meter readings", "Meterstanden"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val status = if (liveLoading) t.tr("Loading…", "Laden…") else t.tr("Unavailable", "Niet beschikbaar")
            val watts = current?.electricity
            MeterValue(Modifier.weight(1f), t.tr("Electricity", "Elektriciteit"),
                watts?.let { "${format.format(it.value)} ${it.unit}" } ?: "—",
                watts?.let(::readingDetail) ?: status)
            val gas = current?.gas
            MeterValue(Modifier.weight(1f), t.tr("Gas", "Gas"),
                gas?.let { "${format.format(it.value)} ${it.unit}" } ?: "—",
                when {
                    current?.recentGasSupported == true && gas != null -> "${t.tr("Last 5 minutes", "Afgelopen 5 minuten")} · ${readingDetail(gas)}"
                    current?.recentGasSupported == true -> t.tr("Waiting for a complete sample", "Wachten op volledige meting")
                    gas != null -> readingDetail(gas)
                    else -> status
                })
        }
    }
    liveError?.let { Text(energyError(it, t), color = MaterialTheme.colorScheme.error) }
    SectionHeading(t.tr("Usage history", "Verbruiksgeschiedenis"))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(EnergyMeter.entries, key = { it.id }) { option ->
            FilterChip(selected = meter == option, onClick = { meter = option }, label = { Text(t.tr(option.en, option.nl)) })
        }
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        EnergyPeriod.entries.forEachIndexed { index, option ->
            SegmentedButton(selected = period == option, onClick = { period = option },
                shape = SegmentedButtonDefaults.itemShape(index, EnergyPeriod.entries.size),
                icon = {},
                label = { Text(t.tr(option.en, option.nl), maxLines = 1) })
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
        val costs = remember(history) { history.bars.indices.map(history::intervalCost) }
        val costAvailable = costs.any { it != null }
        val costMode = showCost && costAvailable
        WorkspacePanel {
                Text(t.tr(meter.en, meter.nl), style = MaterialTheme.typography.titleMedium)
                Text(if (period == EnergyPeriod.Hour) t.tr("Average measured rate", "Gemiddeld gemeten debiet")
                    else t.tr("Recorded total", "Gemeten totaal"), style = MaterialTheme.typography.labelLarge)
                Text(summary?.let { "${format.format(it)} ${history.unit}" } ?: "—",
                    style = MaterialTheme.typography.headlineMedium)
                history.estimatedCost?.let { cost ->
                    Text("${t.tr("Estimated cost", "Geschatte kosten")} · ${currency.format(cost)}",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                history.unitPrice?.takeIf { period != EnergyPeriod.Hour }?.let { price ->
                    Text("€${rate.format(price)} / ${history.unit}",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("$start – $end", style = MaterialTheme.typography.bodySmall)
                if (history.bars.any { it != null }) {
                    if (costAvailable) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false, true).forEachIndexed { index, cost ->
                            SegmentedButton(selected = costMode == cost, onClick = { showCost = cost },
                                shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {},
                                label = { Text(if (cost) t.tr("Cost", "Kosten") else t.tr("Usage", "Verbruik")) })
                        }
                    }
                    val displayed = if (costMode) history.copy(bars = costs, unit = "€") else history
                    EnergyBars(displayed, selected, t, if (costMode) currency else format) { selected = it }
                    Text(t.tr("Tap a bar for details. Gaps mean missing readings.",
                        "Tik op een balk voor details. Lege plekken zijn ontbrekende metingen."),
                        style = MaterialTheme.typography.bodySmall)
                } else Text(t.tr("No measurements for this period.", "Geen metingen voor deze periode."))
                if (history.partial && history.bars.any { it != null }) Text(
                    t.tr("Incomplete period. Amber bars contain partial data.",
                        "Onvolledige periode. Gele balken bevatten onvolledige gegevens."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                selected?.let { index -> ReadingRow(history, index, zone, locale, t, format, currency) }
        }
        if (history.bars.any { it != null }) {
            TextButton(onClick = { showReadings = !showReadings }) {
                Text(if (showReadings) t.tr("Hide readings", "Metingen verbergen") else t.tr("Show readings", "Metingen tonen"))
            }
            if (showReadings) {
                for (i in history.bars.indices.reversed().take(readingLimit)) {
                    ReadingRow(history, i, zone, locale, t, format, currency)
                    HorizontalDivider()
                }
                if (readingLimit < history.bars.size) TextButton(onClick = { readingLimit += 12 }) {
                    Text(t.tr("Show older readings", "Toon oudere metingen"))
                }
            }
        }
    }
}

@Composable
private fun MeterValue(modifier: Modifier, title: String, value: String, detail: String) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
}

@Composable
private fun EnergyBars(chart: EnergyChart, selected: Int?, t: TextKit, format: NumberFormat, select: (Int) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outlineVariant
    val selectedColor = MaterialTheme.colorScheme.onSurface
    val partial = androidx.compose.ui.graphics.Color(0xFFB67928)
    val maximum = remember(chart) { chart.bars.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 0.0 }
    val description = if (chart.unit == "€") t.tr("Estimated cost chart.", "Grafiek met geschatte kosten.")
        else t.tr("Usage chart. Show readings for a text view.", "Verbruiksgrafiek. Toon metingen voor een tekstweergave.")
    Text(if (chart.unit == "€") format.format(maximum) else "${format.format(maximum)} ${chart.unit}", style = MaterialTheme.typography.labelSmall)
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
private fun ReadingRow(chart: EnergyChart, index: Int, zone: ZoneId, locale: Locale, t: TextKit, format: NumberFormat, currency: NumberFormat) {
    val from = date(chart.boundary(index), zone, locale, "d MMM HH:mm")
    val to = date(chart.boundary(index + 1), zone, locale, "d MMM HH:mm")
    val value = chart.bars[index]?.let {
        "${format.format(it)} ${chart.unit}" + if (chart.partialBars[index]) " · " + t.tr("partial", "onvolledig") else ""
    } ?: t.tr("Missing", "Ontbreekt")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$from – $to", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleSmall)
        chart.intervalCost(index)?.let { cost ->
            Text("${t.tr("Estimated cost", "Geschatte kosten")} · ${currency.format(cost)}",
                style = MaterialTheme.typography.bodySmall)
        }
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
