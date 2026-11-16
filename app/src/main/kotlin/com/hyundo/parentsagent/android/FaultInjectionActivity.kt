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
        result = Ui.text(this, "", 20f)
        box.addView(result)
        setContentView(ScrollView(this).apply { addView(box) })
    }
}
