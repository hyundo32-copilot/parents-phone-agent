package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.*
import org.junit.Assert.*
import org.junit.Test

class SwitchFinderTest {
    private class N(
        override val text: String? = null,
        override val desc: String? = null,
        override val isCheckable: Boolean = false,
        override val isClickable: Boolean = false,
        override val isChecked: Boolean = false,
        kids: List<N> = emptyList(),
    ) : UiNode {
        override var parent: UiNode? = null
        override val children: List<UiNode> = kids
        init { kids.forEach { it.parent = this } }
    }

    private val labels = listOf("비행기 모드", "Airplane mode")

    @Test fun `문구 옆 스위치를 문구로 찾는다`() {
        val sw = N(isCheckable = true, isChecked = true)
        val root = N(kids = listOf(N(kids = listOf(N(text = "비행기 모드"), sw))))
        val f = SwitchFinder.find(root, labels, singlePurpose = false)!!
        assertSame(sw, f.node)
        assertEquals(FindStrategy.LABEL, f.strategy)
        assertEquals(true, f.checked)
    }

    @Test fun `문구가 contentDescription에만 있어도 찾는다`() {
        val sw = N(isCheckable = true)
        val root = N(kids = listOf(N(desc = "Airplane mode", kids = listOf(sw))))
        val f = SwitchFinder.find(root, labels, false)!!
        assertEquals(FindStrategy.DESCRIPTION, f.strategy)
        assertSame(sw, f.node)
    }

    @Test fun `전용 페이지에서 문구가 없고 스위치가 1개면 그것을 쓴다`() {
        val sw = N(isCheckable = true, isChecked = true)
        val root = N(kids = listOf(N(text = "전혀 다른 문구"), sw))
        val f = SwitchFinder.find(root, labels, singlePurpose = true)!!
        assertEquals(FindStrategy.SINGLE, f.strategy)
        assertSame(sw, f.node)
    }

    @Test fun `전용 페이지가 아니면 스위치가 1개여도 문구 없이는 안 쓴다`() {
        val root = N(kids = listOf(N(isCheckable = true)))
        assertNull(SwitchFinder.find(root, labels, singlePurpose = false))
    }

    @Test fun `전용 페이지여도 스위치가 여러 개면 단일 폴백을 쓰지 않는다`() {
        val root = N(kids = listOf(N(isCheckable = true), N(isCheckable = true)))
        assertNull(SwitchFinder.find(root, labels, singlePurpose = true))
    }

    @Test fun `문구가 있으면 단일 스위치보다 문구가 우선이다`() {
        val a = N(isCheckable = true)
        val root = N(kids = listOf(N(kids = listOf(N(text = "비행기 모드"), a))))
        assertEquals(FindStrategy.LABEL, SwitchFinder.find(root, labels, true)!!.strategy)
    }

    @Test fun `스위치 노드가 없으면 문구가 있는 클릭 가능 행을 누른다`() {
        val row = N(isClickable = true, kids = listOf(N(text = "비행기 모드")))
        val root = N(kids = listOf(row))
        val f = SwitchFinder.find(root, labels, false)!!
        assertEquals(FindStrategy.ROW, f.strategy)
        assertSame(row, f.node)
        assertNull(f.checked)
    }

    @Test fun `못 찾으면 기존 형식으로 화면 상황을 설명한다`() {
        val root = N(kids = listOf(N(isCheckable = true)))
        assertEquals("문구 0곳 발견, 화면 전체 스위치 1개", SwitchFinder.describe(root, labels))
        assertEquals("활성 창 없음", SwitchFinder.describe(null, labels))
    }

    @Test fun `진단에 찾은 방법이 남는다`() {
        val a = SwitchAttempt(SwitchOutcome.CHANGED, strategy = FindStrategy.SINGLE)
        assertEquals("눌러서 바뀜 [찾은 방법: 단일 스위치]", a.describe())
    }
}
