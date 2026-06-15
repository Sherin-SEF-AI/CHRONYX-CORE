package com.chronyx.harness.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronyx.harness.ui.theme.ChronyxColors

/**
 * Operational Materialism primitives. Greyscale data; the phosphor accent is passed explicitly only
 * where a value represents earned/healthy live state. State transitions are binary — no animation.
 */

/** The CHRONYX time-sync reticle, drawn vector-style. [active] lights the center lock dot in phosphor. */
@Composable
fun ChronyxMark(modifier: Modifier = Modifier, active: Boolean = true) {
    val accent = ChronyxColors.Phosphor
    Canvas(modifier) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val r = size.minDimension / 2f
        drawCircle(accent, radius = r * 0.82f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.07f))
        drawCircle(accent, radius = r * 0.48f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.035f))
        val tick = r * 0.18f
        val sw = r * 0.07f
        drawLine(accent, Offset(cx, cy - r), Offset(cx, cy - r + tick), strokeWidth = sw)
        drawLine(accent, Offset(cx, cy + r), Offset(cx, cy + r - tick), strokeWidth = sw)
        drawLine(accent, Offset(cx - r, cy), Offset(cx - r + tick, cy), strokeWidth = sw)
        drawLine(accent, Offset(cx + r, cy), Offset(cx + r - tick, cy), strokeWidth = sw)
        drawCircle(if (active) accent else ChronyxColors.TextDim, radius = r * 0.12f, center = Offset(cx, cy))
    }
}

/** App header: the mark + CHRONYX wordmark. The mark's lock dot is earned (phosphor) only when [locked]. */
@Composable
fun ChronyxHeader(subtitle: String, locked: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(ChronyxColors.Surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChronyxMark(Modifier.size(26.dp), active = locked)
        Spacer(Modifier.width(10.dp))
        Text("CHRONYX", color = ChronyxColors.TextPrimary, fontFamily = FontFamily.Monospace,
            fontSize = 18.sp, letterSpacing = 4.sp)
        Spacer(Modifier.width(10.dp))
        Text(subtitle, color = ChronyxColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
}

/** A label : value readout row. [valueColor] defaults to greyscale; pass phosphor only when earned. */
@Composable
fun ReadoutRow(
    label: String,
    value: String,
    valueColor: Color = ChronyxColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = ChronyxColors.TextSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = valueColor,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

/** A bordered channel section with a header. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ChronyxColors.Hairline)
            .background(ChronyxColors.SurfaceRaised)
            .padding(12.dp),
    ) {
        Text(
            title,
            color = ChronyxColors.TextDim,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

/**
 * A binary status pill. When [earned] is true the accent fills it; otherwise it is greyscale outline.
 * Used for SYNC LOCKED / ARMED / RECORDING — the only place the accent appears.
 */
@Composable
fun StatusPill(text: String, earned: Boolean, modifier: Modifier = Modifier) {
    val bg = if (earned) ChronyxColors.Phosphor else Color.Transparent
    val fg = if (earned) ChronyxColors.Surface else ChronyxColors.TextDim
    Text(
        text,
        color = fg,
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(bg)
            .border(1.dp, if (earned) ChronyxColors.Phosphor else ChronyxColors.Hairline)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
