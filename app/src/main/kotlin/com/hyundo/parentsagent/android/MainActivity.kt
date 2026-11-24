package com.hyundo.parentsagent.android

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.hyundo.parentsagent.BuildConfig
import com.hyundo.parentsagent.core.CheckSummary
import com.hyundo.parentsagent.core.LogPresentation
import com.hyundo.parentsagent.core.MainTab
import com.hyundo.parentsagent.core.ResultNote
import com.hyundo.parentsagent.core.ResultNotes
import com.hyundo.parentsagent.core.SpeechText
import com.hyundo.parentsagent.core.StatusCardState
import com.hyundo.parentsagent.core.SettingKey
import com.hyundo.parentsagent.core.Snapshot
import com.hyundo.parentsagent.core.StatusCard
import com.hyundo.parentsagent.core.StatusInput
import com.hyundo.parentsagent.core.Symptom
import com.hyundo.parentsagent.core.Thresholds
import java.text.SimpleDateFormat
import java.util.Date
import java.time.ZoneId
import java.util.Locale

class MainActivity : android.app.Activity() {
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    // 전체 기록 펼침 상태는 render()가 뷰를 다시 만들어도 유지한다. 저장은 안 해서 앱을 다시 켜면 닫힌 채 시작한다.
    private var logOpen = false
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    /** 배너 바로 아래 탭 줄. 선택이 바뀌면 [renderTabBar]가 칸을 다시 만든다. 고른 탭은 Store에 저장된다. */
    private lateinit var tabBar: LinearLayout
    private var tab = MainTab.DEFAULT
    private val statusListener: () -> Unit = { renderBanner() }
    // Announcer는 프로세스 공유라 화면이 shutdown 하지 않는다. 화면이 시작한 발화(읽어 주기·끝 음성)만 onPause에서 멈춘다.
    private var resumed = false
    private var ownSpeechId: String? = null
    /** 점검(증상 분류 포함)이 도는 동안 "점검하는 중" 표시. 화면이 다시 만들어지면 사라진다. */
    private var checking = false
    /** 가족 영역 버튼 바로 아래 피드백 (기준 저장·다시 보내기). 맨 위 결과 카드로는 쓰지 않는다. */
    private var familyNote: ResultNote? = null

    /** 관리용 Liner 키 입력·동작 확인 피드백. render()가 뷰를 다시 만들어도 유지한다. */
    private var linerKeyNote: String? = null
    private var linerKeyChecking = false

    private var stepPermissionAsked = false

