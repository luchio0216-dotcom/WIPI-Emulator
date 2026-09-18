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
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Guided two-stage installer for titles whose original handset install process
 * required a first launch before the P/ post-download databases were copied in.
 */
class PDataHomeActivity : ComponentActivity() {
    private lateinit var library: GameLibrary
    private lateinit var importer: PDataImporter
    private lateinit var gameSpinner: Spinner
    private lateinit var statusText: TextView
    private var games: List<GameEntry> = emptyList()

    private val stage1Picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val game = selectedGame() ?: return@registerForActivityResult
        statusText.text = "1단계 준비 중... 기존 게임 데이터를 초기화하고 P 폴더를 뺀 실행본을 만들고 있습니다."
        importer.prepareFirstStage(game, uri)
            .onSuccess { result ->
                statusText.text = "1단계 준비 완료\nAID: ${result.aid}\nPID: ${result.pid}\n제외된 P 파일: ${result.removedPFiles}개\n\n이제 '에뮬레이터 열기'로 게임을 실행하세요.\n600KB 데이터 전송 안내가 뜨면 예/아니오를 누르지 말고 게임을 종료하세요.\n(뒤로가기 → 일시정지 화면 → 뒤로가기 한 번 더)\n\n그 다음 이 화면으로 돌아와 2단계를 진행하세요."
            }
            .onFailure { error ->
                statusText.text = "1단계 준비 실패: ${error.message ?: error.javaClass.simpleName}"
            }
    }

    private val stage2Picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val game = selectedGame() ?: return@registerForActivityResult
        statusText.text = "2단계 P 데이터 적용 중..."
        importer.importZip(game, uri)
            .onSuccess { result ->
                statusText.text = "2단계 적용 완료\nAID: ${result.aid}\nPID: ${result.pid}\nP 파일: ${result.fileCount}개\nDB 주입: ${result.databaseCount}개\n용량: ${result.totalBytes} bytes\n\n이제 '에뮬레이터 열기'로 다시 실행하세요. 이번에는 600KB 네트워크 다운로드 없이 넘어가는지 확인하면 됩니다."
            }
            .onFailure { error ->
                statusText.text = "2단계 적용 실패: ${error.message ?: error.javaClass.simpleName}"
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = GameLibrary(this)
        importer = PDataImporter(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 64)
            setBackgroundColor(Color.rgb(24, 24, 24))
        }

        val title = TextView(this).apply {
            text = "WIPI 에뮬 P지원 V2"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        content.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val hint = TextView(this).apply {
            text = "이노티아연대기1의 원래 설치 순서를 그대로 재현합니다.\n\n① 먼저 에뮬레이터에서 게임 ZIP을 한 번 추가\n② 1단계 버튼에서 같은 전체 ZIP 선택\n③ 게임 첫 실행 → 600KB 안내에서 아무 버튼도 누르지 않고 종료\n④ 2단계 버튼에서 같은 전체 ZIP 선택\n⑤ 최종 실행"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 20)
        }
        content.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        gameSpinner = Spinner(this)
        content.addView(gameSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val stage1Button = Button(this).apply {
            text = "1단계: P 제외 · 완전 초기화"
            setOnClickListener {
                refreshGames()
                if (games.isEmpty()) {
                    statusText.text = "먼저 아래 '에뮬레이터 열기'에서 이노티아1 ZIP을 게임 목록에 추가한 뒤 돌아오세요."
                } else {
                    stage1Picker.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            }
        }
        content.addView(stage1Button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 24
        })

        val emulatorButton = Button(this).apply {
            text = "에뮬레이터 열기"
            setOnClickListener { startActivity(Intent(this@PDataHomeActivity, MainActivity::class.java)) }
        }
        content.addView(emulatorButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12
        })

        val stage2Button = Button(this).apply {
            text = "2단계: P 데이터 · DB 적용"
            setOnClickListener {
                refreshGames()
                if (games.isEmpty()) {
                    statusText.text = "게임이 없습니다. 먼저 게임 ZIP을 추가하세요."
                } else {
                    stage2Picker.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            }
        }
        content.addView(stage2Button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12
        })

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }
        content.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        refreshGames()
    }

    override fun onResume() {
        super.onResume()
        refreshGames()
    }

    private fun selectedGame(): GameEntry? {
        refreshGames()
        val game = games.getOrNull(gameSpinner.selectedItemPosition)
        if (game == null) statusText.text = "먼저 게임을 추가해 주세요."
        return game
    }

    private fun refreshGames() {
        val selectedId = games.getOrNull(gameSpinner.selectedItemPosition)?.id
        games = library.list()
        val labels = if (games.isEmpty()) listOf("추가된 게임 없음") else games.map { it.name }
        gameSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (selectedId != null) {
            val index = games.indexOfFirst { it.id == selectedId }
            if (index >= 0) gameSpinner.setSelection(index)
        }
    }
}
