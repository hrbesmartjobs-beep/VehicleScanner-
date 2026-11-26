package com.vehiclescanner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.random.Random

// Palette: deep black, electric blue, metallic silver.
private val IntroBlack = Color(0xFF020306)
private val NeonBlue = Color(0xFF2E9BFF)
private val NeonCyan = Color(0xFF7FD4FF)
private val SilverText = Color(0xFFC9CED6)
private val SilverStops = listOf(
    Color(0xFF7D838C), Color(0xFFE9EDF2), Color(0xFFFFFFFF), Color(0xFFA9B0BA), Color(0xFFF4F6F9), Color(0xFF7D838C)
)

/** Times in ms. The intro animates in, then waits on the ENTER button. */
private class Timeline(
    val devIn: Float, val devOut: Float, val subIn: Float,
    val finalIn: Float, val featuresIn: Float, val enterIn: Float
) { val end get() = enterIn + 900f }

// First launch: long cinematic reveal. Later launches: shorter, but the name still gets its moment.
private val FullTimeline = Timeline(devIn = 300f, devOut = 4200f, subIn = 1500f, finalIn = 4200f, featuresIn = 5600f, enterIn = 6800f)
private val ShortTimeline = Timeline(devIn = 200f, devOut = 2600f, subIn = 900f, finalIn = 2600f, featuresIn = 3300f, enterIn = 3900f)

/** 0→1 fade-in from [start], optional 1→0 fade-out ending at [end]. */
private fun fade(t: Float, start: Float, end: Float? = null, fadeIn: Float = 550f, fadeOut: Float = 420f): Float {
    if (t < start) return 0f
    if (end != null && t >= end) return 0f
    val a = ((t - start) / fadeIn).coerceIn(0f, 1f)
    val b = if (end != null) ((end - t) / fadeOut).coerceIn(0f, 1f) else 1f
    return min(a, b)
}

private fun easeOut(x: Float): Float { val c = x.coerceIn(0f, 1f); return 1f - (1f - c) * (1f - c) * (1f - c) }

/**
 * Premium opening: developer identity (CREATED & DEVELOPED BY · VIGHNESH) → brand with a
 * holographic car scan and feature reveal → a glowing ENTER button. The app opens only when
 * ENTER is pressed. Pure Compose drawing (no bitmaps / video).
 */
