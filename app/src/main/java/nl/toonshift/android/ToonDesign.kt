package nl.toonshift.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val light = lightColorScheme(
    primary = Color(0xFF126054), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEE4), onPrimaryContainer = Color(0xFF103D35),
    secondaryContainer = Color(0xFFDDECE8), onSecondaryContainer = Color(0xFF193B35),
    background = Color(0xFFF3F7F6), surface = Color(0xFFFAFDFC),
    onSurface = Color(0xFF172D2A), onSurfaceVariant = Color(0xFF4B625D),
    outlineVariant = Color(0xFFCCDAD6), surfaceContainer = Color(0xFFEAF2F0)
)
private val dark = darkColorScheme(
    primary = Color(0xFF9BE4D1), onPrimary = Color(0xFF093B31),
    primaryContainer = Color(0xFF17483D), onPrimaryContainer = Color(0xFFD7F5EB),
    secondaryContainer = Color(0xFF254B42), onSecondaryContainer = Color(0xFFCFECE3),
    background = Color(0xFF111E1C), surface = Color(0xFF172724),
    onSurface = Color(0xFFE4F1ED), onSurfaceVariant = Color(0xFFAFC7C0),
    outlineVariant = Color(0xFF3B5650), surfaceContainer = Color(0xFF1E322E)
)

@Composable
internal fun ToonTheme(content: @Composable () -> Unit) {
    val typography = remember {
        val font = FontFamily(Font(R.font.inter))
        val base = Typography()
        base.copy(
            displaySmall = base.displaySmall.copy(fontFamily = font, fontWeight = FontWeight.SemiBold),
            displayMedium = base.displayMedium.copy(fontFamily = font, fontWeight = FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.copy(fontFamily = font, fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontFamily = font, fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontFamily = font, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontFamily = font, fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(fontFamily = font),
            bodyMedium = base.bodyMedium.copy(fontFamily = font),
            bodySmall = base.bodySmall.copy(fontFamily = font),
            labelMedium = base.labelMedium.copy(fontFamily = font)
        )
    }
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dark else light,
        typography = typography,
        shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)),
        content = content
    )
}

@Composable
internal fun AppMasthead(device: Toon?, t: TextKit, pickDevice: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.toonshift_mark), contentDescription = null, modifier = Modifier.size(34.dp))
        Text("ToonShift", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (device != null) {
            TextButton(onClick = pickDevice) {
                Text(device.name.ifBlank { t.tr("Devices", "Apparaten") },
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 124.dp))
                Spacer(Modifier.width(5.dp))
                Text("⌄")
            }
        }
    }
}

@Composable
internal fun PageHeading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun WorkspacePanel(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = if (emphasized) colors.primaryContainer else colors.surface,
        contentColor = if (emphasized) colors.onPrimaryContainer else colors.onSurface,
        border = if (emphasized) null else BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun SectionHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
}

@Composable
internal fun StatusPill(label: String, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(50), color = if (active) colors.primaryContainer else colors.surfaceContainer,
        contentColor = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant) {
        Text(label, Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun NavSymbol(tab: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(1.8f * u)
        fun pt(x: Int, y: Int) = Offset(x * u, y * u)
        when (tab) {
            0 -> {
                drawPath(Path().apply {
                    moveTo(3*u, 11*u); lineTo(12*u, 3*u); lineTo(21*u, 11*u)
                    moveTo(6*u, 9*u); lineTo(6*u, 20*u); lineTo(18*u, 20*u); lineTo(18*u, 9*u)
                }, color, style = stroke)
            }
            3 -> drawPath(Path().apply {
                moveTo(13*u, 2*u); lineTo(5*u, 13*u); lineTo(11*u, 13*u)
                lineTo(10*u, 22*u); lineTo(19*u, 10*u); lineTo(13*u, 10*u); close()
            }, color, style = stroke)
            1 -> {
                drawRect(color, pt(4, 5), Size(16*u, 15*u), style = stroke)
                drawLine(color, pt(4, 10), pt(20, 10), stroke.width)
                drawLine(color, pt(8, 3), pt(8, 7), stroke.width)
                drawLine(color, pt(16, 3), pt(16, 7), stroke.width)
            }
            else -> {
                drawRect(color, pt(6, 3), Size(12*u, 18*u), style = stroke)
                drawLine(color, pt(10, 17), pt(14, 17), stroke.width)
            }
        }
    }
}
