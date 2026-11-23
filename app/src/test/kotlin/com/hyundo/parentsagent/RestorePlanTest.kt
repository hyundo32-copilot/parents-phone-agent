package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.*
import org.junit.Assert.*
import org.junit.Test

class RestorePlanTest {
    private fun d(key: SettingKey) = dev(key, flag(true), flag(false))
    private fun keys(l: List<Deviation>) = l.map { it.key }

    // --- orderForRestore ---

    @Test fun `방해금지를 벨소리 모드·벨소리 볼륨 앞으로 옮기고 나머지 순서는 지킨다`() {
        val devs = listOf(SettingKey.WIFI, SettingKey.RINGER_MODE, SettingKey.VOLUME_RING, SettingKey.VOLUME_MEDIA, SettingKey.DND, SettingKey.NOTIF_PHONE_APP).map(::d)
        assertEquals(
            listOf(SettingKey.WIFI, SettingKey.DND, SettingKey.RINGER_MODE, SettingKey.VOLUME_RING, SettingKey.VOLUME_MEDIA, SettingKey.NOTIF_PHONE_APP),
            keys(RestorePlan.orderForRestore(devs)),
        )
    }

    @Test fun `벨소리 볼륨만 있어도 방해금지가 앞선다`() {
        val devs = listOf(SettingKey.VOLUME_RING, SettingKey.DND).map(::d)
        assertEquals(listOf(SettingKey.DND, SettingKey.VOLUME_RING), keys(RestorePlan.orderForRestore(devs)))
    }

    @Test fun `방해금지가 이미 앞이거나 없으면 원래 순서`() {
        val a = listOf(SettingKey.DND, SettingKey.RINGER_MODE).map(::d)
        assertEquals(keys(a), keys(RestorePlan.orderForRestore(a)))
        val b = listOf(SettingKey.WIFI, SettingKey.RINGER_MODE, SettingKey.VOLUME_RING).map(::d)
        assertEquals(keys(b), keys(RestorePlan.orderForRestore(b)))
    }

    @Test fun `벨소리 항목이 없으면 방해금지는 제자리`() {
        val devs = listOf(SettingKey.WIFI, SettingKey.DND, SettingKey.NOTIF_SMS_APP).map(::d)
        assertEquals(keys(devs), keys(RestorePlan.orderForRestore(devs)))
    }

    // --- dndPath ---

    @Test fun `dndPath 네 가지 조합`() {
        assertEquals(RestorePlan.DndPath.Api, RestorePlan.dndPath(policyGranted = true, blocker = "화면 꺼짐"))
        assertEquals(RestorePlan.DndPath.ApiThenAccessibility, RestorePlan.dndPath(policyGranted = true, blocker = null))
        assertEquals(RestorePlan.DndPath.DeferAll, RestorePlan.dndPath(policyGranted = false, blocker = "화면 꺼짐"))
        assertEquals(RestorePlan.DndPath.ApiThenAccessibility, RestorePlan.dndPath(policyGranted = false, blocker = null))
    }

    // --- gateMode ---

    @Test fun `gateMode - 방해금지와 오디오 항목은 API 먼저, 접근성 항목은 미룸`() {
        assertEquals(GateMode.API_FIRST, RestorePlan.gateMode(SettingKey.DND, "화면 꺼짐"))
        assertEquals(GateMode.DEFER, RestorePlan.gateMode(SettingKey.WIFI, "화면 꺼짐"))
        assertEquals(GateMode.API_FIRST, RestorePlan.gateMode(SettingKey.VOLUME_RING, "통화 중"))
        SettingKey.entries.forEach { assertEquals(it.name, GateMode.NONE, RestorePlan.gateMode(it, null)) }
    }

    @Test fun `gateMode - 오디오 3개는 화면 꺼짐이면 API 먼저, 막힌 게 없으면 NONE`() {
        assertEquals(setOf(SettingKey.RINGER_MODE, SettingKey.VOLUME_RING, SettingKey.VOLUME_MEDIA), RestorePlan.API_FIRST_AUDIO)
        RestorePlan.API_FIRST_AUDIO.forEach {
            assertEquals(it.name, GateMode.API_FIRST, RestorePlan.gateMode(it, "화면 꺼짐"))
            assertEquals(it.name, GateMode.NONE, RestorePlan.gateMode(it, null))
        }
    }

    // --- deferredAfterApi ---

