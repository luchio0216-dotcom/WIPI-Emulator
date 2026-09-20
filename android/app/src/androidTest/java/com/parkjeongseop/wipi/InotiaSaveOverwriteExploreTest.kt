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
class InotiaSaveOverwriteExploreTest {
    private val width = SCREEN_WIDTH
    private val height = SCREEN_HEIGHT

    @Test
    fun driveRealGameIntoSystemSaveAndCaptureEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        WipiNative.init(context)

        val archive = testContext.assets.open("inotia1_flat.zip").use { it.readBytes() }
        val root = File(context.filesDir, "games/inotia-save-explore").apply { deleteRecursively(); mkdirs() }
        val gameFile = File(root, "inotia1.zip").apply { writeBytes(archive) }
        val entry = GameEntry("inotia-save-explore", "Inotia save overwrite explore", null, gameFile, "inotia1.zip", File(root, "data"))
        entry.dataDir.mkdirs()

        assertTrue(WipiNative.nativeStart(gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        waitAndPump(7000); press("OK")
        waitAndPump(3500); press("OK")
        waitAndPump(8000); press("OK")
        waitAndPump(5000); press("DOWN"); press("OK")
        waitAndPump(6000); press("OK")
        waitAndPump(8000)

        // Default character on the character-selection screen.
        press("OK")
        // Advance intro/dialogue until normal gameplay is expected. Extra OK presses are
        // harmless once gameplay starts and let this survive timing differences.
        repeat(14) {
            waitAndPump(1400)
            press("OK")
        }
        val gameplay = captureAfterDelay(3000)
        saveFrame(context.cacheDir, "inotia-save-gameplay.png", gameplay)

        val saveFile = File(entry.dataDir, "db/PD005362/save0.dat/1")
        val beforeExists = saveFile.isFile
        val beforeBytes = if (beforeExists) saveFile.readBytes() else ByteArray(0)
        val beforeSha = sha256(beforeBytes)

        // Old handset UI: left soft key opens the in-game menu. Step right across tabs;
        // the user-provided screenshots show System near the right edge of the tab bar.
        press("SOFT_L")
        val menu0 = captureAfterDelay(1500)
        saveFrame(context.cacheDir, "inotia-save-menu0.png", menu0)
        repeat(5) { press("RIGHT") }
        val systemTab = captureAfterDelay(1200)
        saveFrame(context.cacheDir, "inotia-save-system-tab.png", systemTab)
        press("OK")
        val systemList = captureAfterDelay(1500)
        saveFrame(context.cacheDir, "inotia-save-system-list.png", systemList)

        // Save is the first item in the System list according to the real-device UI.
        press("OK")
        val saveResult = captureAfterDelay(4000)
        saveFrame(context.cacheDir, "inotia-save-result.png", saveResult)
        val afterError = pendingError()

        val afterExists = saveFile.isFile
        val afterBytes = if (afterExists) saveFile.readBytes() else ByteArray(0)
        val afterSha = sha256(afterBytes)
        val tree = summarizeDataTree(entry.dataDir)
        File(context.cacheDir, "inotia-save-explore.txt").writeText(
            "beforeExists=$beforeExists\n" +
                "beforeSize=${beforeBytes.size}\n" +
                "beforeSha256=$beforeSha\n" +
                "afterExists=$afterExists\n" +
                "afterSize=${afterBytes.size}\n" +
                "afterSha256=$afterSha\n" +
                "nativeError=${afterError ?: "none"}\n" +
                "gameplayToMenuDiff=${diffRatio(gameplay, menu0)}\n" +
                "menuToSystemTabDiff=${diffRatio(menu0, systemTab)}\n" +
                "systemTabToListDiff=${diffRatio(systemTab, systemList)}\n" +
                "systemListToSaveResultDiff=${diffRatio(systemList, saveResult)}\n" +
                "--- data tree ---\n$tree\n"
        )
        println("INOTIA_SAVE_EXPLORE before=$beforeExists/${beforeBytes.size}/$beforeSha after=$afterExists/${afterBytes.size}/$afterSha error=${afterError ?: "none"}")
        WipiNative.nativeStop()
    }

    private fun press(key: String) {
        WipiNative.nativeKeyDown(key)
        Thread.sleep(150)
        WipiNative.nativeKeyUp(key)
        Thread.sleep(300)
    }

    private fun waitAndPump(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        val frame = IntArray(width * height)
        while (System.currentTimeMillis() < deadline) {
            WipiNative.nativeGetFrame(frame)
            Thread.sleep(40)
        }
    }

    private fun captureAfterDelay(ms: Long): IntArray {
        waitAndPump(ms)
        val frame = IntArray(width * height)
        var saw = false
        repeat(100) {
            if (WipiNative.nativeGetFrame(frame)) saw = true
            Thread.sleep(20)
        }
        assertTrue("No emulator frame was produced", saw)
        return frame.copyOf()
    }

    private fun pendingError(): String? {
        val kind = IntArray(1)
        return WipiNative.nativeGetError(kind)?.let { "kind=${kind[0]} $it" }
    }

    private fun saveFrame(dir: File, name: String, pixels: IntArray) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun diffRatio(a: IntArray, b: IntArray): Double {
        var changed = 0L
        val count = minOf(a.size, b.size)
        for (i in 0 until count) {
            val ca = a[i]
            val cb = b[i]
            val delta = kotlin.math.abs(((ca shr 16) and 0xff) - ((cb shr 16) and 0xff)) +
                kotlin.math.abs(((ca shr 8) and 0xff) - ((cb shr 8) and 0xff)) +
                kotlin.math.abs((ca and 0xff) - (cb and 0xff))
            if (delta > 24) changed++
        }
        return if (count == 0) 0.0 else changed.toDouble() / count.toDouble()
    }

    private fun summarizeDataTree(root: File): String = root.walkTopDown()
        .filter { it.isFile }
        .map { "${it.relativeTo(root).path.replace(File.separatorChar, '/')} (${it.length()})" }
        .sorted()
        .joinToString("\n")
        .ifBlank { "<empty>" }
}
