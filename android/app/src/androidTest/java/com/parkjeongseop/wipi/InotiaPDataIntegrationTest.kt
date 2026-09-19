package com.parkjeongseop.wipi

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class InotiaPDataIntegrationTest {
    private val width = SCREEN_WIDTH
    private val height = SCREEN_HEIGHT

    @Test
    fun directLaunchWithInotiaKtfCompatibilityShim() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        WipiNative.init(context)

        // Use the complete archive including the shipped P/ data.  W-Feature's
        // compatibility notes for this exact KTF title show that the obsolete
        // download branch is controlled by subscriber-number length, not by a
        // need to fetch the already-packaged 600 KB again.
        val fullZip = testContext.assets.open("inotia1_flat.zip").use { it.readBytes() }
        val gameRoot = File(context.filesDir, "games/inotia-min-autotest").apply {
            deleteRecursively()
            mkdirs()
        }
        val gameFile = File(gameRoot, "inotia1.zip").apply { writeBytes(fullZip) }
        val entry = GameEntry(
            id = "inotia-min-autotest",
            name = "Inotia 1 KTF MIN bypass test",
            cover = null,
            gameFile = gameFile,
            filename = "inotia1.zip",
            dataDir = File(gameRoot, "data"),
        )
        entry.dataDir.deleteRecursively()
        entry.dataDir.mkdirs()

        assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))

        // Splash -> title.
        waitAndPump(7000)
        pressOk()
        val titleFrame = captureAfterDelay(3500)
        val titleError = pendingError()
        saveFrame(context.cacheDir, "inotia-before.png", titleFrame)

        // Title -> legacy KTF data/billing check.  With AID 010100D3 the patched
        // WIE runtime returns MIN=9999, which should skip the retired 600 KB
        // network/receipt branch and enter the real game menu instead.
        pressOk()
        val menuFrame = captureAfterDelay(8000)
        val menuError = pendingError()
        saveFrame(context.cacheDir, "inotia-after-restart.png", menuFrame)

        // One more OK proves that the result is interactive game state rather
        // than a cosmetically hidden prompt.
        pressOk()
        val advancedFrame = captureAfterDelay(5000)
        val advancedError = pendingError()
        saveFrame(context.cacheDir, "inotia-after-ok.png", advancedFrame)

        val titleToMenuDiff = diffRatio(titleFrame, menuFrame)
        val menuToAdvancedDiff = diffRatio(menuFrame, advancedFrame)
        val tree = summarizeDataTree(entry.dataDir)
        val digest = MessageDigest.getInstance("SHA-256").digest(fullZip).joinToString("") { "%02x".format(it) }

        File(context.cacheDir, "inotia-stage2.txt").writeText(
            "aid=010100D3\n" +
                "pid=PD005362\n" +
                "mode=complete-package+short-MIN-compatibility-shim\n" +
                "minForInotia=9999\n" +
                "packageSha256=$digest\n" +
                "packageBytes=${fullZip.size}\n" +
                "--- final data tree ---\n$tree\n"
        )
        File(context.cacheDir, "inotia-report.txt").writeText(
            "titleError=${titleError ?: "none"}\n" +
                "menuError=${menuError ?: "none"}\n" +
                "advancedError=${advancedError ?: "none"}\n" +
                "titleToMenuDiff=$titleToMenuDiff\n" +
                "menuToAdvancedDiff=$menuToAdvancedDiff\n"
        )

        WipiNative.nativeStop()
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

    private fun diffRatio(a: IntArray, b: IntArray): Double {
        var changed = 0L
        val count = minOf(a.size, b.size)
        for (i in 0 until count) {
            val ca = a[i]
            val cb = b[i]
            val delta =
                kotlin.math.abs(((ca shr 16) and 0xff) - ((cb shr 16) and 0xff)) +
                    kotlin.math.abs(((ca shr 8) and 0xff) - ((cb shr 8) and 0xff)) +
                    kotlin.math.abs((ca and 0xff) - (cb and 0xff))
            if (delta > 24) changed++
        }
        return if (count == 0) 0.0 else changed.toDouble() / count.toDouble()
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
}
