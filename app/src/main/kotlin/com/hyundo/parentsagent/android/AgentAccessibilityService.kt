package com.hyundo.parentsagent.android

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hyundo.parentsagent.core.AppReturn
import com.hyundo.parentsagent.core.ReturnReport
import com.hyundo.parentsagent.core.ReturnResult
import com.hyundo.parentsagent.core.WindowSnap
import com.hyundo.parentsagent.core.SwitchAttempt
import com.hyundo.parentsagent.core.SwitchFinder
import com.hyundo.parentsagent.core.UiNode
import com.hyundo.parentsagent.core.SwitchOutcome

/** 빠른 설정 타일. labels 는 One UI 타일 표시 이름(텍스트 또는 접근성 설명)에 들어 있는 문구. */
enum class Tile(val labels: List<String>) {
    AIRPLANE(listOf("비행기 모드", "Airplane mode")),
    WIFI(listOf("Wi-Fi", "와이파이")),
    MOBILE_DATA(listOf("모바일 데이터", "Mobile data")),
    DND(listOf("방해 금지", "Do not disturb")),
}

/**
 * 접근성 탭 수단 (설계 §8-2). 좌표가 아니라 텍스트로 찾고, 못 찾으면 바로 중단하고 false를 돌려준다.
 * 이 서비스가 켜져 있는 동안 앱 프로세스가 유지되므로 주기 점검·잠금 해제 감지도 여기서 이어받는다.
 * 모든 public 작업 메서드는 백그라운드 스레드에서 호출한다 (대기 포함).
 */
class AgentAccessibilityService : AccessibilityService() {

