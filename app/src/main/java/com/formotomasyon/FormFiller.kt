package com.formotomasyon

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Bir form adımı beklenen koşulu sağlayamayınca fırlatılır; rapor bu bilgilerle üretilir. */
class StepFail(
    val step: String,
    val action: String,
    val expected: String,
    val found: String
) : Exception("$step | $action")

/** Anket formunu erişilebilirlik düğümleri üzerinden adım adım doldurur. */
class FormFiller(
    private val screen: ScreenReader,
    private val cfg: Cfg,
    private val events: AtomicLong
) {
    var currentStep = "-"
        private set
    var listInfo = ""
        private set

    private class Candidate(val name: String, val score: Double)

    private class MahalleScan {
        val seen = LinkedHashMap<String, Candidate>()
        var net = 0
        var scrolls = 0
    }

    private val byPosition = compareBy<AccessibilityNodeInfo>({ Nodes.top(it) }, { Nodes.left(it) })

    private enum class Flow { MAIN, CITY, NOTES_BOTH, NOTES_AUDIT }

    /** Butona basılan anda ekran başlığına tek taramayla bakıp akışı seçer. */
    suspend fun run() {
        when (detectFlow()) {
            Flow.CITY -> runCity()
            Flow.NOTES_BOTH -> runNotes(both = true)
            Flow.NOTES_AUDIT -> runNotes(both = false)
            Flow.MAIN -> runMain()
        }
    }

    private fun detectFlow(): Flow {
        val visible = screen.allNodes().filter { it.isVisibleToUser && !it.isEditable }
        fun classify(nodes: List<AccessibilityNodeInfo>): Flow? {
            val texts = nodes.map { " ${Nodes.normLabel(it)} " }
            return when {
                texts.any { it.contains(" aslen nereli ") } -> Flow.CITY
                texts.any { it.contains(" anketor notu ") } -> Flow.NOTES_BOTH
                texts.any { it.contains(" denetci notu ") } -> Flow.NOTES_AUDIT
                else -> null
            }
        }
        val titled = visible.filter { it.viewIdResourceName?.endsWith("/txt_Soru") == true }
        return classify(titled) ?: classify(visible) ?: Flow.MAIN
    }

    private suspend fun runMain() {
        if (cfg.mahalle.isBlank()) throw StepFail("Ayarlar", "Mahalle kontrolü", "Mahalle dolu olmalı", "boş")
        if (cfg.binaTipi.isBlank()) throw StepFail("Ayarlar", "Bina Tipi kontrolü", "Bina Tipi seçili olmalı", "boş")
        for ((index, title) in STEPS.withIndex()) {
            currentStep = "${index + 1}/${STEPS.size} $title"
            awaitTitle(title)
            fillStep(title)
            clickNext()
        }
    }

    private suspend fun fillStep(title: String) {
        when (title) {
            "İlçe" -> selectIlce()
            "Mahalle" -> selectMahalle(cfg.mahalle)
            "Cadde" -> typeText(cfg.cadde.orDot())
            "Sokak" -> typeText(cfg.sokak.orDot())
            "Bina Adı" -> typeText(cfg.binaAdi.orDot())
            "Bina No" -> typeText(cfg.binaNo.orDot())
            "Daire No" -> typeText(cfg.daireNo.orDot())
            "Bina Tipi" -> pickOption(cfg.binaTipi)
        }
    }

    private fun String.orDot() = ifBlank { "." }

    private fun fail(action: String, expected: String, found: String): Nothing =
        throw StepFail(currentStep, action, expected, found)

    private fun clickNode(n: AccessibilityNodeInfo): Boolean =
        (Nodes.clickableSelfOrAncestor(n) ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)

    private fun clickOrFail(n: AccessibilityNodeInfo, action: String) {
        if (!clickNode(n)) fail(action, "ACTION_CLICK true", "false")
    }

    private fun List<AccessibilityNodeInfo>.labels(separator: String = ", ") =
        joinToString(separator) { Nodes.label(it) }

    private fun formatScore(value: Double) = "%.2f".format(Locale.US, value)

    // ---- bekleme: olay tabanlı, kısa kalp atışıyla

    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (cond()) return true
            val left = end - SystemClock.uptimeMillis()
            if (left <= 0) return false
            val seen = events.get()
            val slice = minOf(left, POLL_SLICE_MS)
            var waited = 0L
            while (events.get() == seen && waited < slice) {
                delay(POLL_TICK_MS)
                waited += POLL_TICK_MS
            }
        }
    }

    private suspend fun <T> waitFor(timeoutMs: Long, find: () -> T?): T? {
        var result: T? = null
        waitUntil(timeoutMs) { result = find(); result != null }
        return result
    }

    // ---- 34. soru: aslen nereli -> Manisa

    private suspend fun runCity() {
        currentStep = "34 Aslen nereli ($CITY)"
        val key = Txt.norm(CITY)
        val option = scrollFind { findCityOption(key) }
            ?: fail("Şehir şıkkı aranıyor", "'$CITY' şıkkı", "liste taranınca bulunamadı; son görünenler: " + cityCandidates().labels())
        listInfo = "Şehir şıkkı: '${Nodes.label(option)}'"
        clickOrFail(option, "Şehir şıkkı tıklanıyor")
        val checked = waitUntil(STEP_MS) { option.refresh(); option.isChecked }
        if (!checked) fail("Şehir şıkkı seçildi mi kontrolü", "isChecked=true", "false")
        clickNext()
    }

    private fun cityCandidates(): List<AccessibilityNodeInfo> =
        radioButtons().ifEmpty {
            screen.allNodes().filter { it.isVisibleToUser && !it.isEditable && Nodes.label(it).isNotEmpty() }
        }

    /** Önce tam eşleşme, sonra kelime sınırlı içerme ("45 - Manisa"). */
    private fun findCityOption(key: String): AccessibilityNodeInfo? {
        val nodes = cityCandidates()
        return nodes.firstOrNull { Nodes.normLabel(it) == key }
            ?: nodes.firstOrNull { " ${Nodes.normLabel(it)} ".contains(" $key ") }
    }

    /** Önce aşağı, bulunamazsa yukarı kaydırarak [find] bir düğüm döndürene kadar arar. */
    private suspend fun scrollFind(find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val end = SystemClock.uptimeMillis() + LONG_LIST_MS
        for (forward in listOf(true, false)) {
            var tries = 0
            while (tries < LONG_LIST_TRIES && SystemClock.uptimeMillis() < end) {
                find()?.let { return it }
                if (!scrollPage(forward)) break
                tries++
            }
        }
        return null
    }

    private suspend fun scrollPage(forward: Boolean): Boolean {
        val before = screen.visibleSignature()
        val list = radioButtons().firstOrNull()?.let { Nodes.scrollableAncestor(it) }
            ?: screen.allNodes().firstOrNull { it.isScrollable }
            ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (!list.performAction(action)) return false
        return waitUntil(PAGE_SCROLL_WAIT_MS) { screen.visibleSignature() != before }
    }

    // ---- 80 / 81. sorular: not alanları

    private suspend fun runNotes(both: Boolean) {
        if (both) {
            currentStep = "80 Anketör notu"
            typeText(".")
            clickNext()
            awaitTitle("Denetçi notu")
        }
        currentStep = "81 Denetçi notu"
        typeText(".")
        clickNext()
    }

    // ---- ekran başlığı

    private fun hasTitle(title: String): Boolean = screen.allNodes().any {
        !it.isEditable && Nodes.normLabel(it).let { n ->
            n == title || n.startsWith("$title ") || n.endsWith(" $title") || n.contains(" $title ")
        }
    }

    private suspend fun awaitTitle(title: String) {
        val normalized = Txt.norm(title)
        if (!waitUntil(STEP_MS) { hasTitle(normalized) }) {
            fail("Ekran başlığı bekleniyor", "'$title' başlıklı ekran", "${STEP_MS / 1000} sn içinde görünmedi")
        }
    }

    // ---- metin kutusu

    private suspend fun typeText(text: String) {
        val field = waitFor(STEP_MS) { screen.allNodes().firstOrNull { it.isEditable && it.isVisibleToUser } }
            ?: fail("Metin kutusu aranıyor", "Görünür EditText", "bulunamadı")
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            fail("ACTION_SET_TEXT", "true dönmesi", "false döndü")
        }
        val applied = waitUntil(TEXT_VERIFY_MS) { field.refresh(); field.text?.toString() == text }
        if (!applied) fail("Yazılan metin doğrulanıyor", "'$text'", "'${field.text}'")
    }

    // ---- dropdown

    private fun findDropdown(): AccessibilityNodeInfo? {
        val nodes = screen.allNodes().filter { it.isVisibleToUser }
        return nodes.firstOrNull { it.className?.toString()?.contains("Spinner") == true }
            ?: nodes.firstOrNull { Nodes.normLabel(it) == PLACEHOLDER }
            ?: nodes.firstOrNull { it.contentDescription?.toString()?.lowercase(Locale.ROOT)?.contains("drop") == true }
    }

    /** Dropdown açılınca ekrana yeni gelen (açılmadan önce olmayan) görünür metin düğümleri. */
    private fun listCandidates(baseline: Set<String>): List<AccessibilityNodeInfo> =
        screen.allNodes().filter {
            it.isVisibleToUser && (it.childCount == 0 || !it.text.isNullOrBlank()) &&
                Nodes.normLabel(it).let { label -> label.isNotEmpty() && label !in baseline }
        }

    private suspend fun openDropdown(baseline: Set<String>): List<AccessibilityNodeInfo> {
        val dropdown = waitFor(STEP_MS) { findDropdown() }
            ?: fail("Dropdown aranıyor", "Spinner veya 'Seçiniz' düğümü", "bulunamadı")
        clickOrFail(dropdown, "Dropdown tıklanıyor")
        val firstItems = waitFor(STEP_MS) { listCandidates(baseline).takeIf { it.isNotEmpty() } }
            ?: fail("Liste açılıyor", "Yeni liste öğeleri", "liste görünmedi")
        delay(LIST_SETTLE_MS)
        return listCandidates(baseline).ifEmpty { firstItems }
    }

    private suspend fun scrollList(baseline: Set<String>, forward: Boolean): Boolean {
        val items = listCandidates(baseline)
        val first = items.firstOrNull() ?: return false
        val list = Nodes.scrollableAncestor(first) ?: return false
        val before = items.labels("|")
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (!list.performAction(action)) return false
        return waitUntil(LIST_SCROLL_WAIT_MS) { listCandidates(baseline).labels("|") != before }
    }

    private suspend fun awaitSelectionApplied() {
        val applied = waitUntil(STEP_MS) { screen.allNodes().none { Nodes.normLabel(it) == PLACEHOLDER } }
        if (!applied) fail("Seçim uygulandı mı kontrolü", "'Seçiniz' yazısı kaybolmalı", "hâlâ ekranda")
    }

    // ---- İlçe

    private fun radioButtons(): List<AccessibilityNodeInfo> =
        screen.allNodes()
            .filter { it.isVisibleToUser && it.className?.toString()?.contains("RadioButton") == true }
            .sortedWith(byPosition)

    private suspend fun selectIlce() {
        val index = if (cfg.ilceIkinci) 1 else 0
        val radios = waitFor(RADIO_WAIT_MS) { radioButtons().takeIf { it.isNotEmpty() } }
        if (radios != null) selectIlceRadio(radios, index) else selectIlceFromDropdown(index)
    }

    private suspend fun selectIlceRadio(radios: List<AccessibilityNodeInfo>, index: Int) {
        val pick = radios.getOrNull(index)
            ?: fail("İlçe şıkkı", "${index + 1}. şık", "ekranda ${radios.size} şık: " + radios.labels())
        listInfo = "İlçe şıkları: ${radios.labels()} → seçilen: ${index + 1}. (${Nodes.label(pick)})"
        clickOrFail(pick, "İlçe şıkkı tıklanıyor")
        val checked = waitUntil(STEP_MS) { pick.refresh(); pick.isChecked }
        if (!checked) fail("İlçe şıkkı seçildi mi kontrolü", "isChecked=true", "false")
    }

    private suspend fun selectIlceFromDropdown(index: Int) {
        val baseline = screen.baselineLabels()
        val rows = openDropdown(baseline)
            .sortedWith(byPosition)
            .filter { Nodes.normLabel(it) != PLACEHOLDER }
            .distinctBy { Nodes.normLabel(it) }
        val pick = rows.getOrNull(index)
            ?: fail("İlçe seçeneği", "${index + 1}. madde", "listede ${rows.size} madde: " + rows.labels())
        listInfo = "İlçe listesi: ${rows.labels()} → seçilen: ${Nodes.label(pick)}"
        clickOrFail(pick, "İlçe maddesi tıklanıyor")
        awaitSelectionApplied()
    }

    // ---- Mahalle

    private suspend fun selectMahalle(target: String) {
        val baseline = screen.baselineLabels()
        openDropdown(baseline)
        val targetKey = Txt.stripMah(Txt.norm(target))
        val deadline = SystemClock.uptimeMillis() + MAH_MS
        val startedAt = SystemClock.uptimeMillis()
        val scan = MahalleScan()

        val cache = cfg.mahCache.split("|")
        if (cache.size == 2 && cache[0] == targetKey) {
            repeat(cache[1].toIntOrNull() ?: 0) { if (scrollList(baseline, true)) scan.net++ }
        }

        val exact = scanForward(baseline, targetKey, deadline, scan)
        if (exact != null) {
            summarize(target, targetKey, scan, startedAt, "tam eşleşme")
            commitMahalle(exact, targetKey, scan)
            return
        }

        val ranked = scan.seen.entries.sortedByDescending { it.value.score }
        val best = ranked.firstOrNull()
        val second = ranked.getOrNull(1)
        summarize(target, targetKey, scan, startedAt, "tam eşleşme yok")
        if (best == null) fail("Mahalle listesi okunuyor", "En az 1 mahalle", "hiç öğe okunamadı")
        if (best.value.score < MIN_SCORE) {
            fail("Mahalle eşleşmesi", "skor ≥ ${formatScore(MIN_SCORE)}", "en iyi: '${best.value.name}' (${formatScore(best.value.score)})")
        }
        if (second != null && best.value.score - second.value.score < MIN_MARGIN) {
            fail(
                "Mahalle eşleşmesi belirsiz", "en iyi ile 2. arasında ≥ ${formatScore(MIN_MARGIN)} fark",
                "'${best.value.name}' (${formatScore(best.value.score)}) / '${second.value.name}' (${formatScore(second.value.score)})"
            )
        }
        returnToBest(baseline, best, targetKey, scan)
    }

    /** Listeyi aşağı kaydırarak tarar; tam eşleşen düğümü bulursa döndürür. */
    private suspend fun scanForward(
        baseline: Set<String>,
        targetKey: String,
        deadline: Long,
        scan: MahalleScan
    ): AccessibilityNodeInfo? {
        while (true) {
            for (node in listCandidates(baseline)) {
                val key = Txt.stripMah(Nodes.normLabel(node))
                if (key.isEmpty()) continue
                if (key !in scan.seen) scan.seen[key] = Candidate(Nodes.label(node), Txt.score(targetKey, key))
                if (key == targetKey) return node
            }
            if (SystemClock.uptimeMillis() > deadline) return null
            if (!scrollList(baseline, true)) return null
            scan.net++
            scan.scrolls++
        }
    }

    /** Tarama bittikten sonra en iyi adaya geri kaydırıp tıklar. */
    private suspend fun returnToBest(
        baseline: Set<String>,
        best: Map.Entry<String, Candidate>,
        targetKey: String,
        scan: MahalleScan
    ) {
        val deadline = SystemClock.uptimeMillis() + MAH_BACK_MS
        while (true) {
            val hit = listCandidates(baseline).firstOrNull { Txt.stripMah(Nodes.normLabel(it)) == best.key }
            if (hit != null) {
                commitMahalle(hit, targetKey, scan)
                return
            }
            if (SystemClock.uptimeMillis() > deadline || !scrollList(baseline, false)) {
                fail("Eşleşen mahalleye geri dönülüyor", "'${best.value.name}' görünür olmalı", "bulunamadı")
            }
            scan.net--
        }
    }

    private suspend fun commitMahalle(node: AccessibilityNodeInfo, targetKey: String, scan: MahalleScan) {
        clickOrFail(node, "Mahalle tıklanıyor")
        cfg.mahCache = "$targetKey|${scan.net}"
        awaitSelectionApplied()
    }

    private fun summarize(target: String, targetKey: String, scan: MahalleScan, startedAt: Long, outcome: String) {
        val top = scan.seen.entries.sortedByDescending { it.value.score }.take(8)
            .joinToString("\n") { "   ${formatScore(it.value.score)}  ${it.value.name}" }
        listInfo = "Mahalle hedef='$target' (norm='$targetKey'), görülen=${scan.seen.size}, kaydırma=${scan.scrolls}, " +
            "süre=${SystemClock.uptimeMillis() - startedAt} ms, $outcome\n   En yakın adaylar:\n$top"
    }

    // ---- şık seçimi (Bina Tipi)

    private suspend fun pickOption(label: String) {
        val normalized = Txt.norm(label)
        val option = findOption(normalized) ?: fail("Şık aranıyor", "'$label'", "ekranda bulunamadı")
        clickOrFail(option, "Şık tıklanıyor")
        delay(OPTION_SETTLE_MS)
    }

    /** Şık görünene kadar sayfayı kaydırarak arar. */
    private suspend fun findOption(normalized: String): AccessibilityNodeInfo? {
        repeat(OPTION_SCROLL_TRIES) {
            screen.allNodes().firstOrNull { it.isVisibleToUser && Nodes.normLabel(it) == normalized }
                ?.let { return it }
            val before = screen.visibleSignature()
            val scrollable = screen.allNodes().firstOrNull { it.isScrollable } ?: return null
            if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return null
            waitUntil(PAGE_SCROLL_WAIT_MS) { screen.visibleSignature() != before }
        }
        return null
    }

    // ---- İleri

    private fun findNext(): AccessibilityNodeInfo? {
        for (node in screen.allNodes()) {
            if (!node.isVisibleToUser) continue
            if (Nodes.normLabel(node) in NEXT_LABELS) return Nodes.clickableSelfOrAncestor(node) ?: node
        }
        return null
    }

    private suspend fun clickNext() {
        val button = waitFor(STEP_MS) { findNext()?.takeIf { it.isEnabled } }
            ?: fail("İleri düğmesi", "etkin 'İleri' düğmesi", "bulunamadı veya pasif")
        clickOrFail(button, "İleri tıklanıyor")
    }

    private companion object {
        const val STEP_MS = 3000L
        const val MAH_MS = 12000L
        const val MAH_BACK_MS = 4000L
        const val TEXT_VERIFY_MS = 1500L
        const val RADIO_WAIT_MS = 1500L
        const val LIST_SCROLL_WAIT_MS = 1200L
        const val PAGE_SCROLL_WAIT_MS = 800L
        const val LIST_SETTLE_MS = 150L
        const val OPTION_SETTLE_MS = 100L
        const val POLL_SLICE_MS = 120L
        const val POLL_TICK_MS = 15L
        const val OPTION_SCROLL_TRIES = 7
        const val LONG_LIST_TRIES = 30
        const val LONG_LIST_MS = 15000L
        const val CITY = "Manisa"
        const val MIN_SCORE = 0.80
        const val MIN_MARGIN = 0.07
        const val PLACEHOLDER = "seciniz"
        val NEXT_LABELS = setOf("ileri", "devam", "devam et", "next", "sonraki")
        val STEPS = listOf("İlçe", "Mahalle", "Cadde", "Sokak", "Bina Adı", "Bina No", "Daire No", "Bina Tipi")
    }
}
