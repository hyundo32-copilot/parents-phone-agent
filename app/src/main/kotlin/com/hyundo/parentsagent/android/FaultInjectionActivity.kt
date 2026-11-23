package com.hyundo.parentsagent.android

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hyundo.parentsagent.BuildConfig

/** 장애 주입 메뉴 (데모·테스트용, 디버그 빌드에서만 열린다). 버튼 하나로 고장을 재현한다. */
class FaultInjectionActivity : android.app.Activity() {
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        AgentRuntime.init(this)
        val injector = FaultInjector(this, AgentRuntime.reader)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@FaultInjectionActivity, 16), Ui.dp(this@FaultInjectionActivity, 16), Ui.dp(this@FaultInjectionActivity, 16), Ui.dp(this@FaultInjectionActivity, 16))
        }
        box.addView(Ui.text(this, "장애 주입 (데모·테스트용)", 26f, bold = true))
        box.addView(Ui.text(this, "누르면 일부러 고장을 만들어요. 뒤로 가서 [지금 점검하기]를 누르면 복원돼요."))
        fun add(label: String, action: () -> String) = box.addView(Ui.manageButton(this, label) {
            result.text = "실행 중…"
            AgentRuntime.async { val r = action(); runOnUiThread { result.text = r } }
        })
        add("비행기 모드 켜기") { injector.airplaneOn() }
        add("무음으로 바꾸기") { injector.silent() }
        add("방해금지 켜기") { injector.dnd() }
        add("전화 앱 알림 끄기") { injector.notificationsOff() }
        add("1분 뒤 예약 점검") {
            val at = java.time.Instant.ofEpochMilli(Scheduler.scheduleTestCheck(this))
                .atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)
            "1분 뒤 예약 점검이 울립니다. 화면을 꺼두고 기다리세요. (예정 $at)"
        }
        add("안부 신호 미리보기 (걸음수 테스트)") {
            if (!StepCounterReader.hasPermission(this)) {
                runOnUiThread { requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), 1) }
                "걸음수 권한이 필요해요. 허용한 뒤 다시 눌러 주세요. (지금 미리보기는 걸음수 알 수 없음)\n\n" +
                    AgentRuntime.heartbeat.previewText(java.time.LocalDateTime.now())
            } else {
                AgentRuntime.heartbeat.previewText(java.time.LocalDateTime.now())
            }
        }
        result = Ui.text(this, "", 20f)
        box.addView(result)
        setContentView(ScrollView(this).apply { addView(box) })
    }
}
