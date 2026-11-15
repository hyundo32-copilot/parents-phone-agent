package com.hyundo.parentsagent.android

import android.content.Context
import com.hyundo.parentsagent.core.RestoreHistory
import com.hyundo.parentsagent.core.Snapshot
import com.hyundo.parentsagent.net.Outbox
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class LogEntry(val timeMillis: Long, val text: String)

/** 기기 안(SharedPreferences)에만 저장한다. 백업도 끈 상태(allowBackup=false). */
class Store(context: Context) : CheckStore {
    private val p = context.applicationContext.getSharedPreferences("agent", Context.MODE_PRIVATE)

    override var consented: Boolean
        get() = p.getBoolean("consented", false)
        set(v) = p.edit().putBoolean("consented", v).apply()

    var demoMode: Boolean
        get() = p.getBoolean("demoMode", false)
        set(v) = p.edit().putBoolean("demoMode", v).apply()

    override var lastCheckMillis: Long
        get() = p.getLong("lastCheck", 0L)
        set(v) = p.edit().putLong("lastCheck", v).apply()

    /** 마지막으로 안부 신호를 보낸 슬롯의 날짜 (하루 1회 중복 방지). */
    var lastHeartbeatSlotDate: LocalDate?
        get() = p.getString("hbSlot", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(v) = p.edit().putString("hbSlot", v?.toString()).apply()

    override fun snapshot(): Snapshot? = p.getString("snapshot", null)?.let { runCatching { Snapshot.fromJson(it) }.getOrNull() }
    fun saveSnapshot(s: Snapshot) = p.edit().putString("snapshot", s.toJson()).apply()

    @Synchronized override fun history(): RestoreHistory = RestoreHistory.fromJson(p.getString("history", null))
    @Synchronized override fun saveHistory(h: RestoreHistory) =
        p.edit().putString("history", h.toJson(LocalDate.now())).apply()

    @Synchronized fun outbox(): Outbox = Outbox(p.getString("outbox", null))
    @Synchronized fun saveOutbox(o: Outbox) = p.edit().putString("outbox", o.toJson()).apply()

    override fun appendLog(text: String) = appendLog(text, System.currentTimeMillis())

    @Synchronized fun appendLog(text: String, nowMillis: Long) {
        val arr = JSONArray(p.getString("log", "[]"))
        arr.put(JSONObject().put("t", nowMillis).put("x", text))
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - MAX_LOG) until arr.length()) trimmed.put(arr.get(i))
        p.edit().putString("log", trimmed.toString()).apply()
    }

    /** 부모님도 앱 안에서 볼 수 있는 복원 기록. 최신순. */
    fun log(): List<LogEntry> {
        val arr = JSONArray(p.getString("log", "[]"))
        return (arr.length() - 1 downTo 0).map { i ->
            val o = arr.getJSONObject(i)
            LogEntry(o.getLong("t"), o.getString("x"))
        }
    }

    companion object {
        const val MAX_LOG = 200
    }
}
