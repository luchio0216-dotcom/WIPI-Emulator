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

        // First boot with P/ removed, matching the original handset sequence up
        // to the 600KB download prompt. This deliberately lets the game create
        // its own default persistent prefs record before the second-stage copy.
        assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        waitAndPump(7000)
        pressOk()
        val before = captureAfterDelay(8000)
        saveFrame(context.cacheDir, "inotia-before.png", before)
        val beforeError = pendingError()
        val treeBefore = summarizeDataTree(entry.dataDir)

        // V9 experiment: #12 proved that merely restoring the full package with
        // P/ is not enough. The reason may be that the first boot already created
        // db/<PID>/prefs/1; WIE then prefers that existing record over packaged
        // P/prefs. Restore the original full package AND explicitly overwrite the
        // persistent prefs record with the shipped P/prefs bytes. Keep the same
        // bytes visible through the WIPI filesystem as well. With packaged P/
        // present, a CREATE-mode DB open should no longer wipe this replacement.
        val shippedPrefs = findPFile(fullZip, "prefs") ?: error("P/prefs not found")
        assertTrue("Expected 64-byte shipped prefs", shippedPrefs.size == 64)
        entry.gameFile.writeBytes(fullZip)

        val persistentPrefs = File(entry.dataDir, "db/${stage1.pid}/prefs/1")
        val oldPrefs = persistentPrefs.takeIf { it.isFile }?.readBytes()
        persistentPrefs.parentFile?.mkdirs()
        persistentPrefs.writeBytes(shippedPrefs)

        val fsPrefs = File(entry.dataDir, "fs/${stage1.aid}/prefs")
        fsPrefs.parentFile?.mkdirs()
        fsPrefs.writeBytes(shippedPrefs)

        val treeAfterRestore = summarizeDataTree(entry.dataDir)
        File(context.cacheDir, "inotia-stage2.txt").writeText(
            "aid=${stage1.aid}\n" +
                "pid=${stage1.pid}\n" +
                "mode=restore-full-package-plus-shipped-prefs-overwrite\n" +
                "restoredPackageBytes=${fullZip.size}\n" +
                "shippedPrefsBytes=${shippedPrefs.size}\n" +
                "oldPrefsBytes=${oldPrefs?.size ?: -1}\n" +
                "oldPrefsEqualShipped=${oldPrefs?.contentEquals(shippedPrefs) ?: false}\n" +
                "--- data tree before restore ---\n$treeBefore\n" +
                "--- data tree after package+prefs restore ---\n$treeAfterRestore\n"
        )

        WipiNative.nativeStop()
        Thread.sleep(1000)

        assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        waitAndPump(7000)
        pressOk()
        val afterRestart = captureAfterDelay(8000)
        saveFrame(context.cacheDir, "inotia-after-restart.png", afterRestart)
        val restartError = pendingError()
        val treeAfterRestart = summarizeDataTree(entry.dataDir)
        val prefsAfterRestart = persistentPrefs.takeIf { it.isFile }?.readBytes()

        val popupRegionDiff = diffRatio(before, afterRestart, 35, 55, 205, 230)
        val fullDiff = diffRatio(before, afterRestart, 0, 0, width, height)

        pressOk()
        val afterOk = captureAfterDelay(5000)
        saveFrame(context.cacheDir, "inotia-after-ok.png", afterOk)
        val afterOkError = pendingError()

        File(context.cacheDir, "inotia-report.txt").writeText(
            "beforeError=${beforeError ?: "none"}\n" +
                "restartError=${restartError ?: "none"}\n" +
                "afterOkError=${afterOkError ?: "none"}\n" +
                "popupRegionDiff=$popupRegionDiff\n" +
                "fullFrameDiff=$fullDiff\n" +
                "prefsAfterRestartBytes=${prefsAfterRestart?.size ?: -1}\n" +
                "prefsAfterRestartEqualShipped=${prefsAfterRestart?.contentEquals(shippedPrefs) ?: false}\n" +
                "--- data tree after restart ---\n$treeAfterRestart\n"
        )

        WipiNative.nativeStop()
    }

    private fun findPFile(zipBytes: ByteArray, wantedName: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: return null
                if (!item.isDirectory) {
                    val name = item.name.replace('\\', '/').trimStart('/')
                    val relative = when {
                        name.startsWith("P/") -> name.removePrefix("P/")
                        name.startsWith("p/") -> name.removePrefix("p/")
                        name.contains("/P/") -> name.substringAfter("/P/")
                        name.contains("/p/") -> name.substringAfter("/p/")
                        else -> null
                    }
                    if (relative != null && relative.equals(wantedName, ignoreCase = true)) {
                        return zip.readBytes()
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun summarizeDataTree(root: File): String {
        if (!root.exists()) return "<missing>"
        return root.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                "$rel (${file.length()})"
            }
            .sorted()
            .joinToString("\n")
            .ifBlank { "<empty>" }
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