    private var overlay: View? = null
    private val statusListener: () -> Unit = { updateOverlay() }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // ACTION_USER_PRESENT 는 매니페스트로 못 받으므로 상주 프로세스인 이 서비스에서 받는다 (설계 §4)
            // 다른 액션이 섞여 들어와도 잠금 해제로 치지 않는다 (EXPORTED 리시버라 방어적으로 확인)
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            AgentRuntime.onUnlock(applicationContext)
        }
    }

    override fun onServiceConnected() {
        instance = this
        AgentRuntime.init(applicationContext)
        // RECEIVER_NOT_EXPORTED 로 등록하면 안 된다. ACTION_USER_PRESENT 는 system_server(uid 1000)가 아니라
        // SystemUI(일반 앱 uid)의 KeyguardViewMediator가 보내서, 내보내지 않은 리시버에는 "Exported Denial"로 전달되지 않는다.
        // USER_PRESENT 는 보호된 브로드캐스트라 다른 앱이 위조해 보낼 수 없으므로 EXPORTED 로 열어도 안전하다.
        val registered = runCatching {
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED)
        }
        AgentRuntime.store.appendLog(
            if (registered.isSuccess) "접근성 서비스 연결: 잠금 해제 감지 등록함"
            else "접근성 서비스 연결: 잠금 해제 감지 등록 실패 (${registered.exceptionOrNull()?.javaClass?.simpleName})",
        )
        StatusBus.addListener(statusListener)
        registerNetworkCallback()
        Scheduler.scheduleAll(applicationContext)
    }

    /** 마지막 창 변화 이벤트 시각 (uptime). 메인 스레드가 쓰고 점검 스레드가 읽는다. */
    @Volatile private var lastWindowEventAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            lastWindowEventAt = SystemClock.uptimeMillis()
        }
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        // onUnbind 와 onDestroy 가 둘 다 불리므로 연결된 인스턴스일 때 한 번만 남긴다
        if (instance === this) runCatching { AgentRuntime.store.appendLog("접근성 서비스 연결 끊김: 잠금 해제 감지 해제") }
        if (instance === this) instance = null
        runCatching { unregisterReceiver(unlockReceiver) }
        StatusBus.removeListener(statusListener)
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        removeOverlay()
    }

    /** 망이 돌아오면 대기열에 쌓인 에스컬레이션을 다시 보낸다 (설계 §9). */
    private fun registerNetworkCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = AgentRuntime.flushOutboxAsync(applicationContext)

            // Doze로 막혔던 앱 네트워크가 풀릴 때는 onAvailable이 아니라 이게 불린다 (API 29+)
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (!blocked) AgentRuntime.flushOutboxAsync(applicationContext)
            }
        }
        networkCallback = cb
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) }
    }

    // ---- 복원 중 표시 + 큰 중단 버튼 (설계 §2 원칙 5) ----

    private fun updateOverlay() {
        val text = StatusBus.fixingText
        if (text == null) removeOverlay() else showOverlay(text)
    }

    private fun showOverlay(text: String) {
        removeOverlay()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2FFF3CD"))
            setPadding(32, 24, 32, 24)
        }
        box.addView(TextView(this).apply {
            this.text = "지금 고치는 중이에요\n$text"
            textSize = 24f
            setTextColor(Color.BLACK)
        })
        box.addView(Button(this).apply {
            this.text = "중단"
            textSize = 30f
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            minimumHeight = 180
            setOnClickListener { StopController.request() }
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        // 실기 확인 필요: 빠른 설정 패널이 열린 동안 오버레이가 보이고 중단 버튼이 눌리는지
        runCatching { getSystemService(WindowManager::class.java).addView(box, lp) }
        overlay = box
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
    }

    // ---- 빠른 설정 타일 탭 ----

    /**
     * 빠른 설정 패널을 열어 [tile]을 한 번 눌러 상태를 바꾸고, [isDone]이 true가 되는지 확인한다.
     * 실기 확인 필요: One UI 9.0 빠른 설정의 타일 노드 텍스트와 펼침 상태. 접힌 패널에 타일이 안 보일 수 있음.
     */
    fun toggleTile(tile: Tile, isDone: () -> Boolean): Boolean {
        if (isDone()) return true
        try {
            if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) return false
            val node = waitFor(timeoutMs = 3_000) { findTile(tile) } ?: return false
            if (!clickNode(node)) return false
            return waitFor(timeoutMs = 3_000) { if (isDone()) true else null } == true
        } finally {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun findTile(tile: Tile): AccessibilityNodeInfo? {
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            windows.forEach { w -> w.root?.let { add(it) } }
        }
        for (root in roots) for (label in tile.labels) {
            root.findAccessibilityNodeInfosByText(label)?.firstOrNull { clickableAncestor(it) != null }?.let { return it }
        }
        return null
    }

    // ---- 설정 화면 스위치 (빠른 설정 타일이 안 될 때의 차선책) ----

    /**
     * [intent]로 설정 화면을 열어 [labels] 문구가 있는 스위치를 [want] 상태로 바꾼다.
     * One UI 9.0 실기(2026-11-14)에서 빠른 설정 타일 탭이 안 돼 추가했다.
     * 결과의 [SwitchAttempt.outcome]이 어디서 막혔는지 알려 준다 (스위치 못 찾음 / 못 누름 / 눌렀지만 안 바뀜).
     */
    fun setSwitchInSettings(intent: Intent, labels: List<String>, want: Boolean, singlePurpose: Boolean = false): SwitchAttempt {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            // 창 전환 직후엔 트리가 비어 있을 수 있어 waitFor가 매번 루트를 다시 읽는다.
            val found = waitFor(timeoutMs = 6_000) { SwitchFinder.find(rootOf(), labels, singlePurpose) }
                ?: return SwitchAttempt(SwitchOutcome.NOT_FOUND, SwitchFinder.describe(rootOf(), labels))
            val strategy = found.strategy
            if (found.checked == want) return SwitchAttempt(SwitchOutcome.ALREADY, strategy = strategy)
            val target = (found.node as AccNode).raw
            if (!clickNode(target)) return SwitchAttempt(SwitchOutcome.CLICK_FAILED, strategy = strategy)
            if (found.checked == null) {
                // 행 클릭으로 상태가 화면에 안 나오는 경우: 나중에 상태가 보이면 확인하고, 아니면 복원 후 재읽기에 맡긴다.
                val seen = waitFor(timeoutMs = 2_000) { SwitchFinder.find(rootOf(), labels, singlePurpose)?.checked }
                val outcome = when (seen) {
                    null -> SwitchOutcome.CLICKED_UNVERIFIED
                    want -> SwitchOutcome.CHANGED
                    else -> SwitchOutcome.NOT_CHANGED
                }
                return SwitchAttempt(outcome, strategy = strategy)
            }
            val changed = waitFor(timeoutMs = 3_000) {
                SwitchFinder.find(rootOf(), labels, singlePurpose)?.takeIf { it.checked == want }
            } != null
            return SwitchAttempt(if (changed) SwitchOutcome.CHANGED else SwitchOutcome.NOT_CHANGED, strategy = strategy)
        } finally {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun rootOf(): UiNode? = rootInActiveWindow?.let { AccNode(it) }

    private fun findSwitch(labels: List<String>): AccessibilityNodeInfo? =
        (SwitchFinder.find(rootOf(), labels, singlePurpose = false)?.node as AccNode?)?.raw

    /** 안드로이드 노드를 [UiNode]로 감싼다. 자식·부모는 필요할 때 읽는다. */
    private class AccNode(val raw: AccessibilityNodeInfo) : UiNode {
        override val text get() = raw.text?.toString()
        override val desc get() = raw.contentDescription?.toString()
        override val isCheckable get() = raw.isCheckable
        override val isClickable get() = raw.isClickable
        override val isChecked get() = raw.isChecked
        override val children get() = (0 until raw.childCount).mapNotNull { raw.getChild(it)?.let(::AccNode) }
        override val parent get() = raw.parent?.let(::AccNode)
    }

    // ---- 앱 알림 설정 화면 ----

    /** 앱 알림 설정 화면의 "알림 허용" 스위치 상태. 못 읽으면 null. 실기 확인 필요(삼성 화면 구조). */
    fun readAppNotifications(pkg: String): Boolean? {
        openAppNotificationSettings(pkg)
        try {
            val sw = waitFor(timeoutMs = 4_000) { findMasterSwitch() } ?: return null
            return sw.isChecked
        } finally {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /** 앱 알림 설정 화면에서 "알림 허용" 스위치를 원하는 상태로. 실기 확인 필요. */
    fun setAppNotifications(pkg: String, on: Boolean): Boolean {
        openAppNotificationSettings(pkg)
        try {
            val sw = waitFor(timeoutMs = 4_000) { findMasterSwitch() } ?: return false
            if (sw.isChecked == on) return true
            if (!clickNode(sw)) return false
            val again = waitFor(timeoutMs = 2_000) { findMasterSwitch()?.takeIf { it.isChecked == on } }
            return again != null
        } finally {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun openAppNotificationSettings(pkg: String) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun findMasterSwitch(): AccessibilityNodeInfo? =
        findSwitch(listOf("알림 허용", "알림 표시", "Allow notifications", "Show notifications"))

    // ---- 점검 뒤 앱으로 복귀 ----

    /**
     * 각 도우미의 뒤로 가기 한 번이 모자랐을 때를 위한 안전망. 우리가 연 시스템 화면이면 뒤로 가기를 눌러 앱으로 돌아오고,
     * 이미 앱이거나 다른 앱이면 건드리지 않는다. 백그라운드 스레드에서 부른다 (대기 포함, 최대 5회).
     */
    fun returnToApp(): ReturnReport {
        // 도우미들(readAppNotifications 등)은 finally에서 뒤로 가기를 누르고 기다리지 않고 바로 돌아온다.
        // 그 직후 창 목록에는 닫히는 중인 설정 창이 아직 맨 위로 남아 있어서, 그걸 보고 뒤로 가기를 또 누르면
        // 앱 화면이 닫혀 홈 화면으로 간다. 창 변화가 가라앉은 뒤에 판정한다.
        waitWindowsQuiet()
        return AppReturn.run(
            appPackage = packageName,
            readWindows = { readWindowsTopFirst() },
            // 우리가 누른 뒤로 가기도 전환이 끝날 때까지 기다린다 (중간 상태를 "바뀜"으로 보고 또 누르지 않게)
            pressBack = { performGlobalAction(GLOBAL_ACTION_BACK); waitWindowsQuiet() },
            sleep = { Thread.sleep(it) },
            homePackages = homePackages(),
            reopenApp = { reopenApp() },
        )
    }

    /**
     * 창 변화 이벤트가 [quietMs] 동안 없을 때까지 기다린다 (최대 [maxMs]). 이벤트가 notificationTimeout(200ms)만큼
     * 늦게 올 수 있어서 부른 시점부터도 최소 [quietMs]는 기다린다.
     */
    private fun waitWindowsQuiet(quietMs: Long = 500, maxMs: Long = 3_000) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - start >= maxMs) return
            if (now - maxOf(start, lastWindowEventAt) >= quietMs) return
            Thread.sleep(100)
        }
    }

    /** 이 폰의 홈 화면 앱. 못 찾을 때를 대비해 흔한 런처도 넣는다. */
    private fun homePackages(): Set<String> {
        val found = runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
            ).mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())
        // 설정 앱은 폴백 홈 액티비티(FallbackHome)를 갖고 있어 목록에 끼는데, 설정은 뒤로 가기 대상이라 뺀다
        return (found + KNOWN_LAUNCHERS).toSet() - AppReturn.SYSTEM_PACKAGES - packageName
    }

    /** 앱 아이콘을 누른 것과 같은 인텐트로 앱을 앞으로 가져온다. 접근성 서비스는 백그라운드에서 액티비티를 열 수 있다. */
    private fun reopenApp() {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(i)
    }

    /**
     * 화면에 쌓인 창을 맨 위부터. 활성 창은 빠른 설정 패널이 아닐 수 있어서 (findTile과 같은 이유) 창 목록을 본다.
     * 창 목록을 못 얻으면 활성 창 하나로 대신한다.
     */
    private fun readWindowsTopFirst(): List<WindowSnap> {
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val snaps = runCatching {
            windows.sortedByDescending { it.layer }.map { w ->
                val r = Rect().also { w.getBoundsInScreen(it) }
                WindowSnap(
                    pkg = w.root?.packageName?.toString(),
                    heightPercent = (r.height() * 100 / screenH),
                    ignorable = w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                )
            }
        }.getOrDefault(emptyList())
        if (snaps.isNotEmpty()) return snaps
        return listOfNotNull(rootInActiveWindow?.packageName?.toString()?.let { WindowSnap(it) })
    }

    // ---- 공통 ----

    private fun clickableAncestor(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean =
        clickableAncestor(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    /** [probe]가 null이 아닌 값을 낼 때까지 폴링. 부모님이 중단을 누르면 바로 포기한다. */
    private fun <T> waitFor(timeoutMs: Long, probe: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (StopController.requested) return null
            probe()?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null

        private val KNOWN_LAUNCHERS = setOf(
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
        )
    }
}

/** 접근성 서비스가 꺼져 있으면 돌아올 수단이 없으므로 아무것도 안 하고, 그 사실을 결과로 남긴다 (조용히 넘기지 않는다). */
object AccessibilityAppReturner : AppReturner {
    override fun returnToApp() { returnToAppWithReport() }

    override fun returnToAppWithReport(): ReturnReport =
        AgentAccessibilityService.instance?.returnToApp() ?: ReturnReport(ReturnResult.NO_SERVICE, 0, null)
}
