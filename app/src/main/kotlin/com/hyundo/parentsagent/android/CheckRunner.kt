package com.hyundo.parentsagent.android

import android.content.Context
import com.hyundo.parentsagent.core.*
import com.hyundo.parentsagent.net.EscalationSender
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

enum class Trigger { SCHEDULED, UNLOCK, MANUAL, SYMPTOM }

sealed interface CheckOutcome {
    data object NoBaseline : CheckOutcome
    data object NoConsent : CheckOutcome
    data object AlreadyRunning : CheckOutcome
    data class Done(val summary: String) : CheckOutcome
}

/** 점검 한 번: 읽기 → 비교 → (규칙 엔진 판정) → 복원 → 재확인 → 필요하면 에스컬레이션. */
class CheckRunner(
    context: Context,
    private val store: Store,
    private val reader: SettingsReader,
    private val sender: EscalationSender,
    private val announcer: Announcer,
) {
    private val ctx = context.applicationContext
    private val running = AtomicBoolean(false)

    fun run(trigger: Trigger, symptom: Symptom? = null): CheckOutcome {
        if (!store.consented) return CheckOutcome.NoConsent
        val baseline = store.snapshot() ?: return CheckOutcome.NoBaseline
        if (!running.compareAndSet(false, true)) return CheckOutcome.AlreadyRunning
        try {
            StopController.reset()
            // 다른 앱 알림 설정은 접근성으로 화면을 열어 읽어야 해서, 부모님이 쓰는 중일 수 있는 자동 점검에서는 건너뛴다
            val deep = trigger == Trigger.MANUAL || trigger == Trigger.SYMPTOM
            val history = store.history()
            val policy = RestorePolicy(history)
            val cmp = SnapshotDiff.compare(baseline, reader.readAll(deep))
            val roaming = reader.isRoaming()

            val attempts = mutableListOf<RestoreAttempt>()
            val skipped = mutableListOf<SkippedItem>()
            val decidedKeys = mutableSetOf<SettingKey>()
            val restorer = Restorer(ctx, reader, deep)

            for (dev in cmp.deviations) {
                if (StopController.requested) break
                val now = LocalDateTime.now(ZoneId.systemDefault())
                val d = policy.decide(dev, PolicyContext(now, baseline.nightStartMin, baseline.nightEndMin, roaming, attempts.size))
                decidedKeys += dev.key
                when (d) {
                    is Decision.Restore -> {
                        val intro = "${dev.key.label}을 원래대로 고칠게요"
                        StatusBus.fixing(intro)
                        // 복원 전에 먼저 알리고, 중단할 시간을 준다 (위임 노트 §0). TTS를 못 쓰면 3초 대기.
                        if (!announcer.speakAndWait(intro)) Thread.sleep(3_000) else Thread.sleep(1_500)
                        if (StopController.requested) {
                            store.appendLog("${dev.key.label}: 부모님이 중단해서 고치지 않았어요")
                            break
                        }
                        history.recordRestoreAttempt(dev.key, now.toLocalDate())
                        val a = restorer.restore(dev)
                        attempts += a
                        store.appendLog(logLine(a))
                        announcer.speak(
                            when (a.result) {
                                AttemptResult.SUCCESS -> "${dev.key.label}을 원래대로 되돌렸어요"
                                AttemptResult.STOPPED_BY_USER -> "멈췄어요"
                                AttemptResult.FAILED -> "${dev.key.label}을 못 고쳤어요. 아들한테 알릴게요"
                            },
                        )
                    }
                    is Decision.Skip -> {
                        if (d.pause) history.pause(dev.key)
                        store.appendLog("${dev.key.label}: ${d.reason.text}")
                        if (d.notify && history.markNotified(dev.key, now.toLocalDate(), d.reason.name)) {
                            skipped += SkippedItem(dev, d.reason)
                        }
                    }
                }
            }
            StatusBus.idle()
            store.saveHistory(history)
            store.lastCheckMillis = System.currentTimeMillis()

            val stopped = StopController.requested
            // 복원 뒤 다시 비교. 판정을 거친 건너뜀 항목은 이미 사유와 함께 보고했거나 일부러 조용히 둔 것이라 제외
            val remaining = SnapshotDiff.compare(baseline, reader.readAll(deep)).deviations.filter { it.key !in decidedKeys || attempts.any { a -> a.key == it.key } }
            val report = EscalationReport(
                symptom = symptom,
                deviations = cmp.deviations,
                attempts = attempts,
                skipped = skipped,
                remaining = remaining,
                unreadable = cmp.unreadable,
            )
            val summary = if (stopped) {
                "부모님이 중단해서 멈췄어요"
            } else if (report.needsEscalation()) {
                val outcome = sender.send(EscalationMessage.build(report))
                announcer.speak("아들한테 연락했어요, 잠시만 기다려요")
                store.appendLog("현도에게 알림: $outcome")
                "현도에게 알렸어요 ($outcome)"
            } else if (cmp.deviations.isEmpty()) {
                "모두 정상이에요"
            } else {
                "되돌렸어요"
            }
            return CheckOutcome.Done(summary)
        } finally {
            StatusBus.idle()
            running.set(false)
        }
    }

    private fun logLine(a: RestoreAttempt): String =
        "${a.key.label}: ${a.before.display()} → ${a.after.display()} (${a.result.text})"
}
