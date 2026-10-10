package com.shilapi.xcertplay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", shadows = [FileProviderPathTestShadow::class])
class DiagnosticExportUiTest {
    @Test fun missingPickerSavesAReportAndProvidesSelectableTextInsideDiPlay() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, DiagnosticReportProvider().apply { attachInfo(context, info) })
        val missingPicker = object : ActivityResultLauncher<String>() {
            override fun launch(input: String, options: ActivityOptionsCompat?) {
                throw ActivityNotFoundException("No DocumentsUI")
            }
            override fun unregister() = Unit
            override fun getContract() = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
        }
        ReflectionHelpers.setField(activity, "export", missingPicker)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "chooseReportDestination")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ShadowAlertDialog.getLatestAlertDialog() == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val saved = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val reports = File(context.getExternalFilesDir(null)!!, "diagnostic-reports")
            val file = reports.listFiles()!!.single()
            assertTrue(file.name.endsWith(".txt"))
            assertTrue(descendants(saved.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.contains(file.absolutePath) })
            assertTrue(file.readText().contains("Android 9 / API 28"))
            saved.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val viewer = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(descendants(viewer.window!!.decorView).filterIsInstance<TextView>()
                .any { it.isTextSelectable && it.text.contains("Android 9 / API 28") })
            viewer.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /**
     * The header is what places a report: which build, which car, which settings. It is also the only
     * place the reason authentication failed can appear — "authentication unavailable" on its own cannot
     * tell a missing asset from a rejected certificate, and the exception itself went to logcat, which a
     * head unit with no adb cannot show anyone.
     */
    @Test fun theHeaderNamesTheReasonAuthenticationFailed() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        ReflectionHelpers.setField(activity, "setupErrorDetail", "IdentityMissing: no offline-mfi assets")
        val header = ReflectionHelpers.callInstanceMethod<String>(
            activity, "buildDiagnosticHeader",
            ReflectionHelpers.ClassParameter.from(Context::class.java, activity.applicationContext),
        )
        assertTrue("the reason must be in the header, not only the symptom", header.contains("IdentityMissing"))
        assertTrue(header.contains("CarPlay setup: authentication unavailable — IdentityMissing: no offline-mfi assets"))
    }

    @Test fun aSetupThatDidNotFailIsReportedAsReady() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        ReflectionHelpers.setField(activity, "setupErrorDetail", null)
        val header = ReflectionHelpers.callInstanceMethod<String>(
            activity, "buildDiagnosticHeader",
            ReflectionHelpers.ClassParameter.from(Context::class.java, activity.applicationContext),
        )
        assertTrue(header.contains("CarPlay setup: ready"))
        assertFalse(header.contains("authentication unavailable"))
    }

    /**
     * The failure this exercises is real: the offline-mfi assets are deliberately not in the tree, so the
     * bootstrap fails here exactly as it does on a build without them.
     */
    @Test fun aFailedBootstrapKeepsTheExceptionForTheHeader() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        val detail = ReflectionHelpers.getField<String>(activity, "setupErrorDetail")
        assertNotNull("setupError alone cannot say which failure it was", detail)
        assertTrue("the detail must name a class and a message", detail!!.contains(":"))
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
