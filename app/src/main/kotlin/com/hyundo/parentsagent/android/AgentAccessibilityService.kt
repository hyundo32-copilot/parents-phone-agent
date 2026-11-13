package com.hyundo.parentsagent.android

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

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
            AgentRuntime.onUnlock(applicationContext)
        }
    }

    override fun onServiceConnected() {
        instance = this
        AgentRuntime.init(applicationContext)
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
        StatusBus.addListener(statusListener)
        registerNetworkCallback()
        Scheduler.scheduleAll(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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

    private fun findMasterSwitch(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        for (label in listOf("알림 허용", "알림 표시", "Allow notifications", "Show notifications")) {
            root.findAccessibilityNodeInfosByText(label)?.forEach { n ->
                (firstCheckable(n) ?: firstCheckable(n.parent))?.let { return it }
            }
        }
        return null
    }

    private fun firstCheckable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isCheckable) return n
        for (i in 0 until n.childCount) firstCheckable(n.getChild(i))?.let { return it }
        return null
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
    }
}
