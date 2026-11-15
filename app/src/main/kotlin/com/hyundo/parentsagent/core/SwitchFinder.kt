package com.hyundo.parentsagent.core

/** 접근성 노드의 최소 모델. 안드로이드 타입 없이 찾기 로직을 단위 테스트하려고 분리했다. */
interface UiNode {
    val text: String?
    val desc: String?
    val isCheckable: Boolean
    val isClickable: Boolean
    val isChecked: Boolean
    val children: List<UiNode>
    val parent: UiNode?
}

/** 스위치를 어떤 방법으로 찾았는지. 실기 로그에 남는다. */
enum class FindStrategy(val text: String) {
    LABEL("문구 매칭"),
    DESCRIPTION("contentDescription 매칭"),
    SINGLE("단일 스위치"),
    ROW("행 클릭"),
}

/**
 * [node]는 누를 대상. [checked]는 화면에서 읽은 현재 상태이고, 행 클릭에서 상태가 화면에 안 나오면 null.
 */
data class FoundSwitch(val node: UiNode, val strategy: FindStrategy, val checked: Boolean?)

object SwitchFinder {
    /**
     * 순서: 문구(text) → contentDescription → 단일 스위치 → 행 클릭.
     * [singlePurpose]가 true인 화면(항목 전용 설정 페이지)에서만 체크 가능 노드가 정확히 1개일 때 그것을 쓴다.
     * 여러 스위치가 있는 화면에서는 단일 스위치 폴백이 동작하지 않는다.
     */
    fun find(root: UiNode?, labels: List<String>, singlePurpose: Boolean): FoundSwitch? {
        if (root == null) return null
        byLabel(root, labels, FindStrategy.LABEL) { it.text }?.let { return it }
        byLabel(root, labels, FindStrategy.DESCRIPTION) { it.desc }?.let { return it }
        if (singlePurpose) {
            val all = checkables(root)
            if (all.size == 1) return FoundSwitch(all[0], FindStrategy.SINGLE, all[0].isChecked)
        }
        return rowByLabel(root, labels)
    }

    /** 못 찾았을 때 화면 상황. 기존 형식("문구 N곳 발견, 화면 전체 스위치 M개")을 유지한다. */
    fun describe(root: UiNode?, labels: List<String>): String {
        if (root == null) return "활성 창 없음"
        return "문구 ${labelNodes(root, labels).size}곳 발견, 화면 전체 스위치 ${checkables(root).size}개"
    }

    fun checkables(root: UiNode): List<UiNode> = buildList { collect(root) { if (it.isCheckable) add(it) } }

    private fun byLabel(root: UiNode, labels: List<String>, strategy: FindStrategy, field: (UiNode) -> String?): FoundSwitch? {
        for (label in labels) {
            for (n in all(root)) {
                if (field(n)?.contains(label, ignoreCase = true) != true) continue
                (firstCheckable(n) ?: firstCheckable(n.parent))?.let { return FoundSwitch(it, strategy, it.isChecked) }
            }
        }
        return null
    }

    /** 스위치 노드가 없고 문구 옆 행만 눌러지는 화면. 행 안에 체크 가능 노드가 없으면 상태는 알 수 없다. */
    private fun rowByLabel(root: UiNode, labels: List<String>): FoundSwitch? {
        for (n in labelNodes(root, labels)) {
            val row = clickableAncestor(n) ?: continue
            return FoundSwitch(row, FindStrategy.ROW, if (row.isChecked) true else null)
        }
        return null
    }

    private fun labelNodes(root: UiNode, labels: List<String>): List<UiNode> = all(root).filter { n ->
        labels.any { n.text?.contains(it, ignoreCase = true) == true || n.desc?.contains(it, ignoreCase = true) == true }
    }

    private fun all(root: UiNode): List<UiNode> = buildList { collect(root) { add(it) } }

    private fun collect(n: UiNode, visit: (UiNode) -> Unit) {
        visit(n)
        n.children.forEach { collect(it, visit) }
    }

    private fun firstCheckable(n: UiNode?): UiNode? {
        if (n == null) return null
        if (n.isCheckable) return n
        n.children.forEach { c -> firstCheckable(c)?.let { return it } }
        return null
    }

    fun clickableAncestor(n: UiNode?): UiNode? {
        var cur = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }
}
