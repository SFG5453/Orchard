package dev.sfg.orchard.mobile.ui.glass

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real RenderNodes and AGSL on the GPU, including backdrop dependency invalidation. */
@RunWith(AndroidJUnit4::class)
class GlassRenderingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun launch(glass: Boolean, animate: Boolean): ActivityScenario<android.app.Activity> {
        val context = instrumentation.targetContext
        return ActivityScenario.launch(Intent().setClassName(context.packageName,
            "dev.sfg.orchard.mobile.ui.glass.GlassBenchmarkActivity")
            .putExtra("glass", glass).putExtra("animate", animate))
    }

    private fun screenshot(): Bitmap {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        return checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    }

    private fun difference(a: Bitmap, b: Bitmap, bottom: Boolean = false): Int {
        var changed = 0
        val top = if (bottom) a.height * 84 / 100 else a.height / 8
        val end = if (bottom) a.height * 95 / 100 else a.height * 3 / 4
        for (y in top until end step 8) for (x in a.width / 8 until a.width * 7 / 8 step 8) {
            if (a.getPixel(x, y) != b.getPixel(x, y)) changed++
        }
        return changed
    }

    @Test fun animatedBackdropUpdatesBothCardsAndChrome() {
        launch(true, true).use {
            val first = screenshot()
            val second = screenshot()
            assertTrue("Card backdrop stayed frozen", difference(first, second) > 100)
            assertTrue("Chrome backdrop stayed frozen", difference(first, second, true) > 100)
            first.recycle()
            second.recycle()
        }
    }

    @Test fun disablingGlassChangesRenderedPanes() {
        val enabled = launch(true, false).use { screenshot() }
        val disabled = launch(false, false).use { screenshot() }
        assertTrue("Glass did not render", difference(enabled, disabled) > 100)
        assertTrue("Chrome did not render", difference(enabled, disabled, true) > 100)
        enabled.recycle()
        disabled.recycle()
    }
}
