package com.parkjeongseop.wipi

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

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

        val fullZip = testContext.assets.open("inotia1_flat.zip").use { it.readBytes() }
        val gameRoot = File(context.filesDir, "games/inotia-min-autotest").apply { deleteRecursively(); mkdirs() }
        val gameFile = File(gameRoot, "inotia1.zip").apply { writeBytes(fullZip) }
        val entry = GameEntry("inotia-min-autotest", "Inotia 1 KTF subscriber fallback test", null, gameFile, "inotia1.zip", File(gameRoot, "data"))
        entry.dataDir.deleteRecursively(); entry.dataDir.mkdirs()

        assertTrue(WipiNative.nativeStart(entry.gameFile.readBytes(), entry.filename, entry.dataDir.absolutePath, ""))
        waitAndPump(7000); pressOk()
        val titleFrame = captureAfterDelay(3500); val titleError = pendingError(); saveFrame(context.cacheDir, "inotia-before.png", titleFrame)
        pressOk()
        val menuFrame = captureAfterDelay(8000); val menuError = pendingError(); saveFrame(context.cacheDir, "inotia-after-restart.png", menuFrame)

        // Main menu defaults to scenario mode. The former storage warning occurred here.
        pressOk()
        val scenarioFrame = captureAfterDelay(5000); val scenarioError = pendingError()

        // Scenario submenu defaults to Continue. Move once to New Game and confirm.
        pressDown(); pressOk()
        val slotFrame = captureAfterDelay(6000); val slotError = pendingError(); saveFrame(context.cacheDir, "inotia-after-ok.png", slotFrame)

        // New-game flow now shows the save-slot selector. Confirm SLOT 1 and capture the
        // next screen. This is the decisive step for the user's requested character
        // selection/new-game proof; do not stop at the slot selector itself.
        pressOk()
        val characterFrame = captureAfterDelay(8000); val characterError = pendingError(); saveFrame(context.cacheDir, "inotia-character.png", characterFrame)

        val tree = summarizeDataTree(entry.dataDir)
        val digest = MessageDigest.getInstance("SHA-256").digest(fullZip).joinToString("") { "%02x".format(it) }
        val stage2 = "aid=010100D3\npid=PD005362\nmode=complete-package+embedded-subscriber-fallback\nsubscriberForInotia=01012349876\npackageSha256=$digest\npackageBytes=${fullZip.size}\n--- final data tree ---\n$tree\n"
        val report = "titleError=${titleError ?: "none"}\nmenuError=${menuError ?: "none"}\nscenarioError=${scenarioError ?: "none"}\nslotError=${slotError ?: "none"}\ncharacterError=${characterError ?: "none"}\ntitleToMenuDiff=${diffRatio(titleFrame, menuFrame)}\nmenuToScenarioDiff=${diffRatio(menuFrame, scenarioFrame)}\nscenarioToSlotDiff=${diffRatio(scenarioFrame, slotFrame)}\nslotToCharacterDiff=${diffRatio(slotFrame, characterFrame)}\n"
        File(context.cacheDir, "inotia-stage2.txt").writeText(stage2); File(context.cacheDir, "inotia-report.txt").writeText(report)
        println("INOTIA_STAGE2_BEGIN"); print(stage2); println("INOTIA_STAGE2_END"); println("INOTIA_REPORT_BEGIN"); print(report); println("INOTIA_REPORT_END")
        WipiNative.nativeStop()
    }

    @Test
    fun wFeatureWfsRoundTripRestoresKtfSaveTree() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        val archive = testContext.assets.open("inotia1_flat.zip").use { it.readBytes() }
        val packagedChar = zipEntry(archive, "P/char.dat")

        val sourceRoot = File(context.filesDir, "games/inotia-wfs-source").apply { deleteRecursively(); mkdirs() }
        val sourceFile = File(sourceRoot, "inotia1.zip").apply { writeBytes(archive) }
        val source = GameEntry("inotia-wfs-source", "Inotia WFS source", null, sourceFile, "inotia1.zip", File(sourceRoot, "data"))
        val sourceDb = File(source.dataDir, "db/PD005362")
        File(sourceDb, "char.dat").mkdirs(); File(sourceDb, "char.dat/1").writeBytes(packagedChar)
        val prefs = ByteArray(64) { i -> (i * 3 + 7).toByte() }
        val save0 = ByteArray(540) { i -> (i * 11 + 5).toByte() }
        File(sourceDb, "prefs").mkdirs(); File(sourceDb, "prefs/1").writeBytes(prefs)
        File(sourceDb, "save0.dat").mkdirs(); File(sourceDb, "save0.dat/1").writeBytes(save0)
        File(sourceDb, "deleted.dat").mkdirs() // empty leaf = W-Feature db/.removed tombstone
        val fsFile = File(source.dataDir, "fs/010100D3/options.bin").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(9, 8, 7, 6)) }

        val wfs = SaveBackup.exportKtf(source)
        assertEquals("WFSAVEBK", wfs.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals(1, wfs[8].toInt() and 0xff)
        assertEquals(0, wfs[9].toInt() and 0xff)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(archive), wfs.copyOfRange(10, 42))
        val payloadLength = u32le(wfs, 42).toInt()
        assertEquals(wfs.size - 50, payloadLength)
        val payload = wfs.copyOfRange(50, wfs.size)
        assertEquals(u32le(wfs, 46), CRC32().apply { update(payload) }.value)

        val logical = SaveBackup.decodeForTest(wfs, archive).associate { it.key to it.data }
        assertTrue("packaged char.dat must not be exported as player progress", "db/char.dat" !in logical)
        assertArrayEquals(prefs, logical["db/prefs"])
        assertArrayEquals(save0, logical["db/save0.dat"])
        assertEquals("deleted.dat", logical["db/.removed"]?.toString(Charsets.UTF_8))
        assertArrayEquals(fsFile.readBytes(), logical["fs/options.bin"])

        val targetRoot = File(context.filesDir, "games/inotia-wfs-target").apply { deleteRecursively(); mkdirs() }
        val targetFile = File(targetRoot, "inotia1.zip").apply { writeBytes(archive) }
        val target = GameEntry("inotia-wfs-target", "Inotia WFS target", null, targetFile, "inotia1.zip", File(targetRoot, "data"))
        // Stale progress must be replaced rather than merged.
        File(target.dataDir, "db/PD005362/oldsave.dat").mkdirs(); File(target.dataDir, "db/PD005362/oldsave.dat/1").writeBytes(byteArrayOf(1, 2, 3))

        val result = SaveBackup.importKtf(target, wfs)
        assertEquals(logical.size, result.entryCount)
        assertArrayEquals(prefs, File(target.dataDir, "db/PD005362/prefs/1").readBytes())
        assertArrayEquals(save0, File(target.dataDir, "db/PD005362/save0.dat/1").readBytes())
        assertTrue(File(target.dataDir, "db/PD005362/deleted.dat").isDirectory)
        assertTrue(File(target.dataDir, "db/PD005362/deleted.dat").listFiles().isNullOrEmpty())
        assertTrue(!File(target.dataDir, "db/PD005362/oldsave.dat").exists())
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), File(target.dataDir, "fs/010100D3/options.bin").readBytes())

        // The format is deterministic: restoring and exporting the same logical save produces the same .wfs bytes.
        val roundTrip = SaveBackup.exportKtf(target)
        assertArrayEquals(wfs, roundTrip)
        File(context.cacheDir, "inotia-wfs-report.txt").writeText(
            "wfsBytes=${wfs.size}\nentries=${logical.keys.sorted()}\nroundTripExact=${wfs.contentEquals(roundTrip)}\n"
        )
    }

    private fun zipEntry(zipBytes: ByteArray, wanted: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.replace('\\', '/') == wanted) return zip.readBytes()
                zip.closeEntry()
            }
        }
        error("ZIP entry missing: $wanted")
    }

    private fun u32le(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 0xff) shl (i * 8)) }

    private fun pressOk() = pressKey("OK")
    private fun pressDown() = pressKey("DOWN")
    private fun pressKey(key: String) { WipiNative.nativeKeyDown(key); Thread.sleep(150); WipiNative.nativeKeyUp(key); Thread.sleep(250) }
    private fun waitAndPump(delayMs: Long) { val deadline = System.currentTimeMillis() + delayMs; val frame = IntArray(width * height); while (System.currentTimeMillis() < deadline) { WipiNative.nativeGetFrame(frame); Thread.sleep(40) } }
    private fun pendingError(): String? { val kind = IntArray(1); return WipiNative.nativeGetError(kind)?.let { "kind=${kind[0]} $it" } }
    private fun captureAfterDelay(delayMs: Long): IntArray { waitAndPump(delayMs); val frame = IntArray(width * height); var sawFrame = false; repeat(100) { if (WipiNative.nativeGetFrame(frame)) sawFrame = true; Thread.sleep(20) }; assertTrue("No emulator frame was produced", sawFrame); return frame.copyOf() }
    private fun saveFrame(dir: File, name: String, pixels: IntArray) { val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.setPixels(pixels, 0, width, 0, 0, width, height); File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    private fun diffRatio(a: IntArray, b: IntArray): Double { var changed = 0L; val count = minOf(a.size, b.size); for (i in 0 until count) { val ca=a[i]; val cb=b[i]; val delta=kotlin.math.abs(((ca shr 16) and 0xff)-((cb shr 16) and 0xff))+kotlin.math.abs(((ca shr 8) and 0xff)-((cb shr 8) and 0xff))+kotlin.math.abs((ca and 0xff)-(cb and 0xff)); if (delta > 24) changed++ }; return if (count == 0) 0.0 else changed.toDouble()/count.toDouble() }
    private fun summarizeDataTree(root: File): String { if (!root.exists()) return "<missing>"; return root.walkTopDown().filter { it.isFile }.map { file -> "${file.relativeTo(root).path.replace(File.separatorChar, '/')} (${file.length()})" }.sorted().joinToString("\n").ifBlank { "<empty>" } }
}
