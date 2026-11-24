package com.hyundo.parentsagent.android

import android.content.Context
import com.hyundo.parentsagent.core.LastCheckGrade
import com.hyundo.parentsagent.core.LogEntry
import com.hyundo.parentsagent.core.LogKind
import com.hyundo.parentsagent.core.MainTab
import com.hyundo.parentsagent.core.ResultNote
import com.hyundo.parentsagent.core.ResultNotes
import com.hyundo.parentsagent.core.RestoreHistory
import com.hyundo.parentsagent.core.SettingKey
import com.hyundo.parentsagent.core.Snapshot
import com.hyundo.parentsagent.net.Outbox
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** 기기 안(SharedPreferences)에만 저장한다. 백업도 끈 상태(allowBackup=false). */
class Store(context: Context) : CheckStore {
    private val p = context.applicationContext.getSharedPreferences("agent", Context.MODE_PRIVATE)

    override var consented: Boolean
        get() = p.getBoolean("consented", false)
        set(v) = p.edit().putBoolean("consented", v).apply()

    var demoMode: Boolean
        get() = p.getBoolean("demoMode", false)
        set(v) = p.edit().putBoolean("demoMode", v).apply()

    /** 메인 화면에서 마지막으로 고른 탭. 화면이 다시 만들어져도 유지한다. */
    var mainTab: MainTab
        get() = MainTab.parse(p.getString("mainTab", null))
        set(v) = p.edit().putString("mainTab", v.name).apply()

    /** 앱 안에서 직접 입력한 Liner API 키. 빌드 때 주입된 키보다 우선한다. 기기 안에만 저장한다. */
    var linerApiKey: String
        get() = p.getString("linerApiKey", "") ?: ""
        set(v) = p.edit().putString("linerApiKey", v).apply()

    override var lastCheckMillis: Long
        get() = p.getLong("lastCheck", 0L)
        set(v) = p.edit().putLong("lastCheck", v).apply()

    override var deferredSince: Map<SettingKey, Long>
        get() {
            val o = runCatching { JSONObject(p.getString("deferredSince", null) ?: "{}") }.getOrNull() ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { name ->
                SettingKey.entries.firstOrNull { it.name == name }?.let { it to o.optLong(name) }
            }.toMap()
        }
        set(v) {
            val o = JSONObject()
            v.forEach { (k, t) -> o.put(k.name, t) }
            p.edit().putString("deferredSince", o.toString()).apply()
        }

    /** 마지막 점검 결과 시각. 0이면 알 수 없음 (이 필드가 생기기 전 저장본 포함). */
    val lastResultMillis: Long get() = p.getLong("lastResultAt", 0L)
    val lastResultGrade: LastCheckGrade get() = LastCheckGrade.parse(p.getString("lastResultGrade", null))

    override fun saveLastResult(atMillis: Long, grade: LastCheckGrade) {
        p.edit().putLong("lastResultAt", atMillis).putString("lastResultGrade", grade.name).apply()
    }

    /** 부모님이 방금 한 동작의 결과 카드 한 장 (없으면 null). 깨진 값은 읽을 때 지운다. */
    val resultNote: ResultNote?
        get() {
            val raw = p.getString("result_note", null) ?: return null
            val n = ResultNotes.decode(raw)
            if (n == null) clearResultNote()
            return n
        }

    fun saveResultNote(n: ResultNote) = p.edit().putString("result_note", ResultNotes.encode(n)).apply()
    fun clearResultNote() = p.edit().remove("result_note").apply()

    /** 마지막으로 안부 신호를 보낸 슬롯의 날짜 (하루 1회 중복 방지). */
    var lastHeartbeatSlotDate: LocalDate?
        get() = p.getString("hbSlot", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(v) = p.edit().putString("hbSlot", v?.toString()).apply()

    /** 마지막으로 안부 신호를 보낼 때 읽은 만보기 누적값. 없으면 null. */
    val lastStepCount: Long? get() = if (p.contains("lastStepCount")) p.getLong("lastStepCount", 0L) else null
    val lastStepCountAt: Long get() = p.getLong("lastStepCountAt", 0L)

    fun saveStepCount(count: Long, atMillis: Long = System.currentTimeMillis()) {
        p.edit().putLong("lastStepCount", count).putLong("lastStepCountAt", atMillis).apply()
    }

    override fun snapshot(): Snapshot? = p.getString("snapshot", null)?.let { runCatching { Snapshot.fromJson(it) }.getOrNull() }
    fun saveSnapshot(s: Snapshot) = p.edit().putString("snapshot", s.toJson()).apply()

    @Synchronized override fun history(): RestoreHistory = RestoreHistory.fromJson(p.getString("history", null))
    @Synchronized override fun saveHistory(h: RestoreHistory) =
        p.edit().putString("history", h.toJson(LocalDate.now())).apply()

    @Synchronized fun outbox(): Outbox = Outbox(p.getString("outbox", null))
    @Synchronized fun saveOutbox(o: Outbox) = p.edit().putString("outbox", o.toJson()).apply()

    override fun appendLog(text: String) = appendLog(text, LogKind.DEV, null)

    override fun appendLog(text: String, kind: LogKind, parentText: String?) =
        appendLog(text, System.currentTimeMillis(), kind, parentText)

    @Synchronized fun appendLog(text: String, nowMillis: Long, kind: LogKind = LogKind.DEV, parentText: String? = null) {
        val arr = JSONArray(p.getString("log", "[]"))
        arr.put(LogEntry(nowMillis, text, kind, parentText).toJson())
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - MAX_LOG) until arr.length()) trimmed.put(arr.get(i))
        p.edit().putString("log", trimmed.toString()).apply()
    }

    /** 부모님도 앱 안에서 볼 수 있는 복원 기록. 최신순. */
    fun log(): List<LogEntry> {
        val arr = JSONArray(p.getString("log", "[]"))
        return (arr.length() - 1 downTo 0).map { i ->
            LogEntry.fromJson(arr.getJSONObject(i))
        }
    }

    companion object {
        const val MAX_LOG = 200
    }
}
