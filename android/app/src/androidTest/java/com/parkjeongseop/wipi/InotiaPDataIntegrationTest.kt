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

        // Reproduce the legacy first boot with P/ absent and stop at the 600KB prompt.
        assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        waitAndPump(7000)
        pressOk()
        val before = captureAfterDelay(8000)
        saveFrame(context.cacheDir, "inotia-before.png", before)
        val beforeError = pendingError()
        val treeBefore = summarizeDataTree(entry.dataDir)

        // First attempt in this run: restore the original package and overwrite the
        // first-boot prefs record with the shipped P/prefs bytes (#13 semantics).
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

        WipiNative.nativeStop()
        Thread.sleep(1000)

        assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        waitAndPump(7000)
        pressOk()
        val afterRestart = captureAfterDelay(8000)
        saveFrame(context.cacheDir, "inotia-after-restart.png", afterRestart)
        val restartError = pendingError()
        val popupRegionDiff = diffRatio(before, afterRestart, 35, 55, 205, 230)
        val fullDiff = diffRatio(before, afterRestart, 0, 0, width, height)

        // Automatic chained fallback: if the first attempt is still pixel-identical
        // to the 600KB prompt, immediately try the next distinct hypothesis in the
        // SAME virtual-device run instead of waiting for another scheduled check.
        // The old handset instructions literally copied the P directory back. Our
        // prior importer wrote P contents at fs/<AID>/<name>, which would be wrong
        // if the Clet opens "P/<name>" through the WIPI file API. Mirror every P
        // payload to both fs/<AID>/P/<name> and fs/<AID>/<name>, then restart again.
        var chainedFallback = false
        var prefixedPFiles = 0
        var prefixedPBytes = 0L
        var finalFrame = afterRestart
        var finalError = restartError

        if (popupRegionDiff <= 0.01 && fullDiff <= 0.01) {
            chainedFallback = true
            WipiNative.nativeStop()
            Thread.sleep(500)

            val copied = copyAllPToPersistentFs(entry, fullZip, stage1.aid)
            prefixedPFiles = copied.files
            prefixedPBytes = copied.bytes
            assertTrue("Expected all six P payloads", prefixedPFiles == 6)

            assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
            waitAndPump(7000)
            pressOk()
            finalFrame = captureAfterDelay(8000)
            finalError = pendingError()
        }

        saveFrame(context.cacheDir, "inotia-after-ok.png", finalFrame)
        val finalPopupDiff = diffRatio(before, finalFrame, 35, 55, 205, 230)
        val finalFullDiff = diffRatio(before, finalFrame, 0, 0, width, height)
        val treeFinal = summarizeDataTree(entry.dataDir)
        val prefsFinal = persistentPrefs.takeIf { it.isFile }?.readBytes()

        File(context.cacheDir, "inotia-stage2.txt").writeText(
            "aid=${stage1.aid}\n" +
                "pid=${stage1.pid}\n" +
                "mode=package+shipped-prefs-then-auto-P-prefix-fallback\n" +
                "restoredPackageBytes=${fullZip.size}\n" +
                "shippedPrefsBytes=${shippedPrefs.size}\n" +
                "oldPrefsBytes=${oldPrefs?.size ?: -1}\n" +
                "oldPrefsEqualShipped=${oldPrefs?.contentEquals(shippedPrefs) ?: false}\n" +
                "chainedFallback=$chainedFallback\n" +
                "prefixedPFiles=$prefixedPFiles\n" +
                "prefixedPBytes=$prefixedPBytes\n" +
                "--- data tree before restore ---\n$treeBefore\n" +
                "--- final data tree ---\n$treeFinal\n"
        )

        File(context.cacheDir, "inotia-report.txt").writeText(
            "beforeError=${beforeError ?: "none"}\n" +
                "restartError=${restartError ?: "none"}\n" +
                "finalError=${finalError ?: "none"}\n" +
                "firstPopupRegionDiff=$popupRegionDiff\n" +
                "firstFullFrameDiff=$fullDiff\n" +
                "finalPopupRegionDiff=$finalPopupDiff\n" +
                "finalFullFrameDiff=$finalFullDiff\n" +
                "prefsFinalBytes=${prefsFinal?.size ?: -1}\n" +
                "prefsFinalEqualShipped=${prefsFinal?.contentEquals(shippedPrefs) ?: false}\n"
        )

        WipiNative.nativeStop()
    }

    private data class CopyResult(val files: Int, val bytes: Long)

    private fun copyAllPToPersistentFs(entry: GameEntry, zipBytes: ByteArray, aid: String): CopyResult {
        val fsRoot = File(entry.dataDir, "fs/$aid").canonicalFile.apply { mkdirs() }
        val pRoot = File(fsRoot, "P").canonicalFile.apply { mkdirs() }
        var files = 0
        var bytes = 0L

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                if (!item.isDirectory) {
                    val name = item.name.replace('\\', '/').trimStart('/')
                    val relative = pRelativePath(name)
                    if (relative != null && isSafeRelativePath(relative)) {
                        val blob = zip.readBytes()
                        writeSafe(fsRoot, relative, blob)
                        writeSafe(pRoot, relative, blob)
                        files++
                        bytes += blob.size
                    }
                }
                zip.closeEntry()
            }
        }
        return CopyResult(files, bytes)
    }

    private fun writeSafe(root: File, relative: String, data: ByteArray) {
        val file = File(root, relative).canonicalFile
        val prefix = root.path + File.separator
        require(file.path.startsWith(prefix))
        file.parentFile?.mkdirs()
        file.writeBytes(data)
    }

    private fun findPFile(zipBytes: ByteArray, wantedName: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: return null
                if (!item.isDirectory) {
                    val name = item.name.replace('\\', '/').trimStart('/')
                    val relative = pRelativePath(name)
                    if (relative != null && relative.equals(wantedName, ignoreCase = true)) {
                        return zip.readBytes()
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun pRelativePath(name: String): String? = when {
        name.startsWith("P/") -> name.removePrefix("P/")
        name.startsWith("p/") -> name.removePrefix("p/")
        name.contains("/P/") -> name.substringAfter("/P/")
        name.contains("/p/") -> name.substringAfter("/p/")
        else -> null
    }

    private fun isSafeRelativePath(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
        return parts.isNotEmpty() && parts.none { it.isBlank() || it == "." || it == ".." }
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
