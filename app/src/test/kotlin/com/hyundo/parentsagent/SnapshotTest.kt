package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.*
import org.junit.Assert.*
import org.junit.Test

class SnapshotTest {
    private val base = baseline()

    private fun current(vararg overrides: Pair<SettingKey, SettingValue>): Map<SettingKey, SettingValue> =
        base.values.mapValues { (_, v) -> if (v is SettingValue.Level) SettingValue.Level.current(v.value) else v } + overrides

    @Test fun `같으면 차이 없음`() {
        val r = SnapshotDiff.compare(base, current())
        assertTrue(r.deviations.isEmpty())
        assertTrue(r.unreadable.isEmpty())
    }

    @Test fun `값이 다른 키 목록을 돌려준다`() {
        val r = SnapshotDiff.compare(
            base,
            current(
                SettingKey.AIRPLANE_MODE to flag(true),
                SettingKey.WIFI to flag(false),
                SettingKey.RINGER_MODE to SettingValue.Mode("SILENT"),
            ),
        )
        assertEquals(
            setOf(SettingKey.AIRPLANE_MODE, SettingKey.WIFI, SettingKey.RINGER_MODE),
            r.deviations.map { it.key }.toSet(),
        )
        val air = r.deviations.first { it.key == SettingKey.AIRPLANE_MODE }
        assertEquals(flag(false), air.expected)
        assertEquals(flag(true), air.actual)
    }

    @Test fun `볼륨은 허용 범위 안이면 이탈이 아니다`() {
        // 기준 5 → 허용 4..6
        assertTrue(SnapshotDiff.compare(base, current(SettingKey.VOLUME_RING to SettingValue.Level.current(4))).deviations.isEmpty())
        assertTrue(SnapshotDiff.compare(base, current(SettingKey.VOLUME_RING to SettingValue.Level.current(6))).deviations.isEmpty())
        val out = SnapshotDiff.compare(base, current(SettingKey.VOLUME_RING to SettingValue.Level.current(0)))
        assertEquals(listOf(SettingKey.VOLUME_RING), out.deviations.map { it.key })
        assertEquals(4, SettingValue.Level.around(5).min)
    }

    @Test fun `기준이 0이 아니면 무음 0은 범위 밖, 기준이 1이어도 하한은 1`() {
        assertEquals(1, SettingValue.Level.around(1).min)
        assertFalse(SettingValue.Level.around(1).matches(SettingValue.Level.current(0)))
        assertTrue(SettingValue.Level.around(0).matches(SettingValue.Level.current(0)))
    }

    @Test fun `읽지 못한 항목은 이탈로 세지 않고 따로 알린다`() {
        val cur = current() + (SettingKey.NOTIF_SMS_APP to SettingValue.Unknown)
        val r = SnapshotDiff.compare(base, cur)
        assertTrue(r.deviations.isEmpty())
        assertEquals(listOf(SettingKey.NOTIF_SMS_APP), r.unreadable)
    }

    @Test fun `현재 맵에 없는 키도 읽지 못한 항목`() {
        val r = SnapshotDiff.compare(base, current() - SettingKey.WIFI)
        assertEquals(listOf(SettingKey.WIFI), r.unreadable)
    }

    @Test fun `기준이 Unknown이면 비교하지 않는다`() {
        val b = base.copy(values = base.values + (SettingKey.WIFI to SettingValue.Unknown))
        assertTrue(SnapshotDiff.compare(b, current(SettingKey.WIFI to flag(false))).deviations.isEmpty())
    }

    @Test fun `JSON 왕복`() {
        val back = Snapshot.fromJson(base.toJson())
        assertEquals(base, back)
    }

    @Test fun `스냅샷에는 설정값만 있고 내용성 필드는 없다`() {
        val json = base.toJson()
        listOf("message", "contact", "password", "photo", "phoneNumber", "body").forEach {
            assertFalse("금지 필드 $it", json.contains(it, ignoreCase = true))
        }
    }

    @Test fun `capture는 볼륨만 범위로 바꾼다`() {
        val s = Snapshot.capture(mapOf(SettingKey.VOLUME_RING to SettingValue.Level.current(7), SettingKey.WIFI to flag(true)), 5L)
        assertEquals(SettingValue.Level(7, 6, 8), s.values[SettingKey.VOLUME_RING])
        assertEquals(flag(true), s.values[SettingKey.WIFI])
    }
}