    private val store get() = AgentRuntime.store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentRuntime.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(Ui.SCREEN_BG))
            // targetSdk 36은 엣지-투-엣지가 강제라 맨 위 뷰가 상태 표시줄 밑까지 그려진다. 위쪽(상태 표시줄) 인셋만 루트 위쪽 여백으로 준다.
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(v.paddingLeft, statusBarInset(insets), v.paddingRight, v.paddingBottom)
                insets
            }
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
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 4))
        }
        root.addView(tabBar)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 32))
        }
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll)
        tab = store.mainTab
        renderTabBar()
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun statusBarInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) insets.getInsets(WindowInsets.Type.statusBars()).top
        else insets.systemWindowInsetTop

    override fun onResume() {
        super.onResume()
        resumed = true
        StatusBus.addListener(statusListener)
        render()
        // 화면이 멈춰 있는 동안 점검이 끝나 idle()이 불려도 리스너가 없어 배너가 낡은 채 남는다. 돌아오면 배너를 다시 그린다.
        // render() 안에 넣지 말 것: renderBanner()가 배너가 없을 때 render()를 부르므로 무한 반복이 된다.
        renderBanner()
        if (!store.consented) askConsent() else requestStepPermissionOnce()
    }

    /** 안부 신호의 걸음수용 권한. 이 화면이 뜬 동안 한 번만 요청한다 (거절해도 안부 신호는 걸음수 없이 나간다). */
    private fun requestStepPermissionOnce() {
        if (stepPermissionAsked || StepCounterReader.hasPermission(this)) return
        stepPermissionAsked = true
        requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), REQ_ACTIVITY_RECOGNITION)
    }

    override fun onPause() {
        resumed = false
        AgentRuntime.announcer.stopUtterance(ownSpeechId)
        ownSpeechId = null
        StatusBus.removeListener(statusListener)
        super.onPause()
    }

    private fun renderTabBar() {
        tabBar.removeAllViews()
        MainTab.entries.forEach { t ->
            tabBar.addView(Ui.tabButton(this, t.label, t == tab) { selectTab(t) })
        }
    }

    private fun selectTab(t: MainTab) {
        if (t == tab) return
        if (tab == MainTab.FAMILY) familyNote = null
        tab = t
        store.mainTab = t
        renderTabBar()
        scroll.scrollTo(0, 0)
        render()
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
                    "• 가족에게 매일 보내는 안부 신호에 지난 하루 걸음수를 같이 알려요. 그래서 '신체 활동' 허용을 한 번 물어봐요.\n" +
                    "• 문자·통화 내용, 사진, 연락처, 비밀번호는 보지 않아요.\n" +
                    "• 못 고치면 가족에게 연락해요. 고친 기록은 이 화면에서 볼 수 있어요.",
            )
            .setCancelable(false)
            .setPositiveButton("동의해요") { _, _ -> store.consented = true; render(); requestStepPermissionOnce() }
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

        when (tab) {
            MainTab.PHONE_STATUS -> {
                // 폰 상태 탭(부모님용): 상태(+데모 띠) → 점검 중 안내 → 결과 → 권한 안내 → 큰 버튼 2개 → 할 일 안내 → 최근 고친 일 → 취침 시간
                renderStatusCard(accOn, policyOn, snap != null, pausedKeys.size)
                renderCheckingNotice()
                renderResultCard()
                renderPermissionNotice(accOn, policyOn)
                renderActions()
                renderTodoCards(snap != null, pausedKeys)
                renderRecent()
                renderSleepTime(snap)
            }
            MainTab.FAMILY -> {
                // 가족 관리 탭: 가족 영역(기준·다시 찍기·못 보낸 알림) → 관리용 카드(권한·기록 / 데모 / Liner 키), 모두 바로 보인다
                renderFamily(snap)
                renderAdmin(accOn, policyOn)
            }
        }
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
        content.addView(Ui.statusCard(this, state.level, state.title, state.sub) { speakStatus(state) })
        if (store.demoMode) content.addView(Ui.strip(this, "데모 모드 (기준 시간이 짧아요)"))
    }

    private fun renderCheckingNotice() {
        if (!checking) return
        content.addView(Ui.card(this, Ui.text(this, "점검하는 중이에요. 잠시만 기다려 주세요", 22f, bold = true)))
    }

    /** 결과 카드 한 장. 그 뒤에 예약·잠금 해제 점검이 상태 카드를 갱신했으면 이 카드는 낡았으니 지운다. */
    private fun renderResultCard() {
        val note = store.resultNote ?: return
        if (ResultNotes.isStale(note, store.lastResultMillis)) { store.clearResultNote(); return }
        val timeText = StatusCard.friendlyTime(note.atMillis, System.currentTimeMillis(), ZoneId.systemDefault())
        // [확인했어요]는 카드만 지운다. 상태 카드의 빨강(마지막 점검 결과)은 다음 점검이 성공해야 풀린다.
        content.addView(Ui.resultCard(this, note, timeText) { store.clearResultNote(); render() })
    }

    /** 상태 카드를 소리로 읽는다. 버튼으로만 시작한다. 실패하면 결과 카드에 안내를 남긴다. */
    private fun speakStatus(state: StatusCardState) {
        val text = SpeechText.forSpeech(SpeechText.statusCard(state, store.lastResultMillis, System.currentTimeMillis(), ZoneId.systemDefault()))
        speakOwned(text)
    }

    private fun speakOwned(text: String) {
        AgentRuntime.announcer.stopUtterance(ownSpeechId)
        ownSpeechId = AgentRuntime.announcer.speakOwned(text) { ok -> if (!ok) runOnUiThread { showTtsFailed() } }
    }

    private fun showTtsFailed() {
        val now = System.currentTimeMillis()
        val cur = store.resultNote?.takeIf { !ResultNotes.isStale(it, store.lastResultMillis) }
        store.saveResultNote(cur?.let { ResultNotes.withTtsFailed(it) } ?: ResultNotes.ttsFailed(now))
        render()
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
            card.addView(Ui.text(this, "가족이 폰이 정상일 때 기준을 찍어 줘야 해요. [가족 관리] 탭에서 해요."))
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

    /** 가족 영역: 기준 상태, 기준 다시 찍기, 못 보낸 알림. 탭 자체가 가족용이라 길게 누르기 없이 바로 보인다. */
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
                familyNote = ResultNotes.resendStarted(System.currentTimeMillis()); render()
                AgentRuntime.flushOutboxAsync(this) { remaining ->
                    runOnUiThread { familyNote = ResultNotes.resendDone(remaining, System.currentTimeMillis()); render() }
                }
            })
        }
        familyNote?.let { n ->
            body.addView(Ui.text(c, n.text, 18f, bold = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        }
        content.addView(Ui.card(c, body))
    }

    /** 관리용: 탭 자체가 가족용이라 접힘·길게 누르기 없이 카드로 바로 보인다 (전체 기록만 짧게 눌러 여는 접힘). */
    private fun renderAdmin(accOn: Boolean, policyOn: Boolean) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val c = this
        body.addView(Ui.text(c, "관리용", 22f, bold = true))

        // 권한 한 줄 (꺼져 있으면 위쪽 안내 카드에 이미 나온다)
        if (accOn && policyOn) body.addView(Ui.text(c, "✔ 접근성 켜짐 · ✔ 알림 정책 켜짐", 18f))

        // 전체 기록 (접힘, 짧게 눌러 열기). 원문 그대로, 바로 이어진 같은 줄만 묶는다.
        val logBody = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val rows = LogPresentation.adminRows(store.log(), 20)
        if (rows.isEmpty()) logBody.addView(Ui.text(c, "아직 기록이 없어요."))
        else {
            // 안드로이드 13+는 시스템이 복사 안내를 띄운다. 앱은 버튼 글자만 잠깐 바꾼다 (토스트·결과 카드는 쓰지 않는다).
            lateinit var copyBtn: android.widget.Button
            copyBtn = Ui.manageButton(c, "복사") {
                copyAdminLog()
                copyBtn.text = "복사했어요 ✓"
                copyBtn.postDelayed({ copyBtn.text = "복사" }, 2_000)
            }
            logBody.addView(copyBtn)
        }
        rows.forEach { logBody.addView(Ui.text(c, "${fmt(it.timeMillis)}  ${LogPresentation.formatCount(it.text, it.count)}", 18f)) }
        body.addView(
            Ui.collapsible(c, "▸ 전체 기록 (최근 20건)", "▾ 전체 기록 (최근 20건)", logOpen, longPressToOpen = false, onToggle = { logOpen = it }, body = logBody),
        )

        content.addView(Ui.card(c, body))

        // 데모 / 개발
        val demoBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        demoBody.addView(Ui.text(c, "데모 설정", 22f, bold = true))
        demoBody.addView(
            Switch(c).apply {
                textSize = 20f
                isChecked = store.demoMode
                text = "데모용 임계값 단축"
                setOnCheckedChangeListener { _, on -> store.demoMode = on; render() }
            },
        )
        // 장시간 통화 감지는 2단계. 1단계에서는 임계값을 저장·표시만 한다.
        demoBody.addView(Ui.text(c, "장시간 통화 기준: ${Thresholds.of(store.demoMode).longCallMinutes}분 (감지는 2단계에서 동작)", 18f))
        if (BuildConfig.DEBUG) {
            demoBody.addView(Ui.manageButton(c, "장애 주입 메뉴 (데모·테스트용)") {
                startActivity(Intent(this, FaultInjectionActivity::class.java))
            })
        }
        content.addView(Ui.card(c, demoBody))

        val linerBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Liner API 키 (앱 안에서 입력, 기기에만 저장한다. 빌드 때 주입된 키보다 우선한다)
        val effectiveKey = store.linerApiKey.ifBlank { AppConfig.linerApiKey }
        linerBody.addView(Ui.text(c, "Liner 키 ${if (effectiveKey.isBlank()) "없음" else "있음"} · 텔레그램 ${if (AppConfig.telegramBotToken.isBlank() || AppConfig.telegramChatId.isBlank()) "없음" else "있음"}", 14f))
        linerBody.addView(Ui.text(c, "Liner API 키", 22f, bold = true))
        val keyInput = EditText(c).apply {
            hint = "Liner API 키를 입력하세요"
            textSize = 18f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(store.linerApiKey)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(c, 8) }
        }
        linerBody.addView(keyInput)
        linerBody.addView(Ui.manageButton(c, "저장") {
            val key = keyInput.text.toString().trim()
            AgentRuntime.setLinerKey(key)
            linerKeyChecking = false
            linerKeyNote = if (key.isBlank()) "Liner 키를 지웠어요" else "Liner 키를 저장했어요. [동작 확인]을 눌러 확인해 주세요"
            render()
        })
        linerBody.addView(Ui.manageButton(c, "동작 확인") {
            if (linerKeyChecking) return@manageButton
            val key = keyInput.text.toString().trim()
            if (key.isBlank()) {
                linerKeyNote = "키를 먼저 입력해 주세요"
                render()
                return@manageButton
            }
            // 입력한 키를 저장하고, 그 키로 실제 분류 호출을 해본다
            AgentRuntime.setLinerKey(key)
            linerKeyChecking = true
            linerKeyNote = null
            render()
            AgentRuntime.async {
                val result = runCatching { AgentRuntime.liner.classify("문자가 안 와요") }
                runOnUiThread {
                    linerKeyChecking = false
                    linerKeyNote = result.fold(
                        onSuccess = { s -> "동작 확인됨: '${s.label}'로 분류됐어요" },
                        onFailure = { e -> "동작 확인 실패: ${linerTestError(e)}" },
                    )
                    render()
                }
            }
        })
        if (linerKeyChecking) linerBody.addView(Ui.text(c, "확인하는 중이에요…", 18f))
        linerKeyNote?.let { linerBody.addView(Ui.text(c, it, 18f, bold = true)) }

        content.addView(Ui.card(c, linerBody))
    }

    /** 멈춘 항목을 다시 켠다. 점검과 같은 실행 줄(executor)에서 해서 진행 중인 점검의 기록 저장과 엇갈리지 않게 한다. */
    private fun resumeAutoRestore(keys: List<SettingKey>) {
        AgentRuntime.async {
            val h = store.history()
            keys.forEach { h.resume(it) }
            store.saveHistory(h)
            store.appendLog("자동 복원을 다시 켰어요: " + keys.joinToString(", ") { it.label })
            store.saveResultNote(ResultNotes.autoFixResumed(System.currentTimeMillis()))
            runOnUiThread { render() }
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
                    runOnUiThread { familyNote = ResultNotes.baselineSaved(System.currentTimeMillis()); render() }
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
                checking = true; render() // 분류가 망 상태에 따라 수 초 걸릴 수 있어 먼저 표시한다
                AgentRuntime.async {
                    // Liner 분류. 망·키가 없으면 OTHER → 같은 점검 흐름 (규칙 엔진만 동작). 분류 결과는 관리용 기록으로만 남긴다.
                    val symptom = runCatching { AgentRuntime.liner.classify(words) }.getOrDefault(Symptom.OTHER)
                    store.appendLog("증상 분류: ${symptom.label}")
                    startCheck(Trigger.SYMPTOM, symptom)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startCheck(trigger: Trigger, symptom: Symptom?) {
        runOnUiThread { checking = true; render() }
        AgentRuntime.runCheckAsync(this, trigger, symptom) { outcome ->
            val now = System.currentTimeMillis()
            // 카드는 화면이 아니라 Store에 저장한다: 점검 중에 화면이 다시 만들어져도 새 화면이 읽어 간다.
            // 이미 점검 중(AlreadyRunning)은 카드를 만들지 않는다 (진행 중인 점검은 이 화면 콜백이 없어 카드가 영영 남는다).
            // 이름·내부 값이 든 outcome.summary는 부모님 화면에 쓰지 않는다.
            val detail = (outcome as? CheckOutcome.Done)?.detail
            val note: ResultNote? = when (outcome) {
                CheckOutcome.NoBaseline -> ResultNotes.noBaseline(now)
                CheckOutcome.NoConsent -> ResultNotes.noConsent(now)
                CheckOutcome.AlreadyRunning -> null
                is CheckOutcome.Done -> detail?.let { ResultNotes.forCheck(it, now) }
            }
            if (note != null) store.saveResultNote(note)
            runOnUiThread {
                checking = false
                if (note != null && detail != null) speakCheckEnd(detail, note)
                render()
            }
        }
    }

    /**
     * 점검 끝 음성: 부모님이 직접 시작한 점검(이 함수는 [startCheck]에서만 불린다)이고 앱이 앞에 있을 때만.
     * 항목별 안내와 겹치지 않게 [ResultNotes.shouldSpeakEnd]인 경우만 말한다.
     * TalkBack이 켜져 있으면 자동 음성 대신 결과 카드의 liveRegion이 읽는다.
     */
    private fun speakCheckEnd(detail: CheckSummary, note: ResultNote) {
        if (!resumed || Announcer.isTalkBackOn(this) || !ResultNotes.shouldSpeakEnd(detail)) return
        speakOwned(SpeechText.forSpeech(note.text))
    }

    private fun isAccessibilityOn(): Boolean =
        getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }

    /** 동작 확인 실패를 사람이 읽을 말로 바꾼다. 키·URL은 넣지 않는다. */
    private fun linerTestError(e: Throwable): String = when (e) {
        is com.hyundo.parentsagent.net.HttpStatusException -> when (e.code) {
            401 -> "키가 틀렸어요 (HTTP 401). 키를 다시 확인해 주세요"
            429 -> "호출이 너무 많아요. 잠시 뒤 다시 해 주세요"
            else -> "Liner가 거부했어요 (HTTP ${e.code})"
        }
        is java.io.IOException -> "망에 연결할 수 없어요. 와이파이·데이터를 확인해 주세요"
        else -> "오류: ${e.message}"
    }

    private fun copyAdminLog() {
        val text = LogPresentation.adminCopyText(store.log(), 20)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("전체 기록", text))
    }

    private fun fmt(ms: Long) = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(ms))
    private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private companion object {
        const val REQ_ACTIVITY_RECOGNITION = 1001
    }
}