    @Test fun `deferredAfterApi - 오디오는 API만 쓰는 상황에서 값이 안 맞으면 시도했어도 미룬다`() {
        RestorePlan.API_FIRST_AUDIO.forEach {
            assertTrue(it.name, RestorePlan.deferredAfterApi(it, apiOnly = true, attempted = true, matched = false))
            assertTrue(it.name, RestorePlan.deferredAfterApi(it, apiOnly = true, attempted = false, matched = false))
        }
    }

    @Test fun `deferredAfterApi - 오디오가 맞으면 미루지 않는다`() {
        RestorePlan.API_FIRST_AUDIO.forEach {
            assertFalse(it.name, RestorePlan.deferredAfterApi(it, apiOnly = true, attempted = true, matched = true))
        }
    }

    @Test fun `deferredAfterApi - 화면이 켜져 있으면(apiOnly 아님) 오디오 실패는 FAILED로 둔다`() {
        RestorePlan.API_FIRST_AUDIO.forEach {
            assertFalse(it.name, RestorePlan.deferredAfterApi(it, apiOnly = false, attempted = true, matched = false))
        }
    }

    @Test fun `deferredAfterApi - 방해금지는 기존대로 실행 못 했을 때만 미룬다`() {
        assertTrue(RestorePlan.deferredAfterApi(SettingKey.DND, apiOnly = true, attempted = false, matched = false))
        assertFalse(RestorePlan.deferredAfterApi(SettingKey.DND, apiOnly = true, attempted = true, matched = false))
        assertFalse(RestorePlan.deferredAfterApi(SettingKey.DND, apiOnly = true, attempted = false, matched = true))
        assertFalse(RestorePlan.deferredAfterApi(SettingKey.DND, apiOnly = false, attempted = false, matched = false))
        assertFalse(RestorePlan.deferredAfterApi(SettingKey.WIFI, apiOnly = true, attempted = false, matched = false))
    }

    @Test fun `미룸 로그 문구에 사유가 들어간다`() {
        assertEquals("벨소리 모드: 화면 꺼짐이라 일반 API로 안 돼서 잠금 해제 때 고칠게요", RestorePlan.deferredLogLine("벨소리 모드"))
    }

    @Test fun `gateMode - 접근성이 필요한 나머지 항목은 전부 미룸`() {
        val others = RestorePlan.NEEDS_ACCESSIBILITY - SettingKey.DND
        assertEquals(setOf(SettingKey.AIRPLANE_MODE, SettingKey.WIFI, SettingKey.MOBILE_DATA, SettingKey.NOTIF_PHONE_APP, SettingKey.NOTIF_SMS_APP), others)
        others.forEach { assertEquals(it.name, GateMode.DEFER, RestorePlan.gateMode(it, "잠금 상태")) }
    }

    // --- 카운트 규칙 ---

    @Test fun `미룸과 이미 정상은 일일 횟수와 이탈 일수에 안 센다`() {
        listOf(AttemptResult.DEFERRED, AttemptResult.ALREADY_OK).forEach {
            assertFalse(it.name, it.countsTowardDailyLimit())
            assertFalse(it.name, it.countsAsDeviationDay())
        }
        listOf(AttemptResult.SUCCESS, AttemptResult.FAILED, AttemptResult.STOPPED_BY_USER).forEach {
            assertTrue(it.name, it.countsTowardDailyLimit())
            assertTrue(it.name, it.countsAsDeviationDay())
        }
    }

    // --- SecurityException ---

    @Test fun `벨소리 모드 변경이 SecurityException이면 실패 사유로 돌려주고 던지지 않는다`() {
        val r = RestorePlan.applyRinger { throw SecurityException("알림 정책 접근 필요") }
        assertEquals(RestorePlan.RINGER_NO_POLICY to false, r)
        assertEquals("알림 정책 접근 없음", r.first)
    }

    @Test fun `벨소리 모드 변경이 되면 일반 API로 기록`() {
        var called = 0
        assertEquals("일반 API (벨소리 모드)" to true, RestorePlan.applyRinger { called++ })
        assertEquals(1, called)
    }

    @Test fun `SecurityException 외의 예외는 그대로 던진다`() {
        try {
            RestorePlan.applyRinger { throw IllegalStateException("x") }
            fail()
        } catch (e: IllegalStateException) {
            // Restorer.restore 의 바깥 try/catch 가 "오류"로 처리한다
        }
    }
}