@Composable
fun IntroScreen(full: Boolean, onFinished: () -> Unit) {
    val tl = if (full) FullTimeline else ShortTimeline
    val clock = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    var exiting by remember { mutableStateOf(false) }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) { clock.animateTo(tl.end, tween(tl.end.toInt(), easing = LinearEasing)) }
    LaunchedEffect(exiting) {
        if (exiting) { exit.animateTo(1f, tween(650, easing = LinearEasing)); finish() }
    }
    val loop = rememberInfiniteTransition(label = "intro")
    val shimmer by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "shimmer")
    val drift by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "drift")
    val scanLoop by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "scan")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulse")
    val t = clock.value
    val ex = exit.value

    Box(Modifier.fillMaxSize().background(IntroBlack)) {
        Backdrop(drift = drift, time = t + ex * 3000f, alpha = fade(t, 0f, fadeIn = 900f))
        HudFrame(alpha = fade(t, 600f, fadeIn = 900f), sweep = shimmer)

        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
            val w = maxWidth.value

            // ---------------- 1. Developer identity, centre stage
            val devA = fade(t, tl.devIn, tl.devOut, fadeOut = 600f)
            if (devA > 0f) {
                val reveal = easeOut((t - tl.devIn) / 1300f)
                Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                    HoloRings(spin = spin, alpha = devA * 0.9f, modifier = Modifier.size((w * 0.92f).dp))
                    Column(Modifier.alpha(devA), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "CREATED & DEVELOPED BY",
                            Modifier.alpha(fade(t, tl.devIn, fadeIn = 700f)),
                            color = NeonCyan, fontSize = (w * 0.038f).coerceIn(11f, 17f).sp,
                            letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "VIGHNESH",
                            modifier = Modifier.drawWithContent {
                                clipRect(right = size.width * reveal) { this@drawWithContent.drawContent() }
                            },
                            style = metallic(shimmer, (w * 0.14f).coerceIn(36f, 88f), letterSpacing = 4f + 14f * (1f - reveal)),
                            maxLines = 1
                        )
                        Spacer(Modifier.height(14.dp))
                        NeonRule(progress = reveal, modifier = Modifier.width((w * 0.62f).dp).height(10.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "ENGINEER • DEVELOPER • INNOVATOR",
                            Modifier.alpha(fade(t, tl.subIn, fadeIn = 800f)),
                            color = SilverText, fontSize = (w * 0.033f).coerceIn(10f, 15f).sp,
                            letterSpacing = 3.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1
                        )
                    }
                }
            }

            // ---------------- 2. Final screen: name stays, brand, hologram car, features, ENTER
            val finA = fade(t, tl.finalIn, fadeIn = 800f)
            if (finA > 0f) {
                Column(
                    Modifier.fillMaxSize().alpha(finA),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("CREATED & DEVELOPED BY", color = NeonCyan, fontSize = 11.sp, letterSpacing = 3.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("VIGHNESH", style = metallic(shimmer, (w * 0.095f).coerceIn(26f, 52f), letterSpacing = 6f), maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CarHologram(
                            scan = scanLoop,
                            reveal = easeOut((t - tl.finalIn) / 1000f),
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(2.35f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("VEHICLE SCANNER", style = metallic(shimmer, (w * 0.075f).coerceIn(22f, 40f), letterSpacing = 5f), maxLines = 1)
                        Spacer(Modifier.height(6.dp))
                        Text("SCAN • MATCH • VERIFY", color = NeonCyan, fontSize = 12.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold)
                    }
                    val features = listOf(
                        Icons.Default.DocumentScanner to "Multi-Plate Detection",
                        Icons.Default.WifiOff to "Offline Plate Reading",
                        Icons.Default.Verified to "Instant Database Match",
                        Icons.Default.TableView to "Excel Import & Export"
                    )
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (col in 0..1) {
                                val i = row * 2 + col
                                FeatureChip(features[i].first, features[i].second, fade(t, tl.featuresIn + i * 180f, fadeIn = 450f), Modifier.weight(1f))
                            }
                        }
                    }
                    EnterButton(
                        alpha = fade(t, tl.enterIn, fadeIn = 700f), pulse = pulse, sweep = shimmer,
                        enabled = t >= tl.enterIn && !exiting,
                        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(0.78f).height(64.dp)
                    ) { exiting = true }
                }
            }

            Text(
                "© 2026 VIGHNESH. All Rights Reserved.",
                Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).alpha(0.7f * fade(t, tl.devIn + 400f, fadeIn = 900f)),
                color = SilverText, fontSize = 10.sp, letterSpacing = 1.sp
            )
        }

        // Exit: blue light burst, then fade to black into the app.
        if (ex > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height * 0.82f)
                val r = size.maxDimension * easeOut(ex) * 1.2f
                drawCircle(Brush.radialGradient(listOf(NeonCyan.copy(alpha = 0.55f * (1f - ex)), NeonBlue.copy(alpha = 0.25f * (1f - ex)), Color.Transparent), c, r.coerceAtLeast(1f)), r, c)
                drawRect(IntroBlack.copy(alpha = ((ex - 0.35f) / 0.65f).coerceIn(0f, 1f)))
            }
        }
    }
}

/** Glass pill with neon edge, breathing glow and a light sweep. */
@Composable
private fun EnterButton(alpha: Float, pulse: Float, sweep: Float, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val glow = 0.35f + 0.35f * kotlin.math.sin(pulse * 2f * Math.PI.toFloat()).let { (it + 1f) / 2f }
    Box(modifier.alpha(alpha), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cr = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            for (k in 3 downTo 1) {
                val g = k * 6f
                drawRoundRect(NeonBlue.copy(alpha = glow * 0.12f * (4 - k)), topLeft = Offset(-g, -g),
                    size = Size(size.width + 2 * g, size.height + 2 * g), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2 + g))
            }
        }
        Box(
            Modifier.fillMaxSize().clip(shape)
                .background(Brush.horizontalGradient(listOf(Color(0xFF0B2A4F), Color(0xFF123E73), Color(0xFF0B2A4F))))
                .border(1.5.dp, Brush.horizontalGradient(listOf(NeonCyan, NeonBlue, NeonCyan)), shape)
                .pointerInput(enabled) { if (enabled) detectTapGestures { onClick() } }
                .drawWithContent {
                    drawContent()
                    val x = (sweep * 2.2f - 0.6f) * size.width
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                        start = Offset(x - 80f, 0f), end = Offset(x + 80f, size.height)))
                },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ENTER", color = Color.White, fontSize = 18.sp, letterSpacing = 6.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = NeonCyan, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Rotating holographic rings and ticks behind the name. */
