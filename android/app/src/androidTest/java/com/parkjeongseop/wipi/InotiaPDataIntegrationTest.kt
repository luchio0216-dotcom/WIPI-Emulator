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
