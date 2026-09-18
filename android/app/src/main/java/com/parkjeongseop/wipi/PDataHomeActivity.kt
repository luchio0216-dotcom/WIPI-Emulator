package com.parkjeongseop.wipi

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Small launcher screen for the P-data-enabled build.
 * It leaves the original emulator screen untouched and provides a safe importer
 * that can write into this app's own internal storage without root access.
 */
class PDataHomeActivity : ComponentActivity() {
    private lateinit var library: GameLibrary
    private lateinit var importer: PDataImporter
    private lateinit var gameSpinner: Spinner
    private lateinit var statusText: TextView
    private var games: List<GameEntry> = emptyList()

    private val pZipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val game = games.getOrNull(gameSpinner.selectedItemPosition)
        if (game == null) {
            statusText.text = "먼저 게임을 추가해 주세요."
            return@registerForActivityResult
        }

        statusText.text = "P 데이터 추가 중..."
        importer.importZip(game, uri)
            .onSuccess { result ->
                statusText.text = "추가 완료\nAID: ${result.aid}\n파일: ${result.fileCount}개\n용량: ${result.totalBytes} bytes\n\n이제 아래 버튼으로 에뮬레이터를 열고 게임을 실행하세요."
            }
            .onFailure { error ->
                statusText.text = "추가 실패: ${error.message ?: error.javaClass.simpleName}"
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = GameLibrary(this)
        importer = PDataImporter(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 72, 48, 48)
            setBackgroundColor(Color.rgb(24, 24, 24))
        }

        val title = TextView(this).apply {
            text = "WIPI 에뮬 P지원판"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val hint = TextView(this).apply {
            text = "이노티아1처럼 실행 후 추가 데이터를 요구하는 게임용입니다.\n먼저 일반 에뮬 화면에서 게임 ZIP을 추가한 뒤 돌아오세요."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 24)
        }
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        gameSpinner = Spinner(this)
        root.addView(gameSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val importButton = Button(this).apply {
            text = "P 데이터 ZIP 선택 · 추가"
            setOnClickListener {
                refreshGames()
                if (games.isEmpty()) {
                    statusText.text = "설치된 게임이 없습니다. 먼저 '에뮬레이터 열기'에서 게임 ZIP을 추가하세요."
                } else {
                    pZipPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            }
        }
        root.addView(importButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 28
        })

        val emulatorButton = Button(this).apply {
            text = "에뮬레이터 열기"
            setOnClickListener { startActivity(Intent(this@PDataHomeActivity, MainActivity::class.java)) }
        }
        root.addView(emulatorButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 16
        })

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }
        root.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        refreshGames()
    }

    override fun onResume() {
        super.onResume()
        refreshGames()
    }

    private fun refreshGames() {
        games = library.list()
        val labels = if (games.isEmpty()) listOf("추가된 게임 없음") else games.map { it.name }
        gameSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }
}
