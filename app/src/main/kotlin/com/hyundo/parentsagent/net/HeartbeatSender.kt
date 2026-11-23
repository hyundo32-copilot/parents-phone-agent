package com.hyundo.parentsagent.net

import com.hyundo.parentsagent.core.HeartbeatMessage
import com.hyundo.parentsagent.core.HeartbeatSchedule
import com.hyundo.parentsagent.core.StepsDelta
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/** UNHEALTHY: 앱이 실제로는 아무것도 복원하지 못하는 상태(접근성 꺼짐)라 일부러 보내지 않았다. */
enum class HeartbeatOutcome { SENT, SENT_DELAYED, NOT_DUE, DEFERRED, UNHEALTHY }

/**
 * 하루 1회 안부 신호 전송. 기존 TelegramClient를 그대로 쓴다. Liner 요약은 거치지 않는다.
 * 망이 끊기면 "보낼 차례" 상태가 그대로 남아(대기열 1칸) 다음 시도 때 실제 보낸 시각과 지연 표시로 한 통만 나간다.
 * 36시간 끊김 판단은 받는 쪽(현도)이 한다. 서버 감시는 없다.
 * 앱이 건강하지 않으면(접근성 꺼짐 등) 보내지 않는다. 안부 신호가 끊기는 것이 현도에게 권한 꺼짐을 알리는 신호다 (설계 §4).
 */
class HeartbeatSender(
    private val telegram: TelegramClient,
    private val hour: Int,
    private val loadLastSlotDate: () -> LocalDate?,
    private val saveLastSlotDate: (LocalDate) -> Unit,
    private val isHealthy: () -> Boolean = { true },
    /** 만보기 누적값. 권한·센서가 없거나 타임아웃이면 null (알 수 없음). 호출하는 스레드에서 기다리므로 백그라운드에서만 부른다. */
    private val readStepCount: () -> Long? = { null },
    private val loadLastStepCount: () -> Long? = { null },
    private val saveStepCount: (Long) -> Unit = {},
) {
    @Synchronized
    fun trySend(now: LocalDateTime): HeartbeatOutcome {
        if (!isHealthy()) return HeartbeatOutcome.UNHEALTHY
        val slot = HeartbeatSchedule.dueSlot(now, hour, loadLastSlotDate()) ?: return HeartbeatOutcome.NOT_DUE
        if (!telegram.configured) return HeartbeatOutcome.DEFERRED
        val currentSteps = readStepCount()
        val text = HeartbeatMessage.build(slot, now, StepsDelta.compute(loadLastStepCount(), currentSteps))
        try {
            telegram.send(text)
        } catch (e: IOException) {
            return HeartbeatOutcome.DEFERRED
        }
        saveLastSlotDate(slot.toLocalDate())
        if (currentSteps != null) saveStepCount(currentSteps)
        return if (HeartbeatMessage.isDelayed(slot, now)) HeartbeatOutcome.SENT_DELAYED else HeartbeatOutcome.SENT
    }

    /** 지금 보낼 메시지 전문만 만든다. 전송도 저장도 하지 않는다 (디버그 미리보기용). */
    fun previewText(now: LocalDateTime): String {
        var slot = now.toLocalDate().atTime(hour, 0)
        if (slot.isAfter(now)) slot = slot.minusDays(1)
        return HeartbeatMessage.build(slot, now, StepsDelta.compute(loadLastStepCount(), readStepCount()))
    }
}
