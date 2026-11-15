package com.hyundo.parentsagent.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.hyundo.parentsagent.core.Symptom
import com.hyundo.parentsagent.net.EscalationSender
import com.hyundo.parentsagent.net.HeartbeatSender
import com.hyundo.parentsagent.net.LinerClient
import com.hyundo.parentsagent.net.TelegramClient
import java.time.LocalDateTime
import java.util.concurrent.Executors

/** 프로세스 전역 의존성 묶음. 접근성 서비스가 켜져 있는 동안 프로세스가 유지된다. */
object AgentRuntime {
    private val executor = Executors.newSingleThreadExecutor()
    private var initialized = false

    lateinit var store: Store; private set
    lateinit var reader: SettingsReader; private set
    lateinit var announcer: Announcer; private set
    lateinit var liner: LinerClient; private set
    lateinit var sender: EscalationSender; private set
    lateinit var runner: CheckRunner; private set
    lateinit var heartbeat: HeartbeatSender; private set

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        store = Store(app)
        reader = SettingsReader(app)
        announcer = Announcer(app)
        liner = LinerClient(AppConfig.linerApiKey)
        val telegram = TelegramClient(AppConfig.telegramBotToken, AppConfig.telegramChatId)
        sender = EscalationSender(
            liner.takeIf { it.configured }, telegram, store.outbox(),
            onPermanentError = { store.appendLog(it) },
            persistOutbox = { store.saveOutbox(it) },
        )
        heartbeat = HeartbeatSender(
            telegram, Scheduler.HEARTBEAT_HOUR,
            { store.lastHeartbeatSlotDate }, { store.lastHeartbeatSlotDate = it },
            isHealthy = { isAccessibilityEnabled(app) },
        )
        runner = CheckRunner(
            store, reader, AndroidScreenGate(app), { deep -> Restorer(app, reader, deep) },
            sender, announcer, StatusBus, StopController,
        )
        initialized = true
    }

    /**
     * 접근성 서비스가 켜져 있는지. 꺼져 있으면 앱은 아무것도 복원하지 못하므로 안부 신호를 내보내지 않는다.
     * 프로세스가 먼저 떠서 서비스 연결 전일 수 있어 설정의 활성 목록으로 판단한다.
     */
    fun isAccessibilityEnabled(context: Context): Boolean =
        AgentAccessibilityService.instance != null ||
            context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }

    fun async(block: () -> Unit) { executor.execute { runCatching(block) } }

    fun runCheckAsync(context: Context, trigger: Trigger, symptom: Symptom? = null, onDone: (CheckOutcome) -> Unit = {}) {
        init(context)
        async { onDone(runner.run(trigger, symptom)) }
    }

    fun flushOutboxAsync(context: Context) {
        init(context)
        async { sender.flush(); heartbeat.trySend(LocalDateTime.now()) }
    }

    /** 안부 신호 전송 시도. 알람·망 복구·잠금 해제 때 부른다. 보낼 차례가 아니면 아무것도 안 한다. */
    fun sendHeartbeatAsync(context: Context) {
        init(context)
        async { heartbeat.trySend(LocalDateTime.now()) }
    }

    /** 잠금 해제 후 첫 점검. 6시간 안에 점검했으면 건너뛴다 (배터리 부담을 줄이기 위한 가벼운 기준). */
    fun onUnlock(context: Context) {
        init(context)
        sendHeartbeatAsync(context) // 지연된 안부 신호가 있으면 이때 따라잡는다
        if (System.currentTimeMillis() - store.lastCheckMillis < UNLOCK_CHECK_MIN_GAP_MS) return
        runCheckAsync(context, Trigger.UNLOCK)
    }

    private const val UNLOCK_CHECK_MIN_GAP_MS = 6 * 60 * 60 * 1000L
}
