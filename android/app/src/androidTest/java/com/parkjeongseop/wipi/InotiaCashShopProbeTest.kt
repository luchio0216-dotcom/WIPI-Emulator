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
class InotiaCashShopProbeTest {
    private val width = SCREEN_WIDTH
    private val height = SCREEN_HEIGHT
    private val gameId = "inotia-cash-probe"
    private val lastFrame = IntArray(width * height)
    private var haveFrame = false
    private var staticCaptures = 0

    @Test
    fun openCashShopAndCaptureBackendFailure() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val testContext = inst.context
        WipiNative.init(context)

        val archive = testContext.assets.open("inotia1_flat.zip").use { it.readBytes() }
        val originalWfs = testContext.assets.open("inotia_user_slot1.wfs").use { it.readBytes() }
        val wfs = rebindFixtureIdentity(originalWfs, archive)
        val entry = entry(context.filesDir, archive)
        SaveBackup.importKtf(entry, wfs)

        assertTrue(WipiNative.nativeStart(archive, entry.filename, entry.dataDir.absolutePath, ""))
        continueSlot1()
        frame(context.cacheDir, "cash-probe-00-gameplay.png", capture(4500))

        // User-confirmed keypad semantics: 5=OK, 8=down, 4=left, CLR=menu.
        // System menu -> 4th row (Cash item purchase) -> activate -> accept charge prompt.
        press("CLR")
        frame(context.cacheDir, "cash-probe-01-clr.png", capture(1000))
        press("4")
        frame(context.cacheDir, "cash-probe-02-system-tab.png", capture(1000))
        press("5")
        frame(context.cacheDir, "cash-probe-03-system-list.png", capture(1000))
        repeat(3) { index ->
            press("8")
            frame(context.cacheDir, "cash-probe-0${4 + index}-down${index + 1}.png", capture(700))
        }
        press("5")
        frame(context.cacheDir, "cash-probe-07-charge-prompt.png", capture(1500))
        press("5") // default selection is Yes
        frame(context.cacheDir, "cash-probe-08-confirm-yes.png", capture(1800))
        waitPump(12000)
        frame(context.cacheDir, "cash-probe-09-after-wait.png", capture(1000))

        val kind = IntArray(1)
        val nativeError = WipiNative.nativeGetError(kind)
        File(context.cacheDir, "cash-probe-report.txt").writeText(
            buildString {
                appendLine("sequence=CLR,4,5,8,8,8,5,5")
                appendLine("staticCaptures=$staticCaptures")
                appendLine("nativeErrorKind=${if (nativeError == null) "none" else kind[0]}")
                appendLine("nativeError=${nativeError ?: "none"}")
                appendLine("pollExit=${WipiNative.nativePollExit()}")
            }
        )
        println("INOTIA_CASH_PROBE_DONE staticCaptures=$staticCaptures error=${nativeError ?: "none"}")
        WipiNative.nativeStop()
    }

    private fun entry(filesDir: File, archive: ByteArray): GameEntry {
        val root = File(filesDir, "games/$gameId")
        root.deleteRecursively()
        root.mkdirs()
        val gameFile = File(root, "inotia1.zip")
        gameFile.writeBytes(archive)
        return GameEntry(gameId, "Inotia cash probe", null, gameFile, "inotia1.zip", File(root, "data")).also { it.dataDir.mkdirs() }
    }

    private fun continueSlot1() {
        waitPump(7000); press("OK")
        waitPump(3500); press("OK")
        waitPump(8000); press("OK")
        waitPump(5000); press("OK")
        waitPump(6000); press("OK")
        waitPump(8000)
    }

    private fun press(key: String) {
        WipiNative.nativeKeyDown(key)
        Thread.sleep(150)
        WipiNative.nativeKeyUp(key)
        Thread.sleep(300)
    }

    private fun pumpFrame(): Boolean {
        val pixels = IntArray(width * height)
        val fresh = WipiNative.nativeGetFrame(pixels)
        if (fresh) {
            pixels.copyInto(lastFrame)
            haveFrame = true
        }
        return fresh
    }

    private fun waitPump(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            pumpFrame()
            Thread.sleep(40)
        }
    }

    private fun capture(ms: Long): IntArray {
        waitPump(ms)
        var fresh = false
        repeat(100) {
            if (pumpFrame()) fresh = true
            Thread.sleep(20)
        }
        assertTrue("emulator never produced any frame", haveFrame)
        if (!fresh) staticCaptures++
        return lastFrame.copyOf()
    }

    private fun frame(dir: File, name: String, pixels: IntArray) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun rebindFixtureIdentity(wfs: ByteArray, archive: ByteArray): ByteArray {
        assertTrue("fixture WFS header too short", wfs.size >= 50)
        assertTrue("fixture WFS magic mismatch", wfs.copyOfRange(0, 8).contentEquals("WFSAVEBK".toByteArray(Charsets.US_ASCII)))
        val expected = MessageDigest.getInstance("SHA-256").digest(archive)
        if (wfs.copyOfRange(10, 42).contentEquals(expected)) return wfs
        return wfs.copyOf().also { expected.copyInto(it, destinationOffset = 10) }
    }
}