@Composable
private fun HoloRings(spin: Float, alpha: Float, modifier: Modifier) {
    Canvas(modifier.alpha(alpha)) {
        val c = center; val r = size.minDimension / 2
        drawCircle(NeonBlue.copy(alpha = 0.10f), r * 0.98f, c, style = Stroke(1.5f))
        rotate(spin, c) {
            drawArc(NeonCyan.copy(alpha = 0.55f), 0f, 70f, false, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f), style = Stroke(3f, cap = StrokeCap.Round))
            drawArc(NeonBlue.copy(alpha = 0.45f), 180f, 110f, false, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f), style = Stroke(3f, cap = StrokeCap.Round))
            for (i in 0 until 60) {
                val a = Math.toRadians(i * 6.0).toFloat(); val l = if (i % 5 == 0) 16f else 7f
                val o = Offset(c.x + kotlin.math.cos(a) * r * 0.98f, c.y + kotlin.math.sin(a) * r * 0.98f)
                val inner = Offset(c.x + kotlin.math.cos(a) * (r * 0.98f - l), c.y + kotlin.math.sin(a) * (r * 0.98f - l))
                drawLine(NeonBlue.copy(alpha = 0.35f), inner, o, strokeWidth = 1.5f)
            }
        }
        rotate(-spin * 1.6f, c) {
            drawArc(SilverText.copy(alpha = 0.25f), 40f, 140f, false, Offset(c.x - r * 0.75f, c.y - r * 0.75f), Size(r * 1.5f, r * 1.5f), style = Stroke(1.5f))
            drawArc(NeonCyan.copy(alpha = 0.30f), 230f, 60f, false, Offset(c.x - r * 0.75f, c.y - r * 0.75f), Size(r * 1.5f, r * 1.5f), style = Stroke(4f, cap = StrokeCap.Round))
        }
    }
}

/** Thin HUD corner frame around the whole screen with a travelling light. */
@Composable
private fun HudFrame(alpha: Float, sweep: Float) {
    Canvas(Modifier.fillMaxSize().systemBarsPadding().padding(10.dp).alpha(alpha)) {
        val l = 46.dp.toPx(); val c = NeonBlue.copy(alpha = 0.7f); val sw = 2.dp.toPx()
        val (w, h) = size.width to size.height
        drawLine(c, Offset(0f, 0f), Offset(l, 0f), sw); drawLine(c, Offset(0f, 0f), Offset(0f, l), sw)
        drawLine(c, Offset(w, 0f), Offset(w - l, 0f), sw); drawLine(c, Offset(w, 0f), Offset(w, l), sw)
        drawLine(c, Offset(0f, h), Offset(l, h), sw); drawLine(c, Offset(0f, h), Offset(0f, h - l), sw)
        drawLine(c, Offset(w, h), Offset(w - l, h), sw); drawLine(c, Offset(w, h), Offset(w, h - l), sw)
        val x = sweep * w
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, NeonCyan.copy(alpha = 0.8f), Color.Transparent), startX = x - 120f, endX = x + 120f),
            Offset(x - 120f, 0f), Offset(x + 120f, 0f), strokeWidth = sw)
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, NeonCyan.copy(alpha = 0.8f), Color.Transparent), startX = w - x - 120f, endX = w - x + 120f),
            Offset(w - x - 120f, h), Offset(w - x + 120f, h), strokeWidth = sw)
    }
}

/** Brushed-metal silver text with a moving highlight and a soft electric-blue glow. */
private fun metallic(shimmer: Float, sizeSp: Float, letterSpacing: Float): TextStyle {
    val x = -600f + shimmer * 1800f
    return TextStyle(
        brush = Brush.linearGradient(SilverStops, start = Offset(x, 0f), end = Offset(x + 520f, 160f), tileMode = TileMode.Mirror),
        fontSize = sizeSp.sp,
        fontWeight = FontWeight.Light,
        fontFamily = FontFamily.SansSerif,
        letterSpacing = letterSpacing.sp,
        shadow = Shadow(NeonBlue.copy(alpha = 0.85f), Offset.Zero, blurRadius = 28f)
    )
}

@Composable
private fun NeonRule(progress: Float, modifier: Modifier) {
    Canvas(modifier) {
        val cy = size.height / 2
        val half = size.width / 2 * progress
        val c = size.width / 2
        val brush = Brush.horizontalGradient(
            listOf(Color.Transparent, NeonBlue, NeonCyan, NeonBlue, Color.Transparent),
            startX = c - half, endX = c + half
        )
        drawLine(brush, Offset(c - half, cy), Offset(c + half, cy), strokeWidth = 8f, alpha = 0.25f, cap = StrokeCap.Round)
        drawLine(brush, Offset(c - half, cy), Offset(c + half, cy), strokeWidth = 2.2f, cap = StrokeCap.Round)
    }
}

