package com.hyundo.parentsagent.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/** 하루 2회(아침·저녁) 주기 점검 + 하루 1회 심박 (설계 §4). 정확한 시각이 필요 없어 inexact 알람을 쓴다. */
object Scheduler {
    val SLOT_HOURS = listOf(8, 19)
    const val HEARTBEAT_HOUR = 9
    val HEARTBEAT_SLOT = SLOT_HOURS.size // 점검 슬롯 뒤 번호를 심박 알람에 쓴다
    private const val EXTRA_SLOT = "slot"

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
        if (slot == Scheduler.HEARTBEAT_SLOT) {
            Scheduler.schedule(context, slot, Scheduler.HEARTBEAT_HOUR)
            AgentRuntime.sendHeartbeatAsync(context)
            return
        }
        Scheduler.schedule(context, slot, Scheduler.SLOT_HOURS[slot.coerceIn(0, Scheduler.SLOT_HOURS.lastIndex)])
        AgentRuntime.runCheckAsync(context, Trigger.SCHEDULED)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Scheduler.scheduleAll(context)
    }
}
