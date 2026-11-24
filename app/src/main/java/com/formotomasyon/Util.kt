package com.formotomasyon

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.Locale

/** Türkçe karakter, büyük/küçük harf ve noktalama farklarını yok sayan metin eşleştirme yardımcıları. */
object Txt {
    private val MAHALLE_WORDS = setOf("mahallesi", "mahalle", "mah", "mh")
    private val DOTTED_I = setOf('İ', 'I', 'ı')
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val MARKS = Regex("\\p{M}+")

    fun norm(s: String): String {
        val folded = s.map { if (it in DOTTED_I) 'i' else it }.joinToString("")
        val decomposed = Normalizer.normalize(folded.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return decomposed.replace(MARKS, "").replace(NON_ALNUM, " ").trim()
    }

    fun stripMah(n: String): String =
        n.split(" ").filter { it.isNotEmpty() && it !in MAHALLE_WORDS }.joinToString(" ")

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }

    fun score(target: String, cand: String): Double {
        if (target.isEmpty() || cand.isEmpty()) return 0.0
        if (target == cand) return 1.0
        val sim = 1.0 - lev(target, cand).toDouble() / maxOf(target.length, cand.length)
        val contains = " $cand ".contains(" $target ") || " $target ".contains(" $cand ")
        return if (contains) maxOf(sim, 0.85) else sim
    }
}

/** AccessibilityNodeInfo ağacı üzerinde gezinme ve yazdırma yardımcıları. */
object Nodes {
    private const val MAX_DEPTH = 60
    private const val CLICKABLE_LOOKUP_STEPS = 8
    private const val SCROLLABLE_LOOKUP_STEPS = 12
    private const val DUMP_MAX_CHARS = 60000

    fun label(n: AccessibilityNodeInfo): String {
        val text = n.text?.toString()
        if (!text.isNullOrBlank()) return text.trim()
        return n.contentDescription?.toString()?.trim() ?: ""
    }

    fun normLabel(n: AccessibilityNodeInfo): String = Txt.norm(label(n))

    fun walk(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        out.add(n)
        if (depth > MAX_DEPTH) return
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            walk(child, out, depth + 1)
        }
    }

    private fun firstUp(
        n: AccessibilityNodeInfo,
        maxSteps: Int,
        test: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? = generateSequence(n) { it.parent }.take(maxSteps).firstOrNull(test)

    fun clickableSelfOrAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        firstUp(n, CLICKABLE_LOOKUP_STEPS) { it.isClickable }

    fun scrollableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        firstUp(n, SCROLLABLE_LOOKUP_STEPS) { it.isScrollable }

    private fun bounds(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

    fun top(n: AccessibilityNodeInfo): Int = bounds(n).top

    fun left(n: AccessibilityNodeInfo): Int = bounds(n).left

    private fun flags(n: AccessibilityNodeInfo): String = buildString {
        if (n.isClickable) append('C')
        if (n.isScrollable) append('S')
        if (n.isEditable) append('E')
        if (n.isCheckable) append(if (n.isChecked) 'K' else 'k')
        if (!n.isEnabled) append('!')
        if (n.isVisibleToUser) append('V')
    }

    fun dump(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        if (sb.length > DUMP_MAX_CHARS || depth > MAX_DEPTH) return
        sb.append("  ".repeat(depth))
        sb.append(n.className?.toString()?.substringAfterLast('.') ?: "?")
        n.text?.let { sb.append(" t='").append(it).append("'") }
        n.contentDescription?.let { sb.append(" d='").append(it).append("'") }
        n.viewIdResourceName?.let { sb.append(" id=").append(it) }
        sb.append(" [").append(flags(n)).append("] ")
        sb.append(bounds(n).toShortString()).append('\n')
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            dump(child, depth + 1, sb)
        }
    }
}