@Composable
private fun GlowProgress(progress: Float, modifier: Modifier) {
    Canvas(modifier) {
        val cy = size.height / 2
        drawLine(Color.White.copy(alpha = 0.10f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 4f, cap = StrokeCap.Round)
        val end = size.width * progress
        if (end > 1f) {
            val brush = Brush.horizontalGradient(listOf(NeonBlue, NeonCyan), startX = 0f, endX = end)
            drawLine(brush, Offset(0f, cy), Offset(end, cy), strokeWidth = 12f, alpha = 0.22f, cap = StrokeCap.Round)
            drawLine(brush, Offset(0f, cy), Offset(end, cy), strokeWidth = 4f, cap = StrokeCap.Round)
            drawCircle(NeonCyan, radius = 4.5f, center = Offset(end, cy))
        }
    }
}

@Composable
private fun FeatureChip(icon: ImageVector, label: String, alpha: Float, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .alpha(alpha)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.02f))))
            .border(1.dp, Brush.linearGradient(listOf(NeonBlue.copy(alpha = 0.55f), Color.White.copy(alpha = 0.10f))), shape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = NeonCyan)
        Spacer(Modifier.width(9.dp))
        Text(label, color = Color(0xFFE6E9EE), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 15.sp)
    }
}

// ------------------------------------------------------------------ backdrop: perspective grid + particles

private class Particle(val x: Float, val y: Float, val speed: Float, val r: Float, val a: Float)

private val particles: List<Particle> = Random(2026).let { rnd ->
    List(42) { Particle(rnd.nextFloat(), rnd.nextFloat(), 0.012f + rnd.nextFloat() * 0.03f, 0.8f + rnd.nextFloat() * 1.8f, 0.15f + rnd.nextFloat() * 0.45f) }
}

@Composable
private fun Backdrop(drift: Float, time: Float, alpha: Float) {
    Canvas(Modifier.fillMaxSize().alpha(alpha)) {
        val w = size.width; val h = size.height
        val horizon = h * 0.60f
        val cx = w / 2

        // Horizon glow.
        drawRect(
            Brush.radialGradient(listOf(NeonBlue.copy(alpha = 0.22f), Color.Transparent), center = Offset(cx, horizon), radius = w * 0.85f),
            topLeft = Offset(0f, horizon - w * 0.6f), size = Size(w, w * 1.2f)
        )
        // Floor grid, scrolling toward the viewer.
        val rows = 14
        for (i in 0 until rows) {
            val f = (i + drift) / rows
            val y = horizon + (h - horizon) * f * f
            drawLine(NeonBlue.copy(alpha = 0.32f * f), Offset(0f, y), Offset(w, y), strokeWidth = 1f + f)
        }
        for (k in -10..10) {
            val xb = cx + k * w / 5f
            drawLine(NeonBlue.copy(alpha = 0.16f), Offset(cx + k * w * 0.018f, horizon), Offset(xb, h), strokeWidth = 1f)
        }
        // Floating particles.
        val secs = time / 1000f
        for (p in particles) {
            val y = ((p.y - secs * p.speed) % 1f + 1f) % 1f
            drawCircle(NeonCyan.copy(alpha = p.a * 0.6f), radius = p.r * density / 2f, center = Offset(p.x * w, y * h))
        }
    }
}

// ------------------------------------------------------------------ holographic sports car

/** Side profile of a low sports coupé, in a w×h box (aspect ≈ 2.35). */
private fun carBody(w: Float, h: Float): Path {
    fun X(v: Float) = v * w
    fun Y(v: Float) = v * h
    val r = 0.074f * w
    val wheelY = Y(0.80f)
    return Path().apply {
        moveTo(X(0.05f), wheelY)
        lineTo(X(0.035f), Y(0.62f))
        cubicTo(X(0.035f), Y(0.53f), X(0.07f), Y(0.49f), X(0.13f), Y(0.47f))
        lineTo(X(0.31f), Y(0.41f))
        cubicTo(X(0.37f), Y(0.24f), X(0.45f), Y(0.15f), X(0.56f), Y(0.15f))
        cubicTo(X(0.64f), Y(0.15f), X(0.70f), Y(0.22f), X(0.765f), Y(0.36f))
        cubicTo(X(0.86f), Y(0.39f), X(0.95f), Y(0.43f), X(0.97f), Y(0.53f))
        lineTo(X(0.975f), Y(0.70f))
        cubicTo(X(0.975f), Y(0.77f), X(0.96f), wheelY, X(0.93f), wheelY)
        lineTo(X(0.78f) + r * 1.12f, wheelY)
        arcTo(Rect(Offset(X(0.78f), wheelY), r * 1.12f), 0f, -180f, false)
        lineTo(X(0.22f) + r * 1.12f, wheelY)
        arcTo(Rect(Offset(X(0.22f), wheelY), r * 1.12f), 0f, -180f, false)
        close()
    }
}

