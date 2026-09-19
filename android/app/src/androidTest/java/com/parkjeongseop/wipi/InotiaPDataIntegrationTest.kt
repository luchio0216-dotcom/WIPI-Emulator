package com.parkjeongseop.wipi

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InotiaPDataIntegrationTest {
    private val width = SCREEN_WIDTH
    private val height = SCREEN_HEIGHT

    @Test
    fun firstRunThenInjectPDataAndRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WipiNative.init(context)

        val fullZip = context.assets.open("inotia1_flat.zip").use { it.readBytes() }
        val sourceZip = File(context.cacheDir, "inotia1_full.zip").apply { writeBytes(fullZip) }

        val gameRoot = File(context.filesDir, "games/inotia-autotest").apply {
            deleteRecursively()
            mkdirs()
        }
        val gameFile = File(gameRoot, "inotia1.zip").apply { writeBytes(fullZip) }
        val entry = GameEntry(
            id = "inotia-autotest",
            name = "Inotia 1 integration test",
            cover = null,
            gameFile = gameFile,
            filename = "inotia1.zip",
            dataDir = File(gameRoot, "data"),
        )

        val importer = PDataImporter(context)
        val sourceUri = Uri.fromFile(sourceZip)
        val stage1 = importer.prepareFirstStage(entry, sourceUri).getOrThrow()
        assertTrue(stage1.removedPFiles > 0)

        // Boot the stripped package and leave the 600KB dialog alive, matching the handset procedure.
        assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        val before = captureAfterDelay(6500)
        saveFrame(context.cacheDir, "inotia-before.png", before)
        val beforeError = pendingError()

        // Inject the real P data while that first emulator session is still running.
        val stage2 = importer.importZip(entry, sourceUri).getOrThrow()
        File(context.cacheDir, "inotia-stage2.txt").writeText(
            "aid=${stage2.aid}\n" +
                "pid=${stage2.pid}\n" +
                "pFiles=${stage2.fileCount}\n" +
                "dbFiles=${stage2.databaseCount}\n" +
                "dbRecords=${stage2.databaseRecordCount}\n" +
                "bytes=${stage2.totalBytes}\n"
        )

        WipiNative.nativeStop()
        Thread.sleep(800)

        // Reboot with the same stripped package. If sideloading is correct, the prompt should disappear.
        assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        val afterRestart = captureAfterDelay(6500)
        saveFrame(context.cacheDir, "inotia-after-restart.png", afterRestart)
        val restartError = pendingError()

        val popupRegionDiff = diffRatio(before, afterRestart, 35, 55, 205, 230)
        val fullDiff = diffRatio(before, afterRestart, 0, 0, width, height)

        // Also press OK once and capture the result. A lingering download prompt normally turns into
        // the old connection-failed dialog; a successful install should advance into the game.
        WipiNative.nativeKeyDown("OK")
        Thread.sleep(120)
        WipiNative.nativeKeyUp("OK")
        val afterOk = captureAfterDelay(3000)
        saveFrame(context.cacheDir, "inotia-after-ok.png", afterOk)
        val afterOkError = pendingError()

        File(context.cacheDir, "inotia-report.txt").writeText(
            "beforeError=${beforeError ?: "none"}\n" +
                "restartError=${restartError ?: "none"}\n" +
                "afterOkError=${afterOkError ?: "none"}\n" +
                "popupRegionDiff=$popupRegionDiff\n" +
                "fullFrameDiff=$fullDiff\n"
        )

        WipiNative.nativeStop()
    }

    private fun pendingError(): String? {
        val kind = IntArray(1)
        return WipiNative.nativeGetError(kind)?.let { "kind=${kind[0]} $it" }
    }

    private fun captureAfterDelay(delayMs: Long): IntArray {
        Thread.sleep(delayMs)
        val frame = IntArray(width * height)
        var sawFrame = false
        repeat(100) {
            if (WipiNative.nativeGetFrame(frame)) sawFrame = true
            Thread.sleep(20)
        }
        assertTrue("No emulator frame was produced", sawFrame)
        return frame.copyOf()
    }

    private fun saveFrame(dir: File, name: String, pixels: IntArray) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun diffRatio(a: IntArray, b: IntArray, left: Int, top: Int, right: Int, bottom: Int): Double {
        var changed = 0L
        var total = 0L
        val l = left.coerceIn(0, width)
        val r = right.coerceIn(l, width)
        val t = top.coerceIn(0, height)
        val bot = bottom.coerceIn(t, height)
        for (y in t until bot) {
            for (x in l until r) {
                val i = y * width + x
                val ca = a[i]
                val cb = b[i]
                val ar = (ca shr 16) and 0xff
                val ag = (ca shr 8) and 0xff
                val ab = ca and 0xff
                val br = (cb shr 16) and 0xff
                val bg = (cb shr 8) and 0xff
                val bb = cb and 0xff
                val delta = kotlin.math.abs(ar - br) + kotlin.math.abs(ag - bg) + kotlin.math.abs(ab - bb)
                if (delta > 24) changed++
                total++
            }
        }
        return if (total == 0L) 0.0 else changed.toDouble() / total.toDouble()
    }
}
