package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.MainTab
import org.junit.Assert.*
import org.junit.Test

class MainTabTest {
    @Test fun `저장된 값이 없으면 폰 상태 탭이 기본이다`() {
        assertEquals(MainTab.PHONE_STATUS, MainTab.parse(null))
        assertEquals(MainTab.PHONE_STATUS, MainTab.DEFAULT)
    }

    @Test fun `깨진 값이나 모르는 값은 기본 탭으로 돌아간다`() {
        assertEquals(MainTab.PHONE_STATUS, MainTab.parse(""))
        assertEquals(MainTab.PHONE_STATUS, MainTab.parse("garbage"))
        assertEquals(MainTab.PHONE_STATUS, MainTab.parse("family"))
    }

    @Test fun `저장한 이름으로 같은 탭을 되살린다`() {
        MainTab.entries.forEach { assertEquals(it, MainTab.parse(it.name)) }
    }

    @Test fun `탭은 두 개이고 이름이 정해진 대로다`() {
        assertEquals(listOf("폰 상태", "가족 관리"), MainTab.entries.map { it.label })
    }
}
