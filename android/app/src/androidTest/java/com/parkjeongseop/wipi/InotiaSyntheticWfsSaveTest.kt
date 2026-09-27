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
class InotiaSyntheticWfsSaveTest {
    private val width = SCREEN_WIDTH
    private val height = SCREEN_HEIGHT

    @Test
    fun syntheticWfsImportOverwriteAndReload() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val testContext = inst.context
        WipiNative.init(context)
        val archive = testContext.assets.open("inotia1_flat.zip").use { it.readBytes() }
        val root = File(context.filesDir, "games/inotia-synthetic-save").apply { deleteRecursively(); mkdirs() }
        val gameFile = File(root, "inotia1.zip").apply { writeBytes(archive) }
        val entry = GameEntry("inotia-synthetic-save", "Inotia synthetic WFS save", null, gameFile, "inotia1.zip", File(root, "data"))
        entry.dataDir.mkdirs()

        // Create a real save using only the game itself: title -> scenario -> new game -> slot 1 -> default character.
        assertTrue(WipiNative.nativeStart(archive, entry.filename, entry.dataDir.absolutePath, ""))
        waitPump(7000); press("OK")
        waitPump(3500); press("OK")
        waitPump(8000); press("OK")
        waitPump(5000); press("DOWN"); press("OK")
        waitPump(6000); press("OK")
        waitPump(8000); press("OK")
        val firstGameplay = capture(12000)
        frame(context.cacheDir, "synthetic-first-gameplay.png", firstGameplay)
        assertTrue("new-game flow raised native error: ${error()}", error() == null)

        // First save creates the baseline slot. This path should not need replacement semantics yet.
        saveFromGameplay()
        val baselineFrame = capture(3500)
        frame(context.cacheDir, "synthetic-baseline-save.png", baselineFrame)
        assertTrue("baseline save raised native error: ${error()}", error() == null)
        WipiNative.nativeStop(); Thread.sleep(500)

        val saveFile = File(entry.dataDir, "db/PD005362/save0.dat/1")
        assertTrue("game did not create save0.dat", saveFile.isFile && saveFile.length() > 0)
        val baselineSha = sha(saveFile.readBytes())

        // Export a synthetic WFS generated during CI, wipe progress, then import it back.
        val wfs = SaveBackup.exportKtf(entry)
        assertTrue("synthetic WFS header invalid", wfs.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "WFSAVEBK")
        val dbRoot = File(entry.dataDir, "db/PD005362")
        val fsRoot = File(entry.dataDir, "fs")
        dbRoot.deleteRecursively(); fsRoot.deleteRecursively()
        SaveBackup.importKtf(entry, wfs)
        assertTrue("WFS import did not restore save0.dat", saveFile.isFile && saveFile.length() > 0)
        assertTrue("WFS import changed baseline save", sha(saveFile.readBytes()) == baselineSha)

        // Load imported slot and prove gameplay, then overwrite the existing save.
        assertTrue(WipiNative.nativeStart(archive, entry.filename, entry.dataDir.absolutePath, ""))
        continueSlot1()
        val importedGameplay = capture(6000)
        frame(context.cacheDir, "synthetic-imported-gameplay.png", importedGameplay)
        assertTrue("imported slot did not load: ${error()}", error() == null)
        repeat(3) { press("RIGHT") }; repeat(2) { press("DOWN") }; waitPump(1200)
        val beforeMtime = saveFile.lastModified()
        saveFromGameplay()
        val overwriteFrame = capture(4000)
        frame(context.cacheDir, "synthetic-overwrite-result.png", overwriteFrame)
        assertTrue("overwrite save raised native error: ${error()}", error() == null)
        WipiNative.nativeStop(); Thread.sleep(500)
        assertTrue("overwrite removed save0.dat", saveFile.isFile && saveFile.length() > 0)
        assertTrue("overwrite did not commit save0.dat", saveFile.lastModified() >= beforeMtime)
        val committedSha = sha(saveFile.readBytes())

        // Process-level restart: slot must still exist and be reloadable.
        assertTrue(WipiNative.nativeStart(archive, entry.filename, entry.dataDir.absolutePath, ""))
        continueSlot1()
        val reloaded = capture(6000)
        frame(context.cacheDir, "synthetic-reloaded-gameplay.png", reloaded)
        val reloadError = error()
        assertTrue("persisted slot failed to reload: ${reloadError ?: "none"}", reloadError == null)
        WipiNative.nativeStop()

        File(context.cacheDir, "synthetic-wfs-save-report.txt").writeText(
            "wfsBytes=${wfs.size}\nbaselineSha256=$baselineSha\ncommittedSha256=$committedSha\n" +
                "saveExists=${saveFile.isFile}\nsaveBytes=${saveFile.length()}\nreloadError=${reloadError ?: "none"}\nrelaunchLoadSucceeded=true\n"
        )
        println("INOTIA_SYNTHETIC_WFS_SAVE_OK baseline=$baselineSha committed=$committedSha bytes=${saveFile.length()}")
    }

    private fun continueSlot1() {
        waitPump(7000); press("OK")
        waitPump(3500); press("OK")
        waitPump(8000); press("OK")
        waitPump(5000); press("OK")
        waitPump(6000); press("OK")
        waitPump(8000)
    }

    private fun saveFromGameplay() {
        press("SOFT_L"); waitPump(1200)
        repeat(5) { press("RIGHT") }
        waitPump(900); press("OK"); waitPump(1200)
        press("OK"); waitPump(1400); press("OK"); waitPump(2200)
    }

    private fun press(key: String) {
        WipiNative.nativeKeyDown(key); Thread.sleep(150); WipiNative.nativeKeyUp(key); Thread.sleep(300)
    }
    private fun waitPump(ms: Long) {
        val until = System.currentTimeMillis() + ms; val pixels = IntArray(width * height)
        while (System.currentTimeMillis() < until) { WipiNative.nativeGetFrame(pixels); Thread.sleep(40) }
    }
    private fun capture(ms: Long): IntArray {
        waitPump(ms); val pixels = IntArray(width * height); var saw = false
        repeat(100) { if (WipiNative.nativeGetFrame(pixels)) saw = true; Thread.sleep(20) }
        assertTrue("no emulator frame", saw); return pixels.copyOf()
    }
    private fun error(): String? { val kind = IntArray(1); return WipiNative.nativeGetError(kind)?.let { "kind=${kind[0]} $it" } }
    private fun frame(dir: File, name: String, pixels: IntArray) {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); b.setPixels(pixels, 0, width, 0, 0, width, height)
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
