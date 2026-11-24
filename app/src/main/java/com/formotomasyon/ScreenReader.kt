package com.formotomasyon

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/** Hedef uygulamanın pencerelerini ve düğüm ağacını okur. */
class ScreenReader(
    private val service: AccessibilityService,
    private val targetPackage: () -> String
) {
    fun roots(): List<AccessibilityNodeInfo> {
        val pkg = targetPackage()
        val fromWindows = service.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == pkg }
        if (fromWindows.isNotEmpty()) return fromWindows
        return listOfNotNull(service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg })
    }

    fun allNodes(): List<AccessibilityNodeInfo> = buildList {
        for (root in roots()) Nodes.walk(root, this)
    }

    /** Görünür düğümlerin etiketlerinden oluşan, ekran değişimini anlamak için kullanılan imza. */
    fun visibleSignature(): String =
        allNodes().filter { it.isVisibleToUser }.joinToString("|") { Nodes.label(it) }

    fun baselineLabels(): Set<String> =
        allNodes().map { Nodes.normLabel(it) }.filter { it.isNotEmpty() }.toSet()

    fun windowsInfo(): String = buildString {
        for (w in service.windows) {
            append("  type=${w.type} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} ")
            append("pkg=${w.root?.packageName} title=${w.title}\n")
        }
    }

    fun appendTrees(sb: StringBuilder, roots: List<AccessibilityNodeInfo> = roots()) {
        for ((i, root) in roots.withIndex()) {
            sb.append("--- pencere kökü #$i ---\n")
            Nodes.dump(root, 0, sb)
        }
    }

    fun dumpTarget(): String = buildString {
        append("Hedef paket: ${targetPackage()}\nPencereler:\n").append(windowsInfo())
        val roots = roots()
        if (roots.isEmpty()) append("(hedef pakete ait pencere bulunamadı)\n")
        appendTrees(this, roots)
    }
}
