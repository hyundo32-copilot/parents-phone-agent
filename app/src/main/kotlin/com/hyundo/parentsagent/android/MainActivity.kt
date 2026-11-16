package com.hyundo.parentsagent.android

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.hyundo.parentsagent.BuildConfig
import com.hyundo.parentsagent.core.LogPresentation
import com.hyundo.parentsagent.core.SettingKey
import com.hyundo.parentsagent.core.Snapshot
import com.hyundo.parentsagent.core.StatusCard
import com.hyundo.parentsagent.core.StatusInput
import com.hyundo.parentsagent.core.Symptom
import com.hyundo.parentsagent.core.Thresholds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : android.app.Activity() {
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    // 펼침 상태는 render()가 뷰를 다시 만들어도 유지한다. 저장은 안 해서 앱을 다시 켜면 닫힌 채 시작한다 (데모 모드면 펼친 채).
    private var adminOpen = false
    private var manageOpen = false
    private var logOpen = false
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private val statusListener: () -> Unit = { renderBanner() }

    private val store get() = AgentRuntime.store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentRuntime.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(Ui.SCREEN_BG))
        }
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF3CD"))
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12))
            visibility = View.GONE
        }
        bannerText = Ui.text(this, "", 24f, bold = true)
        banner.addView(bannerText)
        banner.addView(Ui.button(this, "중단", Ui.BAD) { StopController.request() }.apply { minimumHeight = Ui.dp(this@MainActivity, 96) })
        root.addView(banner)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 32))
        }
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll)
        adminOpen = store.demoMode
        manageOpen = store.demoMode
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
        val scrollY = scroll.scrollY
        content.removeAllViews()
        val accOn = isAccessibilityOn()
        val policyOn = getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
        val snap = store.snapshot()
        val pausedKeys = store.history().pausedKeys()

        // 부모님 영역(카드별): 상태 → (데모 띠) → 권한 안내 → 큰 버튼 2개 → 할 일 안내 → 최근 고친 일 → 취침 시간
        renderStatusCard(accOn, policyOn, snap != null, pausedKeys.size)
        renderPermissionNotice(accOn, policyOn)
        renderActions()
        renderTodoCards(snap != null, pausedKeys)
        renderRecent()
        renderSleepTime(snap)
        // 가족·관리용 (각각 카드 안의 접힘 영역)
        content.addView(Ui.divider(this, "아래는 가족이 관리하는 부분이에요"))
        renderFamily(snap)
        renderAdmin(accOn, policyOn)
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun renderStatusCard(accOn: Boolean, policyOn: Boolean, hasBaseline: Boolean, pausedCount: Int) {
        val state = StatusCard.evaluate(
            StatusInput(
                accessibilityOn = accOn, notificationPolicyOn = policyOn, hasBaseline = hasBaseline,
                pausedCount = pausedCount, lastCheckMillis = store.lastResultMillis,
                lastCheckGrade = store.lastResultGrade, queuedCount = store.outbox().size,
                nowMillis = System.currentTimeMillis(),
            ),
        )
        content.addView(Ui.text(this, "폰 도우미", 16f).apply { setTextColor(Color.parseColor("#546E7A")) })
        content.addView(Ui.statusCard(this, state.level, state.title, state.sub))
        if (store.demoMode) content.addView(Ui.strip(this, "데모 모드 (기준 시간이 짧아요)"))
    }

    /** 접근성이 꺼지면 앱이 아무것도 못 고치므로 상태 카드 바로 아래 가장 먼저 보이게 한다. 켜져 있으면 그리지 않는다. */
    private fun renderPermissionNotice(accOn: Boolean, policyOn: Boolean) {
        if (accOn && policyOn) return
        val card = Ui.card(this)
        card.addView(Ui.text(this, "준비가 덜 됐어요. 가족에게 보여 주세요", 22f, bold = true))
        if (!accOn) {
            card.addView(Ui.text(this, "✘ 접근성 서비스가 꺼져 있어요 (설정 화면 대신 누르기)"))
            card.addView(Ui.button(this, "앱 정보 열기 (제한된 설정 허용 먼저)") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            card.addView(Ui.button(this, "접근성 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        }
        if (!policyOn) {
            card.addView(Ui.text(this, "✘ 알림 정책 접근이 꺼져 있어요 (방해금지·소리)"))
            card.addView(Ui.button(this, "알림 정책 접근 설정 열기") { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) })
        }
        content.addView(card)
    }

    private fun renderActions() {
        content.addView(
            Ui.card(
                this,
                Ui.tile(this, "🔍", "지금 점검하기", "설정이 괜찮은지 봐요") { startCheck(Trigger.MANUAL, null) },
                Ui.tile(this, "🆘", "폰이 이상해요", "가족에게 바로 알려요") { askSymptom() },
            ),
        )
    }

    /** 할 일이 있을 때만 나오는 안내. 기준 찍기는 가족 몫이라 부모님에게는 안내만 한다. */
    private fun renderTodoCards(hasBaseline: Boolean, pausedKeys: Collection<SettingKey>) {
        if (!hasBaseline) {
            val card = Ui.card(this)
            card.addView(Ui.text(this, "아직 기준이 없어요", 22f, bold = true))
            card.addView(Ui.text(this, "가족이 폰이 정상일 때 기준을 찍어 줘야 해요. 아래 [가족·관리용]에서 해요."))
            content.addView(card)
        }
        if (pausedKeys.isNotEmpty()) {
            val card = Ui.card(this)
            card.addView(Ui.text(this, "잠깐 쉬고 있는 항목이 있어요", 22f, bold = true))
            card.addView(Ui.text(this, "같은 이탈이 3일 연속 반복돼 자동 복원이 멈췄어요. 원인을 확인한 뒤 다시 켜 주세요.", 18f))
            pausedKeys.forEach { k ->
                card.addView(Ui.manageButton(this, "${k.label} 다시 자동 복원") { resumeAutoRestore(listOf(k)) })
            }
            content.addView(card)
        }
    }

    private fun renderRecent() {
        val card = Ui.card(this)
        card.addView(Ui.text(this, "최근 고친 일", 24f, bold = true))
        val rows = LogPresentation.parentRows(store.log(), 5)
        if (rows.isEmpty()) card.addView(Ui.text(this, "아직 고친 일이 없어요"))
        val now = System.currentTimeMillis()
        rows.forEach { card.addView(Ui.logRowView(this, it, now)) }
        content.addView(card)
    }

    /** 부모님이 보는 취침 시간. 기준을 찍은 뒤에만 나온다. */
    private fun renderSleepTime(snap: Snapshot?) {
        if (snap == null) return
        val card = Ui.card(this)
        card.addView(Ui.text(this, "취침 시간", 24f, bold = true))
        card.addView(Ui.text(this, "${hm(snap.nightStartMin)} ~ ${hm(snap.nightEndMin)}"))
        content.addView(card)
    }

    /** 가족 영역: 기준 상태, 기준 다시 찍기, 못 보낸 알림. 길게 눌러야 열린다. */
    private fun renderFamily(snap: Snapshot?) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val c = this
        body.addView(Ui.text(c, "정상일 때 모습 (기준)", 22f, bold = true))
        if (snap == null) {
            body.addView(Ui.text(c, "아직 기준이 없어요. 폰이 정상일 때 아래 버튼을 눌러 주세요."))
        } else {
            body.addView(Ui.text(c, "찍은 시각: ${fmt(snap.takenAtMillis)}"))
            val lines = SettingKey.entries.mapNotNull { k -> snap.values[k]?.let { "${k.label}: ${it.display()}" } }
            body.addView(Ui.text(c, lines.joinToString("\n"), 18f))
        }
        body.addView(Ui.manageButton(c, "기준 다시 찍기 (가족이 할 일)") { confirmSnapshot() })

        val queued = store.outbox().size
        if (queued > 0) {
            body.addView(Ui.text(c, "아직 못 보낸 알림 ${queued}건 (망이 돌아오면 다시 보내요)", 18f))
            body.addView(Ui.manageButton(c, "지금 다시 보내기") {
                AgentRuntime.flushOutboxAsync(this); toast("다시 보내는 중이에요")
            })
        }
        content.addView(
            Ui.card(
                c,
                Ui.collapsible(
                    c, "▸ 가족용 (길게 눌러서 열기)", "▾ 가족용 (눌러서 닫기)", adminOpen,
                    longPressToOpen = true, onToggle = { adminOpen = it }, body = body,
                ),
            ),
        )
    }

    private fun renderAdmin(accOn: Boolean, policyOn: Boolean) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val c = this

        // 권한 한 줄 (꺼져 있으면 위쪽 안내 카드에 이미 나온다)
        if (accOn && policyOn) body.addView(Ui.text(c, "✔ 접근성 켜짐 · ✔ 알림 정책 켜짐", 18f))

        // 전체 기록 (접힘, 짧게 눌러 열기). 원문 그대로, 바로 이어진 같은 줄만 묶는다.
        val logBody = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val rows = LogPresentation.adminRows(store.log(), 20)
        if (rows.isEmpty()) logBody.addView(Ui.text(c, "아직 기록이 없어요."))
        rows.forEach { logBody.addView(Ui.text(c, "${fmt(it.timeMillis)}  ${LogPresentation.formatCount(it.text, it.count)}", 18f)) }
        body.addView(
            Ui.collapsible(c, "▸ 전체 기록 (최근 20건)", "▾ 전체 기록 (최근 20건)", logOpen, longPressToOpen = false, onToggle = { logOpen = it }, body = logBody),
        )

        // 데모 / 개발
        body.addView(Ui.text(c, "데모 설정", 22f, bold = true))
        body.addView(
            Switch(c).apply {
                textSize = 20f
                isChecked = store.demoMode
                text = "데모용 임계값 단축"
                setOnCheckedChangeListener { _, on -> store.demoMode = on; render() }
            },
        )
        // 장시간 통화 감지는 2단계. 1단계에서는 임계값을 저장·표시만 한다.
        body.addView(Ui.text(c, "장시간 통화 기준: ${Thresholds.of(store.demoMode).longCallMinutes}분 (감지는 2단계에서 동작)", 18f))
        if (BuildConfig.DEBUG) {
            body.addView(Ui.manageButton(c, "장애 주입 메뉴 (데모·테스트용)") {
                startActivity(Intent(this, FaultInjectionActivity::class.java))
            })
        }
        body.addView(Ui.text(c, "Liner 키 ${if (AppConfig.linerApiKey.isBlank()) "없음" else "있음"} · 텔레그램 ${if (AppConfig.telegramBotToken.isBlank() || AppConfig.telegramChatId.isBlank()) "없음" else "있음"}", 14f))

        content.addView(
            Ui.card(
                c,
                Ui.collapsible(
                    c, "▸ 관리용 (길게 눌러서 열기)", "▾ 관리용 (눌러서 닫기)", manageOpen,
                    longPressToOpen = true, onToggle = { manageOpen = it }, body = body,
                ),
            ),
        )
    }

    /** 멈춘 항목을 다시 켠다. 점검과 같은 실행 줄(executor)에서 해서 진행 중인 점검의 기록 저장과 엇갈리지 않게 한다. */
    private fun resumeAutoRestore(keys: List<SettingKey>) {
        AgentRuntime.async {
            val h = store.history()
            keys.forEach { h.resume(it) }
            store.saveHistory(h)
            store.appendLog("자동 복원을 다시 켰어요: " + keys.joinToString(", ") { it.label })
            runOnUiThread { render(); toast("다시 자동으로 고칠게요") }
        }
    }

    private fun confirmSnapshot() {
        // 취침 시간은 시간 선택기로 고른다 (글로 쓰다 형식이 틀리면 조용히 기본값이 저장되던 문제가 없어진다)
        var ns = Snapshot.DEFAULT_NIGHT_START
        var ne = Snapshot.DEFAULT_NIGHT_END
        fun pickerButton(label: String, get: () -> Int, set: (Int) -> Unit): android.widget.Button {
            lateinit var b: android.widget.Button
            b = Ui.manageButton(this, "$label ${hm(get())}") {
                android.app.TimePickerDialog(this, { _, h, m -> set(h * 60 + m); b.text = "$label ${hm(get())}" }, get() / 60, get() % 60, true).show()
            }
            return b
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 24), 0)
            addView(Ui.text(this@MainActivity, "폰이 정상인 상태인지 확인하세요. 부모님 취침 시간을 눌러서 골라 주세요."))
            addView(pickerButton("잠자는 시간", { ns }, { ns = it }))
            addView(pickerButton("일어나는 시간", { ne }, { ne = it }))
        }
        AlertDialog.Builder(this)
            .setTitle("기준 찍기")
            .setView(box)
            .setPositiveButton("찍기") { _, _ ->
                AgentRuntime.async {
                    // 알림 설정은 접근성으로 화면을 열어 읽는다 (deep)
                    val cur = AgentRuntime.reader.readAll(deep = true)
                    store.saveSnapshot(Snapshot.capture(cur, System.currentTimeMillis(), ns, ne))
                    // 기준을 새로 찍었으면 이전 이탈 기록은 의미가 없다 → 멈춤 전부 해제
                    val h = store.history()
                    h.resumeAll()
                    store.saveHistory(h)
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
}
