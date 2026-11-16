package com.hyundo.parentsagent.android

import com.hyundo.parentsagent.core.*
import com.hyundo.parentsagent.net.Escalator
import com.hyundo.parentsagent.net.SendOutcome
import com.hyundo.parentsagent.net.Sleeper
import com.hyundo.parentsagent.net.ThreadSleeper
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
    private val store: CheckStore,
    private val device: DeviceReader,
    private val gate: ScreenGate,
    private val restorerFor: (deep: Boolean) -> RestoreExecutor,
    private val sender: Escalator,
    private val announcer: Speaker,
    private val status: StatusSink,
    private val stop: StopSignal,
    private val sleeper: Sleeper = ThreadSleeper,
    private val clock: () -> LocalDateTime = { LocalDateTime.now(ZoneId.systemDefault()) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val running = AtomicBoolean(false)

    fun run(trigger: Trigger, symptom: Symptom? = null): CheckOutcome {
        if (!store.consented) return CheckOutcome.NoConsent
        val baseline = store.snapshot() ?: return CheckOutcome.NoBaseline
        if (!running.compareAndSet(false, true)) return CheckOutcome.AlreadyRunning
        try {
            stop.reset()
            // 다른 앱 알림 설정은 접근성으로 화면을 열어 읽어야 해서, 부모님이 쓰는 중일 수 있는 자동 점검에서는 건너뛴다
            val deep = trigger == Trigger.MANUAL || trigger == Trigger.SYMPTOM
            val history = store.history()
            val policy = RestorePolicy(history)
            val cmp = SnapshotDiff.compare(baseline, device.readAll(deep))

            val attempts = mutableListOf<RestoreAttempt>()
            // 가족 알림을 실제로 보냈는지는 점검 끝에야 알 수 있어서, 실패 기록은 그때 쓴다
            val pendingFailed = mutableListOf<PendingFailure>()
            val skipped = mutableListOf<SkippedItem>()
            val deferred = mutableListOf<SettingKey>()
            val decidedKeys = mutableSetOf<SettingKey>()
            val restorer = restorerFor(deep)
            var airplaneRestored = false

            for (dev in cmp.deviations) {
                if (stop.requested) break
                val now = clock()

                // 화면이 꺼졌거나 잠겼거나 통화 중이면 접근성 수단이 필요한 항목은 잠금 해제 때로 미룬다.
                // 판정(policy)에 넣지 않으므로 시도 횟수·이탈 일수에 안 세고 현도에게도 알리지 않는다.
                if (dev.key in NEEDS_ACCESSIBILITY && gate.accessibilityBlocker() != null) {
                    deferred += dev.key
                    decidedKeys += dev.key
                    continue
                }
                // 비행기 모드를 방금 풀었으면 망이 붙기 전이라 로밍을 알 수 없다. 데이터는 다음 점검에서 판정한다.
                if (dev.key == SettingKey.MOBILE_DATA && airplaneRestored) {
                    deferred += dev.key
                    decidedKeys += dev.key
                    continue
                }
                // 로밍은 데이터 항목을 판정하기 직전에 읽는다 (점검 시작 때 값은 낡을 수 있다). 모르면 로밍으로 본다.
                val roaming = dev.key == SettingKey.MOBILE_DATA && device.roamingState() != RoamingState.HOME

                val d = policy.decide(dev, PolicyContext(now, baseline.nightStartMin, baseline.nightEndMin, roaming, attempts.size, manual = trigger == Trigger.MANUAL))
                decidedKeys += dev.key
                when (d) {
                    is Decision.Restore -> {
                        val intro = "${dev.key.label}을 원래대로 고칠게요"
                        status.fixing(intro)
                        // 복원 전에 먼저 알리고, 중단할 시간을 준다 (위임 노트 §0). TTS를 못 쓰면 3초 대기.
                        if (!announcer.speakAndWait(intro)) sleeper.sleep(3_000) else sleeper.sleep(1_500)
                        if (stop.requested) {
                            store.appendLog("${dev.key.label}: 부모님이 중단해서 고치지 않았어요")
                            break
                        }
                        // 수동 점검의 복원은 주인이 직접 누른 것이라 일일 횟수에 세지 않는다.
                        // 세면 수동으로 두 번 고친 뒤 자동 점검이 그날 멈춰 버린다.
                        if (trigger != Trigger.MANUAL) history.recordRestoreAttempt(dev.key, now.toLocalDate())
                        val a = restorer.restore(dev)
                        attempts += a
                        if (a.key == SettingKey.AIRPLANE_MODE && a.result == AttemptResult.SUCCESS) airplaneRestored = true
                        when (a.result) {
                            AttemptResult.SUCCESS -> store.appendLog(logLine(a), LogKind.FIXED, ParentText.fixed(a.key.label))
                            AttemptResult.FAILED -> pendingFailed += PendingFailure(logLine(a), a.key.label)
                            AttemptResult.STOPPED_BY_USER -> store.appendLog(logLine(a))
                        }
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
                        val line = "${dev.key.label}: ${d.reason.text}"
                        if (ParentText.isLimit(d.reason)) pendingFailed += PendingFailure(line, dev.key.label) else store.appendLog(line)
                        if (d.notify && history.markNotified(dev.key, now.toLocalDate(), d.reason.name)) {
                            skipped += SkippedItem(dev, d.reason)
                        }
                    }
                }
            }
            status.idle()
            store.saveHistory(history)
            // 미룬 항목이 있으면 점검한 것으로 치지 않는다. 그래야 잠금 해제 때 6시간 간격에 막히지 않고 따라잡는다.
            if (deferred.isEmpty()) store.lastCheckMillis = nowMillis()

            val stopped = stop.requested
            // 복원 뒤 다시 비교. 판정을 거친 건너뜀·미룬 항목은 이미 사유와 함께 보고했거나 일부러 조용히 둔 것이라 제외
            val remaining = SnapshotDiff.compare(baseline, device.readAll(deep)).deviations.filter { it.key !in decidedKeys || attempts.any { a -> a.key == it.key } }
            // 중단한 점검은 결과를 모르므로 이전 결과를 그대로 둔다
            if (!stopped) store.saveLastResult(nowMillis(), LastCheckGrade.of(attempts, remaining))
            val report = EscalationReport(
                symptom = symptom,
                deviations = cmp.deviations,
                attempts = attempts,
                skipped = skipped,
                remaining = remaining,
                unreadable = cmp.unreadable,
            )
            var familyNotified = false
            val summary = if (stopped) {
                "부모님이 중단해서 멈췄어요"
            } else if (report.needsEscalation()) {
                val outcome = sender.send(EscalationMessage.build(report))
                if (outcome == SendOutcome.REJECTED) {
                    store.appendLog("알림 설정 오류: 현도에게 알리지 못했어요")
                    "알림 설정 오류로 현도에게 못 알렸어요"
                } else {
                    announcer.speak("아들한테 연락했어요, 잠시만 기다려요")
                    store.appendLog("현도에게 알림: $outcome")
                    familyNotified = outcome == SendOutcome.SENT || outcome == SendOutcome.SENT_RAW
                    "현도에게 알렸어요 ($outcome)"
                }
            } else if (deferred.isNotEmpty()) {
                "폰을 쓰는 중이라 ${deferred.joinToString(", ") { it.label }}은 잠금을 풀면 고칠게요"
            } else if (cmp.deviations.isEmpty()) {
                "모두 정상이에요"
            } else {
                "되돌렸어요"
            }
            pendingFailed.forEach { store.appendLog(it.text, LogKind.FAILED, parentMessage(it, familyNotified)) }
            if (!stopped && cmp.deviations.isEmpty() && cmp.unreadable.isEmpty()) {
                store.appendLog("점검: 모두 정상", LogKind.INFO, ParentText.NO_PROBLEM)
            }
            return CheckOutcome.Done(summary)
        } finally {
            status.idle()
            running.set(false)
        }
    }

    private class PendingFailure(val text: String, val label: String)

    /** 부모님용 문구는 여기 한 곳에서 만든다. */
    private fun parentMessage(f: PendingFailure, familyNotified: Boolean) = ParentText.failed(f.label, familyNotified)

    /** method(어떻게 시도했고 왜 안 됐는지 진단)를 뒤에 붙인다. 텔레그램이 없으면 이 로그가 유일한 확인 경로다. */
    private fun logLine(a: RestoreAttempt): String {
        val base = "${a.key.label}: ${a.before.display()} → ${a.after.display()} (${a.result.text})"
        return if (a.method.isBlank()) base else "$base — ${a.method}"
    }

    companion object {
        /** 복원 수단이 접근성(빠른 설정·설정 화면)인 항목. 방해금지는 일반 API가 먼저지만 폴백이 접근성이라 같이 묶는다. */
        val NEEDS_ACCESSIBILITY = setOf(
            SettingKey.AIRPLANE_MODE, SettingKey.WIFI, SettingKey.MOBILE_DATA, SettingKey.DND,
            SettingKey.NOTIF_PHONE_APP, SettingKey.NOTIF_SMS_APP,
        )
    }
}
