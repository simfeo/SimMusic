package com.idimus.simmusic

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** One entry of the index bar: its letter and the lazy-list index it jumps to. */
data class Section(val letter: String, val index: Int)

/**
 * Which alphabet slot a label belongs to: the slot of the letter its first character equals at primary
 * strength (case and accents ignored), else the closest preceding letter of the same script, else "#"
 * (alphabet.length). Same-script matching matters because a uk alphabet lists Cyrillic before Latin,
 * which is not collation order.
 */
private fun bucket(label: String, alphabet: String): Int {
    val c = label.trim().firstOrNull() ?: return alphabet.length
    if (!c.isLetter()) return alphabet.length
    val s = c.toString()
    alphabet.forEachIndexed { k, a -> if (Collation.primary.compare(a.toString(), s) == 0) return k }
    val script = Character.UnicodeScript.of(c.code)
    var best = -1
    alphabet.forEachIndexed { k, a ->
        if (Character.UnicodeScript.of(a.code) == script && Collation.primary.compare(a.toString(), s) < 0) best = k
    }
    return if (best >= 0) best else alphabet.length
}

/**
 * The full alphabet plus "#": a letter with items jumps to its first item; a letter
 * without items jumps to the next letter that has some, or the last item.
 */
fun sections(labels: List<String>, alphabet: String, offset: Int = 0): List<Section> {
    if (labels.size < 2) return emptyList()
    val first = IntArray(alphabet.length + 1) { -1 }
    labels.forEachIndexed { i, l -> val b = bucket(l, alphabet); if (first[b] < 0) first[b] = i }
    var next = labels.lastIndex
    val idx = IntArray(first.size)
    for (k in first.indices.reversed()) { if (first[k] >= 0) next = first[k]; idx[k] = next }
    return (alphabet.map { it.toString() } + "#").mapIndexed { k, l -> Section(l, offset + idx[k]) }
}

/**
 * Index scroller on the right edge. Dragging jumps the list and shows a letter bubble
 * beside the finger with a haptic tick per letter. When letters do not fit at 17dp each, only every
 * n-th letter is drawn with a dot between, while touch still resolves every letter.
 */
@Composable
fun IndexBar(sections: List<Section>, state: LazyListState, modifier: Modifier = Modifier) {
    if (sections.size < 2) return
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    var height by remember { mutableIntStateOf(1) }
    var colTop by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf<Int?>(null) }
    var touchY by remember { mutableFloatStateOf(0f) }

    fun select(y: Float) {
        touchY = y.coerceIn(0f, height.toFloat())
        val i = (touchY / height * sections.size).toInt().coerceIn(0, sections.lastIndex)
        if (i != active) {
            active = i
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            scope.launch { state.scrollToItem(sections[i].index) }
        }
    }

    BoxWithConstraints(modifier.fillMaxHeight().padding(top = 36.dp, bottom = 18.dp)) {
        val fit = (maxHeight.value / 17f).toInt().coerceAtLeast(2)
        val step = if (sections.size > fit) ceil(sections.size / fit.toFloat()).toInt() else 1
        active?.let { i ->
            Box(
                Modifier.align(Alignment.TopEnd)
                    .offset {
                        val r = 35.dp.roundToPx()
                        val y = (colTop + touchY).toInt().coerceIn(18.dp.roundToPx(), constraints.maxHeight - 18.dp.roundToPx())
                        // Bubble center sits 70dp left of the bar center (bar: 22dp wide, 8dp from the edge).
                        IntOffset(-(19.dp.roundToPx() + 70.dp.roundToPx() - r), y - r)
                    }
                    .size(70.dp).clip(CircleShape).background(SevenColors.accent),
                contentAlignment = Alignment.Center,
            ) { Text(sections[i].letter, color = SevenColors.onAccent, fontSize = with(density) { 41.dp.toSp() }, fontWeight = FontWeight.Medium) }
        }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).width(22.dp)
                .heightIn(max = (sections.size * 20).dp).fillMaxHeight()
                .clip(RoundedCornerShape(11.dp))
                .background(if (dark) Color(0x993A3A3D) else Color(0x80E9E9EC))
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .onPlaced { colTop = it.positionInParent().y }
                .pointerInput(sections) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        select(down.position.y)
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            ch.consume()
                            select(ch.position.y)
                        }
                        active = null
                    }
                }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            sections.forEachIndexed { i, s ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val shown = step == 1 || i % step == 0 || i == sections.lastIndex
                    val dot = !shown && i % step == step / 2
                    val color = if (i == active) SevenColors.accent else if (dark) Color(0xFFA0A0A0) else Color(0xFF686868)
                    if (shown || i == active) {
                        Text(s.letter, fontSize = with(density) { 11.dp.toSp() }, lineHeight = 11.sp, color = color,
                            fontWeight = if (i == active) FontWeight.Bold else FontWeight.Medium)
                    } else if (dot) {
                        Box(Modifier.size(1.5.dp).clip(CircleShape).background(color))
                    }
                }
            }
        }
    }
}
