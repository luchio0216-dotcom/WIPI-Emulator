package com.parkjeongseop.wipi

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class Inotia2StartupTest {
    private val width = SCREEN_WIDTH
    private val height = SCREEN_HEIGHT

    @Test
    fun originalArchiveGetsProvisionedCertificateAndRunsPastStartupGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        WipiNative.init(context)

        val original = testContext.assets.open("inotia2_original.zip").use { it.readBytes() }
        assertEquals("974e0df9ab1e51751efdef21a0fe324ad79917f41cd049fcfaf7c46c14f9e324", sha256(original))
        val fixture = File(context.cacheDir, "inotia2-original.zip").apply { writeBytes(original) }
        val entry = GameLibrary(context).importGame(Uri.fromFile(fixture))
        assertNotNull("GameLibrary failed to import the original Inotia 2 archive", entry)
        entry!!

        val rawCert = File(entry.dataDir, "fs/010100D5/cert.c2s")
        assertTrue("P/cert.c2s was not installed into the guest filesystem", rawCert.isFile)
        assertEquals(23L, rawCert.length())
        val started = WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, "")

        val frames = mutableListOf<IntArray>()
        val frameSeen = mutableListOf<Boolean>()
        val errors = mutableListOf<String>()
        repeat(6) { index ->
            if (index > 0) pressOk()
            val capture = captureAfterDelay(if (index == 0) 6000 else 5000, errors)
            frames += capture.first
            frameSeen += capture.second
            if (capture.second) saveFrame(context.cacheDir, "inotia2-frame-$index.png", capture.first)
        }

        val provisioned = File(entry.dataDir, "db/PD007974/cert.c2s/1")
        val provisionedHex = if (provisioned.isFile) provisioned.readBytes().joinToString("") { "%02x".format(it) } else "<missing>"
        val diffs = frames.zipWithNext().map { (a, b) -> diffRatio(a, b) }
        val report = buildString {
            appendLine("archiveSha256=${sha256(original)}")
            appendLine("aid=010100D5")
            appendLine("pid=PD007974")
            appendLine("nativeStart=$started")
            appendLine("rawCert=${rawCert.readBytes().joinToString("") { "%02x".format(it) }}")
            appendLine("provisionedCert=$provisionedHex")
            appendLine("frameSeen=$frameSeen")
            appendLine("errors=${errors.ifEmpty { listOf("none") }}")
            appendLine("frameDiffs=$diffs")
            appendLine("dataTree:")
            appendLine(summarizeDataTree(entry.dataDir))
        }
        File(context.cacheDir, "inotia2-report.txt").writeText(report)
        println("INOTIA2_REPORT_BEGIN")
        print(report)
        println("INOTIA2_REPORT_END")

        WipiNative.nativeStop()
        assertTrue("nativeStart rejected the original Inotia 2 package", started)
        assertTrue("No Inotia 2 emulator frame was produced; see inotia2-report.txt and logcat", frameSeen.any { it })
        assertTrue("Inotia 2 never opened/provisioned cert.c2s", provisioned.isFile)
        assertEquals("55c708598d5ee163adffb344997311e445d64728dadbac", provisionedHex)
    }

    private fun pressOk() {
        WipiNative.nativeKeyDown("OK"); Thread.sleep(150); WipiNative.nativeKeyUp("OK"); Thread.sleep(350)
    }

    private fun captureAfterDelay(delayMs: Long, errors: MutableList<String>): Pair<IntArray, Boolean> {
        val deadline = System.currentTimeMillis() + delayMs
        val frame = IntArray(width * height)
        var sawFrame = false
        while (System.currentTimeMillis() < deadline) {
            if (WipiNative.nativeGetFrame(frame)) sawFrame = true
            val kind = IntArray(1)
            WipiNative.nativeGetError(kind)?.let { error ->
                val text = "kind=${kind[0]} $error"
                if (errors.lastOrNull() != text) errors += text
            }
            Thread.sleep(40)
        }
        repeat(100) { if (WipiNative.nativeGetFrame(frame)) sawFrame = true; Thread.sleep(20) }
        return frame.copyOf() to sawFrame
    }

    private fun saveFrame(dir: File, name: String, pixels: IntArray) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun diffRatio(a: IntArray, b: IntArray): Double {
        var changed = 0L
        val count = minOf(a.size, b.size)
        for (i in 0 until count) {
            val ca = a[i]; val cb = b[i]
            val delta = kotlin.math.abs(((ca shr 16) and 0xff) - ((cb shr 16) and 0xff)) + kotlin.math.abs(((ca shr 8) and 0xff) - ((cb shr 8) and 0xff)) + kotlin.math.abs((ca and 0xff) - (cb and 0xff))
            if (delta > 24) changed++
        }
        return if (count == 0) 0.0 else changed.toDouble() / count.toDouble()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun summarizeDataTree(root: File): String = root.walkTopDown().filter { it.isFile }.map { file -> "${file.relativeTo(root).path.replace(File.separatorChar, '/')} (${file.length()})" }.sorted().joinToString("\n").ifBlank { "<empty>" }
}
