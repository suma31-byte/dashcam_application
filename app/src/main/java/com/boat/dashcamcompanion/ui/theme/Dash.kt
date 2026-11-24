/*
 * "New Theme" from the DashCam Final Designs (Figma, page "New Theme_UI/UX 26 OCT"):
 * dark background, grey cards, bright green accent, round back button, green pill CTAs with an
 * arrow circle, green toggles and checkboxes. Values are taken from the Figma frames
 * "Safety 1.2" and "Recordings" (colours, sizes, radii, stroke widths, arrow geometry).
 */
package com.boat.dashcamcompanion.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Colour tokens of the new theme (Figma variables greys/…, primary/…, secondary/…). */
object DashColors {
    val Background = Color(0xFF181818)      // screen background
    val Card = Color(0xFF242424)            // greys/grey-24 - cards, arrow circle
    val SubCard = Color(0xFF1D1D1D)         // secondary/sub-cards - toggle track, chips
    val Grey3F = Color(0xFF3F3F3F)          // greys/grey-3f - back button
    val Accent = Color(0xFF36EB87)          // green accent
    val Text = Color(0xFFFFFFFF)            // primary/white
    val TextPrimary = Color(0xFFEDEDED)     // primary/text
    val TextSecondary = Color(0xFFB5B5B5)   // secondary/text
    val TextMuted = Color(0xFFCCCCCC)       // boAt Neutral/200
    val CheckOff = Color(0xFF595959)
    val Divider = Color(0xFF2F2F2F)
}

/**
 * Text styles of the new theme. The design uses the "Metropolis" font; until its font files are
 * added to res/font, the phone's default sans-serif is used with the same sizes and spacing.
 */
object DashType {
    private val family = FontFamily.Default
    val ScreenTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 24.sp,
        lineHeight = 1.2.em, letterSpacing = (-0.48).sp, color = DashColors.Text)
    val Body = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 1.4.em, color = DashColors.TextMuted)
    val RowTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 16.sp,
        lineHeight = 1.3.em, color = DashColors.Text)
    val RowSubtitle = TextStyle(fontFamily = family, fontSize = 11.sp, lineHeight = 1.4.em, color = DashColors.TextSecondary)
    val Button = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 14.sp,
        lineHeight = 1.4.em, letterSpacing = (-0.28).sp, color = DashColors.Text)
    val Caption = TextStyle(fontFamily = family, fontSize = 13.sp, color = Color(0xFFB3B3B3))
    val DateHeader = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = DashColors.Text)
}

/**
 * The design's arrow (a 13-long line with a pointer, stroke 1.4), pointing RIGHT before
 * rotation: rotation 180 = back arrow, -45 = the "↗" on CTAs.
 */
@Composable
fun DashArrow(color: Color, rotation: Float, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size).rotate(rotation)) {
        val s = this.size.minDimension / 20f
        val stroke = Stroke(width = 1.4f * s, cap = StrokeCap.Butt, join = StrokeJoin.Miter)
        val cy = this.size.height / 2f
        val left = (this.size.width - 17f * s) / 2f
        // line
        drawLine(color, Offset(left, cy), Offset(left + 13f * s, cy), strokeWidth = 1.4f * s)
        // pointer (from Figma vector "Pointer": 6.68 x 17.36)
        val px = left + 13f * s - 3f * s
        val p = Path().apply {
            moveTo(px, cy - 8.68f * s)
            lineTo(px + 6.68f * s, cy - 2f * s)
            lineTo(px + 6.68f * s, cy + 2f * s)
            lineTo(px, cy + 8.68f * s)
        }
        drawPath(p, color, style = stroke)
    }
}

/** Screen title row: round grey back button (40 dp) + 24 sp title, optional action on the right. */
@Composable
fun DashTopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().background(DashColors.Background).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(DashColors.Grey3F).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { DashArrow(DashColors.Text, rotation = 180f) }
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = DashType.ScreenTitle, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Grey rounded card (radius 16, padding 16). */
@Composable
fun DashCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DashColors.Card).padding(16.dp),
        content = content
    )
}

/** Toggle from the design: 86 × 48 dark track, 40 dp knob - green on the right when on. */
@Composable
fun DashToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.width(86.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(DashColors.SubCard)
            .clickable { onCheckedChange(!checked) }.padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (checked) DashColors.Accent else DashColors.Grey3F))
    }
}

/** Checkbox from the design: 20 dp, radius 4, green with dark tick when on, grey when off. */
@Composable
fun DashCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).clickable { onCheckedChange(!checked) }) {
        val s = size.width / 20f
        drawRect(if (checked) DashColors.Accent else DashColors.CheckOff)
        val tick = Path().apply {
            moveTo(5f * s, 6.5f * s + 4.31f * s)
            lineTo(5f * s + 2.31f * s, 6.5f * s + 7f * s)
            lineTo(15f * s, 6.5f * s)
        }
        drawPath(tick, if (checked) Color(0xFF242424) else Color(0xFF3F3F3F),
            style = Stroke(width = 1.5f * s, cap = StrokeCap.Round))
    }
}

/** Card header: title (16 sp medium) + small subtitle, with an optional toggle on the right. */
@Composable
fun DashCardHeader(title: String, subtitle: String?, checked: Boolean? = null, onCheckedChange: (Boolean) -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = DashType.RowTitle)
            if (subtitle != null) Text(subtitle, style = DashType.RowSubtitle, modifier = Modifier.width(190.dp))
        }
        if (checked != null) DashToggle(checked, onCheckedChange)
    }
}

/** Row with a title and a checkbox, separated from the next row by a thin divider. */
@Composable
fun DashCheckRow(title: String, checked: Boolean, enabled: Boolean = true, divider: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = DashType.RowTitle.copy(color = if (enabled) DashColors.Text else DashColors.TextSecondary),
                modifier = Modifier.weight(1f))
            DashCheckbox(checked && enabled, { if (enabled) onCheckedChange(it) })
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(DashColors.Divider))
    }
}

/** Green pill CTA (48 dp, radius 24) with the label on the left and a dark circle with "↗" on the right. */
@Composable
fun DashPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (enabled) DashColors.Accent else DashColors.Grey3F)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 24.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, style = DashType.Button)
        Box(Modifier.size(40.dp).clip(CircleShape).background(DashColors.Card), contentAlignment = Alignment.Center) {
            DashArrow(DashColors.Accent, rotation = -45f)
        }
    }
}

/** Filter chip from the Recordings screen: green when selected, dark otherwise. */
@Composable
fun DashChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(100.dp)).background(if (selected) DashColors.Accent else DashColors.SubCard)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = DashType.Button.copy(fontSize = 13.sp,
            color = if (selected) DashColors.SubCard else DashColors.TextSecondary))
    }
}
