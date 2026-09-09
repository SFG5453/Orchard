package dev.sfg.orchard.mobile.ui.glass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Deterministic, network-free scene for adb gfxinfo and visual inspection. Debug APK only. */
class GlassBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val enabled = intent.getBooleanExtra("glass", true)
        val animate = intent.getBooleanExtra("animate", true)
        setContent {
            val scene = rememberGlassScene()
            val phase = remember { mutableFloatStateOf(0f) }
            LaunchedEffect(animate) {
                if (animate) {
                    val start = withFrameNanos { it }
                    while (true) withFrameNanos { phase.floatValue = (it - start) / 1e9f }
                }
            }
            CompositionLocalProvider(LocalGlass provides rememberGlassStyle(enabled, remember { mutableStateOf(Color(0xFF89ADC9)) }), LocalGlassScene provides scene) {
                Box(Modifier.fillMaxSize().background(Color(0xFF18212B))) {
                    Box(Modifier.fillMaxSize().glassSceneSource(scene)) {
                        Canvas(Modifier.fillMaxSize().glassWashSource(scene)) {
                            val shift = (phase.floatValue * 90f) % 320f
                            for (row in -1..12) for (col in 0..3) {
                                val palette = listOf(Color(0xFFFACB38), Color(0xFFEA795D), Color(0xFF478BB3), Color(0xFF69AA83))
                                drawRect(palette[(row + col + 20) % 4], Offset(col * size.width / 4, row * 320f + shift), Size(size.width / 4 - 8f, 308f))
                                drawCircle(Color.White.copy(alpha = .6f), 44f, Offset(col * size.width / 4 + 70f, row * 320f + shift + 100f))
                            }
                        }
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Liquid glass • Android", color = Color.White, fontSize = 24.sp)
                            repeat(8) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    repeat(2) { col ->
                                        Box(Modifier.weight(1f).height(64.dp).glassPane(RoundedCornerShape(24.dp), if (col == 0) GlassTone.PANEL else GlassTone.CONTROL), contentAlignment = Alignment.Center) {
                                            Text("Pane ${row * 2 + col + 1}", color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.fillMaxWidth().height(64.dp).glassPane(RoundedCornerShape(32.dp), GlassTone.CHROME), contentAlignment = Alignment.Center) {
                            Text("Random Song     •     Please don't sue me           ▶     ▶▶", color = Color.White, fontSize = 18.sp)
                        }
                        Box(Modifier.fillMaxWidth().height(72.dp).glassPane(RoundedCornerShape(36.dp), GlassTone.CHROME), contentAlignment = Alignment.Center) {
                            Text("Home       New       Radio       Library", color = Color.White, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}
