package com.parkjeongseop.wipi

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class InotiaUserWfsSlot1PersistenceTest {
    private val width = SCREEN_WIDTH
    private val height = SCREEN_HEIGHT
    private val gameId = "inotia-user-slot1"

    @Test
    fun importAndOverwriteSlot1ThenWaitForForceStop() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val testContext = inst.context
        WipiNative.init(context)
        val archive = testContext.assets.open("inotia1_flat.zip").use { it.readBytes() }
        val originalWfs = testContext.assets.open("inotia_user_slot1.wfs").use { it.readBytes() }
        val archiveIdentity = shaBytes(archive)
        val originalIdentity = originalWfs.copyOfRange(10, 42)
        val fixtureIdentityRebound = !originalIdentity.contentEquals(archiveIdentity)
        val wfs = rebindFixtureIdentity(originalWfs, archive)
        val entry = entry(context.filesDir, archive, reset = true)
        val decoded = SaveBackup.decodeForTest(wfs, archive).associate { it.key to it.data }
        assertTrue("user WFS has no db/save0.dat", decoded["db/save0.dat"]?.isNotEmpty() == true)
        assertTrue("user WFS has no db/prefs", decoded["db/prefs"]?.isNotEmpty() == true)
        val imported = SaveBackup.importKtf(entry, wfs)
        val saveFile = File(entry.dataDir, "db/PD005362/save0.dat/1")
        val prefsFile = File(entry.dataDir, "db/PD005362/prefs/1")
        assertTrue("WFS import did not restore save0.dat", saveFile.isFile && saveFile.length() > 0)
        assertTrue("WFS import did not restore prefs", prefsFile.isFile && prefsFile.length() > 0)
        assertEquals(sha(decoded.getValue("db/save0.dat")), sha(saveFile.readBytes()))
        assertEquals(sha(decoded.getValue("db/prefs")), sha(prefsFile.readBytes()))
        val importedSha = sha(saveFile.readBytes())
        assertTrue(WipiNative.nativeStart(archive, entry.filename, entry.dataDir.absolutePath, ""))
        continueSlot1()
        val gameplay = capture(6000)
        frame(context.cacheDir, "user-wfs-imported-slot1-gameplay.png", gameplay)
        assertTrue("existing SLOT 1 did not load: ${error()}", error() == null)
        repeat(3) { press("RIGHT") }
        repeat(2) { press("DOWN") }
        waitPump(1500)
        saveFromGameplay(context.cacheDir)
        val saveResult = capture(3500)
        frame(context.cacheDir, "user-wfs-overwrite-result.png", saveResult)
        assertTrue("System/Save raised native error: ${error()}", error() == null)
        assertTrue("overwrite removed save0.dat", saveFile.isFile && saveFile.length() > 0)
        val committedSha = sha(saveFile.readBytes())
        assertNotEquals("save0.dat bytes did not update after real in-game overwrite", importedSha, committedSha)
        File(context.filesDir, "inotia-user-slot1-phase1.txt").writeText(
            "wfsBytes=${originalWfs.size}\nfixtureIdentityRebound=$fixtureIdentityRebound\nimportEntries=${imported.entryCount}\nimportTotalBytes=${imported.totalBytes}\nimportedSaveSha256=$importedSha\ncommittedSaveSha256=$committedSha\nsaveBytes=${saveFile.length()}\nprefsBytes=${prefsFile.length()}\nslot1LoadSucceeded=true\noverwriteSucceeded=true\n"
        )
        File(context.filesDir, "inotia-force-stop-ready.flag").writeText(committedSha)
        println("INOTIA_USER_WFS_PHASE1_READY imported=$importedSha committed=$committedSha bytes=${saveFile.length()} rebound=$fixtureIdentityRebound")
        while (true) Thread.sleep(1000)
    }

    @Test
    fun reloadPersistedSlotAfterForceStop() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val testContext = inst.context
        WipiNative.init(context)
        val archive = testContext.assets.open("inotia1_flat.zip").use { it.readBytes() }
        val entry = entry(context.filesDir, archive, reset = false)
        val saveFile = File(entry.dataDir, "db/PD005362/save0.dat/1")
        val prefsFile = File(entry.dataDir, "db/PD005362/prefs/1")
        val marker = File(context.filesDir, "inotia-force-stop-ready.flag")
        assertTrue("phase-1 force-stop marker missing", marker.isFile)
        val expectedCommittedSha = marker.readText().trim()
        assertTrue("save0.dat missing after process death", saveFile.isFile && saveFile.length() > 0)
        assertTrue("prefs missing after process death", prefsFile.isFile && prefsFile.length() > 0)
        assertEquals("save0.dat changed across process death", expectedCommittedSha, sha(saveFile.readBytes()))
        assertTrue(WipiNative.nativeStart(archive, entry.filename, entry.dataDir.absolutePath, ""))
        continueSlot1()
        val reloaded = capture(6000)
        frame(context.cacheDir, "user-wfs-reloaded-slot1-gameplay.png", reloaded)
        val reloadError = error()
        assertTrue("persisted SLOT 1 failed to reload after force-stop: ${reloadError ?: "none"}", reloadError == null)
        assertEquals("save0.dat changed during reload", expectedCommittedSha, sha(saveFile.readBytes()))
        File(context.cacheDir, "user-wfs-final-report.txt").writeText(
            "committedSaveSha256=$expectedCommittedSha\npersistedSaveSha256=${sha(saveFile.readBytes())}\nsaveBytes=${saveFile.length()}\nprefsBytes=${prefsFile.length()}\nforceStopPersistence=true\nslot1ReloadSucceeded=true\nreloadError=${reloadError ?: "none"}\n"
        )
        println("INOTIA_USER_WFS_PERSISTENCE_OK sha=$expectedCommittedSha bytes=${saveFile.length()}")
        WipiNative.nativeStop()
    }

    private fun entry(filesDir: File, archive: ByteArray, reset: Boolean): GameEntry {
        val root = File(filesDir, "games/$gameId")
        if (reset) root.deleteRecursively()
        root.mkdirs()
        val gameFile = File(root, "inotia1.zip")
        if (!gameFile.isFile || reset) gameFile.writeBytes(archive)
        return GameEntry(gameId, "Inotia user WFS slot 1", null, gameFile, "inotia1.zip", File(root, "data")).also { it.dataDir.mkdirs() }
    }

    private fun continueSlot1() {
        waitPump(7000); press("OK"); waitPump(3500); press("OK"); waitPump(8000); press("OK"); waitPump(5000); press("OK"); waitPump(6000); press("OK"); waitPump(8000)
    }

    private fun saveFromGameplay(dir: File) {
        // Runs #31/#32: soft-key guesses enter minimap. #33: CLR is a no-op.
        // #34: center OK also enters minimap. Probe NUM0 next; the minimap itself
        // advertises */# controls, so NUM0 is a stronger independent menu candidate.
        press("0")
        frame(dir, "user-wfs-menu-open.png", capture(1200))
        repeat(5) { press("RIGHT") }
        frame(dir, "user-wfs-system-tab.png", capture(900))
        press("OK")
        frame(dir, "user-wfs-system-list.png", capture(1200))
        press("OK")
        frame(dir, "user-wfs-save-selected.png", capture(2200))
        press("OK")
        frame(dir, "user-wfs-save-after-confirm.png", capture(1200))
    }

    private fun press(key: String) { WipiNative.nativeKeyDown(key); Thread.sleep(150); WipiNative.nativeKeyUp(key); Thread.sleep(300) }
    private fun waitPump(ms: Long) { val until = System.currentTimeMillis() + ms; val pixels = IntArray(width * height); while (System.currentTimeMillis() < until) { WipiNative.nativeGetFrame(pixels); Thread.sleep(40) } }
    private fun capture(ms: Long): IntArray { waitPump(ms); val pixels = IntArray(width * height); var saw = false; repeat(100) { if (WipiNative.nativeGetFrame(pixels)) saw = true; Thread.sleep(20) }; assertTrue("no emulator frame", saw); return pixels.copyOf() }
    private fun error(): String? { val kind = IntArray(1); return WipiNative.nativeGetError(kind)?.let { "kind=${kind[0]} $it" } }
    private fun frame(dir: File, name: String, pixels: IntArray) { val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.setPixels(pixels, 0, width, 0, 0, width, height); File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    private fun rebindFixtureIdentity(wfs: ByteArray, archive: ByteArray): ByteArray { assertTrue("fixture WFS header too short", wfs.size >= 50); assertTrue("fixture WFS magic mismatch", wfs.copyOfRange(0, 8).contentEquals("WFSAVEBK".toByteArray(Charsets.US_ASCII))); val expected = shaBytes(archive); if (wfs.copyOfRange(10, 42).contentEquals(expected)) return wfs; return wfs.copyOf().also { expected.copyInto(it, destinationOffset = 10) } }
    private fun shaBytes(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun sha(bytes: ByteArray): String = shaBytes(bytes).joinToString("") { "%02x".format(it) }
}