private fun carWindow(w: Float, h: Float) = Path().apply {
    moveTo(0.355f * w, 0.40f * h)
    quadraticTo(0.44f * w, 0.205f * h, 0.56f * w, 0.205f * h)
    quadraticTo(0.655f * w, 0.205f * h, 0.715f * w, 0.355f * h)
    close()
}

@Composable
private fun CarHologram(scan: Float, reveal: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val body = carBody(w, h)
        val window = carWindow(w, h)
        val r = 0.074f * w
        val wheelY = 0.80f * h

        // Reveal the car left→right.
        clipRect(right = w * reveal) {
            // Ground reflection.
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, NeonBlue.copy(alpha = 0.7f), Color.Transparent)),
                Offset(0f, wheelY + r * 0.95f), Offset(w, wheelY + r * 0.95f), strokeWidth = 2f
            )
            // Glass body fill + neon outline (three glow passes, then metal edge).
            drawPath(body, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), NeonBlue.copy(alpha = 0.05f))))
            glowStroke(body)
            drawPath(window, Brush.verticalGradient(listOf(NeonCyan.copy(alpha = 0.22f), NeonBlue.copy(alpha = 0.05f))))
            drawPath(window, SilverText.copy(alpha = 0.7f), style = Stroke(1.4f))
            // Character line, door line, headlight.
            drawLine(SilverText.copy(alpha = 0.45f), Offset(0.12f * w, 0.56f * h), Offset(0.93f * w, 0.55f * h), strokeWidth = 1.2f)
            drawLine(SilverText.copy(alpha = 0.30f), Offset(0.50f * w, 0.42f * h), Offset(0.51f * w, 0.74f * h), strokeWidth = 1f)
            drawLine(NeonCyan, Offset(0.90f * w, 0.47f * h), Offset(0.965f * w, 0.52f * h), strokeWidth = 3f, cap = StrokeCap.Round)
            // Wheels.
            for (cx in floatArrayOf(0.22f * w, 0.78f * w)) {
                drawCircle(Color.Black, r, Offset(cx, wheelY))
                drawCircle(SilverText.copy(alpha = 0.85f), r, Offset(cx, wheelY), style = Stroke(2.2f))
                drawCircle(NeonBlue.copy(alpha = 0.8f), r * 0.62f, Offset(cx, wheelY), style = Stroke(1.6f))
                drawCircle(SilverText, r * 0.14f, Offset(cx, wheelY))
            }
        }

        // Holographic targeting brackets.
        brackets(Rect(0.01f * w, 0.06f * h, 0.99f * w, 0.99f * h), 0.07f * w, alpha = reveal)

        // Scanning beam with a lit trail inside the body.
        if (scan >= 0f) {
            val x = scan * w
            clipPath(body) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, NeonBlue.copy(alpha = 0.35f)), startX = x - w * 0.22f, endX = x),
                    topLeft = Offset(x - w * 0.22f, 0f), size = Size(w * 0.22f, h)
                )
            }
            drawLine(NeonCyan.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, h), strokeWidth = 14f)
            drawLine(NeonCyan, Offset(x, 0.04f * h), Offset(x, h), strokeWidth = 2f)
        }
    }
}

private fun DrawScope.glowStroke(path: Path) {
    drawPath(path, NeonBlue.copy(alpha = 0.10f), style = Stroke(14f))
    drawPath(path, NeonBlue.copy(alpha = 0.22f), style = Stroke(7f))
    drawPath(path, NeonBlue.copy(alpha = 0.9f), style = Stroke(2.6f))
    drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(1f))
}

private fun DrawScope.brackets(r: Rect, len: Float, alpha: Float) {
    val c = NeonCyan.copy(alpha = 0.8f * alpha)
    val s = 2f
    drawLine(c, r.topLeft, r.topLeft + Offset(len, 0f), s); drawLine(c, r.topLeft, r.topLeft + Offset(0f, len), s)
    drawLine(c, r.topRight, r.topRight - Offset(len, 0f), s); drawLine(c, r.topRight, r.topRight + Offset(0f, len), s)
    drawLine(c, r.bottomLeft, r.bottomLeft + Offset(len, 0f), s); drawLine(c, r.bottomLeft, r.bottomLeft - Offset(0f, len), s)
    drawLine(c, r.bottomRight, r.bottomRight - Offset(len, 0f), s); drawLine(c, r.bottomRight, r.bottomRight - Offset(0f, len), s)
}
