package com.hyundo.parentsagent.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/** 하루 2회(아침·저녁) 주기 점검 + 하루 1회 안부 신호 (설계 §4). 정확한 시각이 필요 없어 inexact 알람을 쓴다. */
object Scheduler {
    val SLOT_HOURS = listOf(8, 19)
    const val HEARTBEAT_HOUR = 9
    val HEARTBEAT_SLOT = SLOT_HOURS.size // 점검 슬롯 뒤 번호를 안부 신호 알람에 쓴다
    /** 디버그 테스트 점검 전용 PendingIntent requestCode. 매일 슬롯(0..HEARTBEAT_SLOT)과 겹치지 않는다. */
    const val TEST_SLOT = 100
    private const val EXTRA_SLOT = "slot"

    /** 알람 수신 시 할 일. 매일 슬롯만 다음 날 재예약하고, 테스트 슬롯은 점검만 한다. */
    enum class AlarmAction(val runsCheck: Boolean, val sendsHeartbeat: Boolean, val reschedulesDaily: Boolean) {
        DAILY_CHECK(runsCheck = true, sendsHeartbeat = false, reschedulesDaily = true),
        HEARTBEAT(runsCheck = false, sendsHeartbeat = true, reschedulesDaily = true),
        TEST_CHECK(runsCheck = true, sendsHeartbeat = false, reschedulesDaily = false),
    }

    fun actionFor(slot: Int): AlarmAction = when (slot) {
        TEST_SLOT -> AlarmAction.TEST_CHECK
        HEARTBEAT_SLOT -> AlarmAction.HEARTBEAT
        else -> AlarmAction.DAILY_CHECK
    }

    fun scheduleAll(context: Context) {
        SLOT_HOURS.forEachIndexed { i, h -> schedule(context, i, h) }
        schedule(context, HEARTBEAT_SLOT, HEARTBEAT_HOUR)
    }

    fun schedule(context: Context, slot: Int, hour: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(hour, 0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 실기 확인 필요: Doze/삼성 절전에서 알람이 늦어지는 정도
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(context, slot))
    }

    /** 디버그 테스트용: 실제 예약 점검과 같은 알람 경로(setAndAllowWhileIdle → AlarmReceiver → Trigger.SCHEDULED)로 [delayMinutes]분 뒤 점검한다. 매일 알람은 건드리지 않는다. */
    fun scheduleTestCheck(context: Context, delayMinutes: Int = 1): Long {
        val am = context.getSystemService(AlarmManager::class.java)
        val millis = System.currentTimeMillis() + delayMinutes * 60_000L
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(context, TEST_SLOT))
        return millis
    }

    private fun pending(context: Context, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, slot,
            Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_SLOT, slot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun slotOf(intent: Intent): Int = intent.getIntExtra(EXTRA_SLOT, 0)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = Scheduler.slotOf(intent)
        val action = Scheduler.actionFor(slot)
        if (action.reschedulesDaily) {
            val hour = if (action == Scheduler.AlarmAction.HEARTBEAT) Scheduler.HEARTBEAT_HOUR
            else Scheduler.SLOT_HOURS[slot.coerceIn(0, Scheduler.SLOT_HOURS.lastIndex)]
            Scheduler.schedule(context, slot, hour)
        }
        if (action.sendsHeartbeat) AgentRuntime.sendHeartbeatAsync(context)
        if (action.runsCheck) AgentRuntime.runCheckAsync(context, Trigger.SCHEDULED)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Scheduler.scheduleAll(context)
    }
}
