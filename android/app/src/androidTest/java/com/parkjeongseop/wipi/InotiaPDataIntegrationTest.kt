package com.parkjeongseop.wipi

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class InotiaPDataIntegrationTest {
    private val width = SCREEN_WIDTH
    private val height = SCREEN_HEIGHT

    @Test
    fun firstRunThenInjectPDataAndRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        WipiNative.init(context)

        val fullZip = testContext.assets.open("inotia1_flat.zip").use { it.readBytes() }
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

        // The Com2uS splash remains visible for several seconds on the virtual device.
        // Advance once after the splash and then wait long enough for the legacy 600KB
        // prompt to appear before taking the baseline frame.
        assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        waitAndPump(7000)
        pressOk()
        val before = captureAfterDelay(8000)
        saveFrame(context.cacheDir, "inotia-before.png", before)
        val beforeError = pendingError()

        // First run the V4 importer so the raw P files are copied into the persistent
        // WIPI filesystem. Then deliberately replace V4's 1,143-record expansion with
        // KTF C's verified single-backing-record model: each packaged P/<name>.dat blob
        // is stored whole as db/<PID>/<name>.dat/1. The pinned WIE KTF runtime reads
        // record 1 as a seekable byte stream and also treats P/<name> filesystem files
        // as packaged databases.
        val stage2 = importer.importZip(entry, sourceUri).getOrThrow()
        val singleStreamDbFiles = rewriteDatabasesAsSingleStreams(entry, fullZip, stage2.pid)
        File(context.cacheDir, "inotia-stage2.txt").writeText(
            "aid=${stage2.aid}\n" +
                "pid=${stage2.pid}\n" +
                "pFiles=${stage2.fileCount}\n" +
                "v4DbFiles=${stage2.databaseFileCount}\n" +
                "v4DbRecords=${stage2.databaseRecordCount}\n" +
                "singleStreamDbFiles=$singleStreamDbFiles\n" +
                "bytes=${stage2.totalBytes}\n"
        )
        assertTrue("Expected the five Inotia .dat databases", singleStreamDbFiles == 5)

        WipiNative.nativeStop()
        Thread.sleep(1000)

        // Repeat the exact same startup sequence after sideloading. If the KTF single-
        // stream representation is correct, this frame should no longer contain the
        // 600KB prompt.
        assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        waitAndPump(7000)
        pressOk()
        val afterRestart = captureAfterDelay(8000)
        saveFrame(context.cacheDir, "inotia-after-restart.png", afterRestart)
        val restartError = pendingError()

        val popupRegionDiff = diffRatio(before, afterRestart, 35, 55, 205, 230)
        val fullDiff = diffRatio(before, afterRestart, 0, 0, width, height)

        // One more OK distinguishes a surviving download prompt (network attempt / error)
        // from a successful install (advance from title into the game/menu).
        pressOk()
        val afterOk = captureAfterDelay(5000)
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

    /**
     * Experimental V5 representation based on the pinned KTF C runtime semantics.
     * Only the five .dat files are database streams; prefs stays a plain filesystem
     * file exactly as shipped in P/.
     */
    private fun rewriteDatabasesAsSingleStreams(entry: GameEntry, fullZip: ByteArray, pid: String): Int {
        val dbRoot = File(entry.dataDir, "db/$pid").apply { mkdirs() }
        var count = 0

        ZipInputStream(ByteArrayInputStream(fullZip)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                if (!item.isDirectory) {
                    val name = item.name.replace('\\', '/').trimStart('/')
                    val marker = when {
                        name.startsWith("P/") -> "P/"
                        name.startsWith("p/") -> "p/"
                        name.contains("/P/") -> "/P/"
                        name.contains("/p/") -> "/p/"
                        else -> null
                    }
                    if (marker != null) {
                        val relative = if (name.startsWith(marker)) {
                            name.removePrefix(marker)
                        } else {
                            name.substringAfter(marker)
                        }
                        if (relative.endsWith(".dat", ignoreCase = true) && !relative.contains("..")) {
                            val blob = zip.readBytes()
                            val dbDir = File(dbRoot, relative).canonicalFile
                            dbDir.deleteRecursively()
                            check(dbDir.mkdirs() || dbDir.isDirectory)
                            File(dbDir, "1").writeBytes(blob)
                            count++
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return count
    }

    private fun pressOk() {
        WipiNative.nativeKeyDown("OK")
        Thread.sleep(150)
        WipiNative.nativeKeyUp("OK")
    }

    private fun waitAndPump(delayMs: Long) {
        val deadline = System.currentTimeMillis() + delayMs
        val frame = IntArray(width * height)
        while (System.currentTimeMillis() < deadline) {
            WipiNative.nativeGetFrame(frame)
            Thread.sleep(40)
        }
    }

    private fun pendingError(): String? {
        val kind = IntArray(1)
        return WipiNative.nativeGetError(kind)?.let { "kind=${kind[0]} $it" }
    }

    private fun captureAfterDelay(delayMs: Long): IntArray {
        waitAndPump(delayMs)
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
