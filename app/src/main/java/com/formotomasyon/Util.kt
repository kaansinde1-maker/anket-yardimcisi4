package com.formotomasyon

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.Locale

object Txt {
    private val MAH = setOf("mahallesi", "mahalle", "mah", "mh")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val MARKS = Regex("\\p{M}+")

    /** Türkçe karakterleri, büyük/küçük harfi ve noktalamayı yok sayan karşılaştırma biçimi. */
    fun norm(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length)
        for (c0 in s) {
            sb.append(
                when (c0) {
                    'İ', 'I', 'ı' -> 'i'
                    else -> c0
                }
            )
        }
        var t = sb.toString().lowercase(Locale.ROOT)
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(MARKS, "")
        return t.replace(NON_ALNUM, " ").trim()
    }

    fun stripMah(n: String): String =
        n.split(" ").filter { it.isNotEmpty() && it !in MAH }.joinToString(" ")

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
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun score(target: String, cand: String): Double {
        if (target.isEmpty() || cand.isEmpty()) return 0.0
        if (target == cand) return 1.0
        val sim = 1.0 - lev(target, cand).toDouble() / maxOf(target.length, cand.length)
        val contain = " $cand ".contains(" $target ") || " $target ".contains(" $cand ")
        return if (contain) maxOf(sim, 0.85) else sim
    }
}

object Nodes {
    fun label(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString()
        if (!t.isNullOrBlank()) return t.trim()
        return n.contentDescription?.toString()?.trim() ?: ""
    }

    fun walk(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        out.add(n)
        if (depth > 60) return
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, out, depth + 1)
        }
    }

    fun clickableSelfOrAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && d < 8) {
            if (c.isClickable) return c
            c = c.parent
            d++
        }
        return null
    }

    fun scrollableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && d < 12) {
            if (c.isScrollable) return c
            c = c.parent
            d++
        }
        return null
    }

    fun top(n: AccessibilityNodeInfo): Int {
        val r = Rect(); n.getBoundsInScreen(r); return r.top
    }

    fun left(n: AccessibilityNodeInfo): Int {
        val r = Rect(); n.getBoundsInScreen(r); return r.left
    }

    fun dump(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        if (sb.length > 60000 || depth > 60) return
        val r = Rect(); n.getBoundsInScreen(r)
        sb.append("  ".repeat(depth))
        sb.append(n.className?.toString()?.substringAfterLast('.') ?: "?")
        n.text?.let { sb.append(" t='").append(it).append("'") }
        n.contentDescription?.let { sb.append(" d='").append(it).append("'") }
        n.viewIdResourceName?.let { sb.append(" id=").append(it) }
        sb.append(" [")
        if (n.isClickable) sb.append('C')
        if (n.isScrollable) sb.append('S')
        if (n.isEditable) sb.append('E')
        if (n.isCheckable) sb.append(if (n.isChecked) 'K' else 'k')
        if (!n.isEnabled) sb.append('!')
        if (n.isVisibleToUser) sb.append('V')
        sb.append("] ").append(r.toShortString()).append('\n')
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            dump(c, depth + 1, sb)
        }
    }
}
