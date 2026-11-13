package com.hyundo.parentsagent.android

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.hyundo.parentsagent.BuildConfig
import com.hyundo.parentsagent.core.SettingKey
import com.hyundo.parentsagent.core.Snapshot
import com.hyundo.parentsagent.core.Symptom
import com.hyundo.parentsagent.core.Thresholds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : android.app.Activity() {
    private lateinit var content: LinearLayout
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private val statusListener: () -> Unit = { renderBanner() }

    private val store get() = AgentRuntime.store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentRuntime.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF3CD"))
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12))
            visibility = View.GONE
        }
        bannerText = Ui.text(this, "", 24f, bold = true)
        banner.addView(bannerText)
        banner.addView(Ui.button(this, "중단", "#D32F2F") { StopController.request() }.apply { minimumHeight = Ui.dp(this@MainActivity, 96) })
        root.addView(banner)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 32))
        }
        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        StatusBus.addListener(statusListener)
        render()
        if (!store.consented) askConsent()
    }

    override fun onPause() {
        StatusBus.removeListener(statusListener)
        super.onPause()
    }

    private fun renderBanner() {
        val t = StatusBus.fixingText
        banner.visibility = if (t == null) View.GONE else View.VISIBLE
        bannerText.text = "지금 고치는 중이에요\n${t.orEmpty()}"
        if (t == null) render()
    }

    private fun askConsent() {
        AlertDialog.Builder(this)
            .setTitle("이 앱이 하는 일")
            .setMessage(
                "• 폰이 잘못 눌려서 바뀐 설정(비행기 모드, 와이파이, 소리, 알림, 방해금지)을 원래대로 되돌려요.\n" +
                    "• 고치기 전에 말로 먼저 알려드리고, 화면의 큰 [중단] 버튼으로 언제든 멈출 수 있어요.\n" +
                    "• 문자·통화 내용, 사진, 연락처, 비밀번호는 보지 않아요.\n" +
                    "• 못 고치면 가족에게 연락해요. 고친 기록은 이 화면에서 볼 수 있어요.",
            )
            .setCancelable(false)
            .setPositiveButton("동의해요") { _, _ -> store.consented = true; render() }
            .setNegativeButton("동의하지 않아요") { _, _ -> finish() }
            .show()
    }

    private fun render() {
        content.removeAllViews()
        val c = this
        content.addView(Ui.text(c, "폰 도우미", 30f, bold = true))
        content.addView(Ui.text(c, "폰 설정이 잘못 바뀌면 원래대로 되돌려 드려요.", 20f))

        // --- 권한 ---
        val accOn = isAccessibilityOn()
        val policyOn = getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
        content.addView(Ui.text(c, "준비 상태", 24f, bold = true))
        content.addView(Ui.text(c, (if (accOn) "✔ " else "✘ ") + "접근성 서비스 (설정 화면 대신 누르기)"))
        if (!accOn) {
            content.addView(Ui.button(c, "앱 정보 열기 (제한된 설정 허용 먼저)") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            content.addView(Ui.button(c, "접근성 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        }
        content.addView(Ui.text(c, (if (policyOn) "✔ " else "✘ ") + "알림 정책 접근 (방해금지·소리)"))
        if (!policyOn) content.addView(Ui.button(c, "알림 정책 접근 설정 열기") { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) })

        // --- 스냅샷 ---
        content.addView(Ui.text(c, "기준 상태", 24f, bold = true))
        val snap = store.snapshot()
        if (snap == null) {
            content.addView(Ui.text(c, "아직 기준이 없어요. 폰이 정상일 때 아래 버튼을 눌러 주세요."))
        } else {
            content.addView(Ui.text(c, "찍은 시각: ${fmt(snap.takenAtMillis)}\n취침 시간: ${hm(snap.nightStartMin)} ~ ${hm(snap.nightEndMin)}"))
            val lines = SettingKey.entries.mapNotNull { k -> snap.values[k]?.let { "${k.label}: ${it.display()}" } }
            content.addView(Ui.text(c, lines.joinToString("\n"), 18f))
        }
        content.addView(Ui.button(c, "지금 이 상태를 기준으로 찍기") { confirmSnapshot() })

        // --- 점검 / 증상 ---
        content.addView(Ui.text(c, "점검", 24f, bold = true))
        content.addView(Ui.button(c, "지금 점검하기") { startCheck(Trigger.MANUAL, null) })
        content.addView(Ui.button(c, "폰이 이상해요", "#E65100") { askSymptom() })

        // --- 기록 ---
        content.addView(Ui.text(c, "고친 기록", 24f, bold = true))
        val log = store.log().take(20)
        if (log.isEmpty()) content.addView(Ui.text(c, "아직 기록이 없어요."))
        log.forEach { content.addView(Ui.text(c, "${fmt(it.timeMillis)}  ${it.text}", 18f)) }

        // --- 대기열 ---
        val queued = store.outbox().size
        if (queued > 0) {
            content.addView(Ui.text(c, "아직 못 보낸 알림 ${queued}건 (망이 돌아오면 다시 보내요)", 18f))
            content.addView(Ui.button(c, "지금 다시 보내기", "#546E7A") {
                AgentRuntime.flushOutboxAsync(this); toast("다시 보내는 중이에요")
            })
        }

        // --- 데모 / 개발 ---
        content.addView(Ui.text(c, "데모 설정", 24f, bold = true))
        val demo = Switch(c).apply {
            textSize = 20f
            isChecked = store.demoMode
            text = "데모용 임계값 단축"
            setOnCheckedChangeListener { _, on -> store.demoMode = on; render() }
        }
        content.addView(demo)
        // 장시간 통화 감지는 2단계. 1단계에서는 임계값을 저장·표시만 한다.
        content.addView(Ui.text(c, "장시간 통화 기준: ${Thresholds.of(store.demoMode).longCallMinutes}분 (감지는 2단계에서 동작)", 18f))
        if (BuildConfig.DEBUG) {
            content.addView(Ui.button(c, "장애 주입 메뉴 (데모·테스트용)", "#6A1B9A") {
                startActivity(Intent(this, FaultInjectionActivity::class.java))
            })
        }
        content.addView(Ui.text(c, "Liner 키 ${if (AppConfig.linerApiKey.isBlank()) "없음" else "있음"} · 텔레그램 ${if (AppConfig.telegramBotToken.isBlank() || AppConfig.telegramChatId.isBlank()) "없음" else "있음"}", 14f))
    }

    private fun confirmSnapshot() {
        val nightStart = EditText(this).apply { setText(hm(Snapshot.DEFAULT_NIGHT_START)); inputType = InputType.TYPE_CLASS_DATETIME; textSize = 22f }
        val nightEnd = EditText(this).apply { setText(hm(Snapshot.DEFAULT_NIGHT_END)); inputType = InputType.TYPE_CLASS_DATETIME; textSize = 22f }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 24), 0)
            addView(Ui.text(this@MainActivity, "폰이 정상인 상태인지 확인하세요. 부모님 취침 시간(시:분)"))
            addView(nightStart); addView(nightEnd)
        }
        AlertDialog.Builder(this)
            .setTitle("기준 찍기")
            .setView(box)
            .setPositiveButton("찍기") { _, _ ->
                val ns = parseHm(nightStart.text.toString()) ?: Snapshot.DEFAULT_NIGHT_START
                val ne = parseHm(nightEnd.text.toString()) ?: Snapshot.DEFAULT_NIGHT_END
                AgentRuntime.async {
                    // 알림 설정은 접근성으로 화면을 열어 읽는다 (deep)
                    val cur = AgentRuntime.reader.readAll(deep = true)
                    store.saveSnapshot(Snapshot.capture(cur, System.currentTimeMillis(), ns, ne))
                    store.appendLog("기준 상태를 새로 찍었어요")
                    runOnUiThread { render(); toast("기준을 저장했어요") }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askSymptom() {
        val input = EditText(this).apply { hint = "예: 문자가 안 와요"; textSize = 22f }
        AlertDialog.Builder(this)
            .setTitle("어떤 문제가 있어요?")
            .setView(input)
            .setPositiveButton("확인") { _, _ ->
                val words = input.text.toString()
                AgentRuntime.async {
                    // Liner 분류. 망·키가 없으면 OTHER → 같은 점검 흐름 (규칙 엔진만 동작)
                    val symptom = runCatching { AgentRuntime.liner.classify(words) }.getOrDefault(Symptom.OTHER)
                    runOnUiThread { toast("분류: ${symptom.label}") }
                    startCheck(Trigger.SYMPTOM, symptom)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startCheck(trigger: Trigger, symptom: Symptom?) {
        AgentRuntime.runCheckAsync(this, trigger, symptom) { outcome ->
            runOnUiThread {
                toast(
                    when (outcome) {
                        CheckOutcome.NoBaseline -> "먼저 기준을 찍어 주세요"
                        CheckOutcome.NoConsent -> "먼저 동의가 필요해요"
                        CheckOutcome.AlreadyRunning -> "이미 점검 중이에요"
                        is CheckOutcome.Done -> outcome.summary
                    },
                )
                render()
            }
        }
    }

    private fun isAccessibilityOn(): Boolean =
        getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun fmt(ms: Long) = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(ms))
    private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)
    private fun parseHm(s: String): Int? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").matchEntire(s) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }
}
