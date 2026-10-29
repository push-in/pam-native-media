package dev.pam.media

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreviewModeTest {
    @Test fun roundedAncestorUsesCompatibleAndSquarePreviewKeepsPerformance() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val factory = CameraViewFactory(context)
            val view = factory.create(context) {} as FrameLayout
            val parent = FrameLayout(context)
            parent.addView(view)
            try {
                view.layout(0, 0, 320, 480)
                val preview = view.getChildAt(0) as PreviewView
                assertEquals(PreviewView.ImplementationMode.PERFORMANCE, preview.implementationMode)
                // PAM uses cornerRadii (a path outline), often inside a layer drawable.
                parent.background = LayerDrawable(arrayOf(GradientDrawable().apply {
                    cornerRadii = FloatArray(8) { 18f }
                }))
                view.layout(0, 0, 321, 480)
                assertEquals(PreviewView.ImplementationMode.COMPATIBLE, preview.implementationMode)
                parent.background = null
                view.layout(0, 0, 320, 480)
                assertEquals(PreviewView.ImplementationMode.PERFORMANCE, preview.implementationMode)
            } finally { factory.release(view) }
        }
    }
}
