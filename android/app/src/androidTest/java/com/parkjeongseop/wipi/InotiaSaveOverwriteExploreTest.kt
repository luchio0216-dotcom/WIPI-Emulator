package com.parkjeongseop.wipi

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class InotiaSaveOverwriteExploreTest {
    private val width = SCREEN_WIDTH
    private val height = SCREEN_HEIGHT
    private val gameId = "inotia-user-wfs-save"

    @Test
    fun phase1ImportUserWfsLoadSlot1AndOverwrite() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        WipiNative.init(context)

        val archive = testContext.assets.open("inotia1_flat.zip").use { it.readBytes() }
        val wfsText = testContext.assets.open("inotia1_user_save.wfs.b64").bufferedReader().use { it.readText() }
        val wfs = Base64.decode(wfsText.trim(), Base64.DEFAULT)
        val logical = SaveBackup.decodeForTest(wfs, archive).associate { it.key to it.data }
        val expectedSave = logical["db/save0.dat"] ?: throw AssertionError("User WFS does not contain db/save0.dat")
        assertTrue("User WFS save0.dat is empty", expectedSave.isNotEmpty())

        val root = File(context.filesDir, "games/$gameId").apply { deleteRecursively(); mkdirs() }
        val gameFile = File(root, "inotia1.zip").apply { writeBytes(archive) }
        val entry = GameEntry(gameId, "Inotia user WFS persistence test", null, gameFile, "inotia1.zip", File(root, "data"))
        entry.dataDir.mkdirs()

        val importResult = SaveBackup.importKtf(entry, wfs)
        val saveFile = File(entry.dataDir, "db/PD005362/save0.dat/1")
        assertTrue("WFS import did not materialize slot 1 save0.dat", saveFile.isFile)
        assertArrayEquals("Imported slot 1 bytes do not match the user WFS", expectedSave, saveFile.readBytes())

        val importedBytes = saveFile.readBytes()
        val importedSha = sha256(importedBytes)
        val importedMtime = saveFile.lastModified()

        assertTrue(WipiNative.nativeStart(gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        navigateToContinueSlot1()
        val loadedFrame = captureAfterDelay(5000)
        saveFrame(context.cacheDir, "inotia-wfs-loaded-slot1.png", loadedFrame)
        val loadError = pendingError()
        assertTrue("Loading user WFS slot 1 produced a native error: ${loadError ?: "none"}", loadError == null)

        repeat(4) { press("RIGHT") }
        repeat(2) { press("DOWN") }
        waitAndPump(1500)

        press("SOFT_L")
        val menuFrame = captureAfterDelay(1500)
        saveFrame(context.cacheDir, "inotia-wfs-menu.png", menuFrame)
        repeat(5) { press("RIGHT") }
        val systemTab = captureAfterDelay(1200)
        saveFrame(context.cacheDir, "inotia-wfs-system-tab.png", systemTab)
        press("OK")
        val systemList = captureAfterDelay(1500)
        saveFrame(context.cacheDir, "inotia-wfs-system-list.png", systemList)

        press("OK")
        waitAndPump(1500)
        press("OK")
        val saveResult = captureAfterDelay(4000)
        saveFrame(context.cacheDir, "inotia-wfs-save-result.png", saveResult)
        val saveError = pendingError()
        assertTrue("Game Save produced a native error: ${saveError ?: "none"}", saveError == null)
        WipiNative.nativeStop()
        Thread.sleep(500)

        assertTrue("Game Save removed slot 1 save0.dat", saveFile.isFile)
        val afterBytes = saveFile.readBytes()
        val afterSha = sha256(afterBytes)
        val afterMtime = saveFile.lastModified()
        assertTrue("Game Save left slot 1 empty", afterBytes.isNotEmpty())
        assertTrue(
            "Save command did not overwrite slot 1 (same bytes and timestamp)",
            afterSha != importedSha || afterMtime > importedMtime,
        )

        File(root, "phase1-proof.txt").writeText(
            "wfsSha256=${sha256(wfs)}\n" +
                "wfsEntries=${logical.keys.sorted()}\n" +
                "importEntries=${importResult.entryCount}\n" +
                "importBytes=${importResult.totalBytes}\n" +
                "importedSize=${importedBytes.size}\n" +
                "importedSha256=$importedSha\n" +
                "importedMtime=$importedMtime\n" +
                "afterSize=${afterBytes.size}\n" +
                "afterSha256=$afterSha\n" +
                "afterMtime=$afterMtime\n" +
                "loadError=${loadError ?: "none"}\n" +
                "saveError=${saveError ?: "none"}\n"
        )
        File(context.cacheDir, "inotia-wfs-phase1.txt").writeText(File(root, "phase1-proof.txt").readText())
        println("INOTIA_WFS_PHASE1 imported=$importedSha after=$afterSha size=${afterBytes.size}")
    }

    @Test
    fun phase2RelaunchAndLoadPersistedSlot1() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        WipiNative.init(context)

        val root = File(context.filesDir, "games/$gameId")
        val gameFile = File(root, "inotia1.zip")
        val dataDir = File(root, "data")
        val proofFile = File(root, "phase1-proof.txt")
        val saveFile = File(dataDir, "db/PD005362/save0.dat/1")
        assertTrue("Phase 1 game package is missing after process restart", gameFile.isFile)
        assertTrue("Phase 1 proof is missing after process restart", proofFile.isFile)
        assertTrue("Slot 1 save0.dat is missing after process restart", saveFile.isFile)

        val proof = proofFile.readText()
        val expectedSha = proof.lineSequence()
            .firstOrNull { it.startsWith("afterSha256=") }
            ?.substringAfter('=')
            ?: throw AssertionError("Phase 1 did not record the post-save SHA")
        val persistedBytes = saveFile.readBytes()
        val persistedSha = sha256(persistedBytes)
        assertTrue("Saved slot bytes changed while the app was stopped", persistedSha == expectedSha)
        assertTrue("Persisted slot 1 is empty", persistedBytes.isNotEmpty())

        assertTrue(WipiNative.nativeStart(gameFile.readBytes(), "inotia1.zip", dataDir.absolutePath, ""))
        navigateToContinueSlot1()
        val relaunchedFrame = captureAfterDelay(5000)
        saveFrame(context.cacheDir, "inotia-wfs-relaunched-slot1.png", relaunchedFrame)
        val reloadError = pendingError()
        assertTrue("Reloading persisted slot 1 produced a native error: ${reloadError ?: "none"}", reloadError == null)
        WipiNative.nativeStop()

        val report = proof +
            "persistedSize=${persistedBytes.size}\n" +
            "persistedSha256=$persistedSha\n" +
            "reloadError=${reloadError ?: "none"}\n" +
            "relaunchLoadSucceeded=true\n"
        File(context.cacheDir, "inotia-wfs-phase2.txt").writeText(report)
        println("INOTIA_WFS_PHASE2 persisted=$persistedSha reloadError=${reloadError ?: "none"}")
    }

    private fun navigateToContinueSlot1() {
        waitAndPump(7000); press("OK")
        waitAndPump(3500); press("OK")
        waitAndPump(8000); press("OK")
        waitAndPump(5000); press("OK")
        waitAndPump(6000); press("OK")
        waitAndPump(8000)
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
}
