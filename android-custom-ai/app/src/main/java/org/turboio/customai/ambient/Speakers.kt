package org.turboio.customai.ambient

import org.turboio.customai.journal.searchNormalize

/**
 * Who is "Você"? Diarization labels (0, 1, …) are anonymous and valid only inside one request.
 *  1. VPU (glasses vpuMask, hypothesis: wearer's own voice) — fraction of each speaker's words on VPU frames;
 *  2. proximity — head-mounted mics hear the wearer much louder: mean RMS per speaker;
 *  3. otherwise unknown. Never guess: an ambiguous result stays "unknown".
 */
object Speakers {
    data class Attribution(val wearer: Int?, val method: String, val confidence: Double)
    data class Utterance(val startWall: Long, val endWall: Long, val text: String, val speaker: String, val label: String, val confidence: Double)

    private fun frames(plan: ChunkPlan, w: Word): IntRange {
        val a = ChunkPlanner.frameAt(plan, w.startMs); val b = ChunkPlanner.frameAt(plan, maxOf(w.startMs, w.endMs - 1))
        return a..maxOf(a, b)
    }

    fun attribute(words: List<Word>, plan: ChunkPlan, flags: ByteArray, levels: IntArray, m: SegmentManifest): Attribution {
        val bySpeaker = words.filter { it.speaker != null }.groupBy { it.speaker!! }
        if (bySpeaker.isEmpty()) return Attribution(null, "sem-diarizacao", 0.0)
        fun inRange(f: Int) = f in flags.indices
        val vpuPresent = m.providesWearer && flags.any { it.toInt() and FrameFlags.WEARER != 0 }
        if (vpuPresent) {
            val score = bySpeaker.mapValues { (_, ws) ->
                val voiced = ws.count { w -> val r = frames(plan, w).filter(::inRange); r.isNotEmpty() && r.count { flags[it].toInt() and FrameFlags.WEARER != 0 } * 2 >= r.size }
                voiced.toDouble() / ws.size
            }
            val ranked = score.entries.sortedByDescending { it.value }
            val best = ranked.first(); val second = ranked.getOrNull(1)?.value ?: 0.0
            return if (best.value >= 0.6 && bySpeaker.getValue(best.key).size >= 3 && second < 0.3) Attribution(best.key, "vpu", best.value)
            else Attribution(null, "vpu-ambiguo", best.value)
        }
        if (m.headMounted && bySpeaker.size >= 2) {
            val level = bySpeaker.filter { it.value.size >= 3 }.mapValues { (_, ws) ->
                val r = ws.flatMap { frames(plan, it).filter(::inRange) }
                if (r.isEmpty()) 0.0 else r.sumOf { levels[it].toDouble() } / r.size
            }
            if (level.size >= 2) {
                val ranked = level.entries.sortedByDescending { it.value }
                val ratio = ranked[0].value / maxOf(1.0, ranked[1].value)
                return if (ratio >= 1.8) Attribution(ranked[0].key, "proximidade", minOf(1.0, (ratio - 1) / 2)) else Attribution(null, "proximidade-ambigua", 0.0)
            }
        }
        return Attribution(null, "indeterminado", 0.0)
    }

    /** Groups consecutive words of one speaker (split on pauses > [pauseMs]) into journal utterances with wall times. */
    fun utterances(words: List<Word>, a: Attribution, plan: ChunkPlan, m: SegmentManifest, pauseMs: Long = 1500): List<Utterance> {
        val labels = HashMap<Int, String>(); var next = 0
        fun label(s: Int?): Pair<String, String> = when {
            s == null -> "unknown" to ""
            s == a.wearer -> "wearer" to "Você"
            else -> (if (a.wearer != null) "other" else "unknown") to labels.getOrPut(s) { "Pessoa " + ('A' + next++) }
        }
        val out = ArrayList<Utterance>()
        var group = ArrayList<Word>()
        fun flush() {
            if (group.isEmpty()) return
            val (speaker, lbl) = label(group[0].speaker)
            val text = group.joinToString(" ") { it.text }.replace(Regex("\\s+([,.!?;:])"), "$1").trim()
            if (text.isNotEmpty()) out.add(Utterance(m.wallAt(ChunkPlanner.frameAt(plan, group.first().startMs)),
                m.wallAt(ChunkPlanner.frameAt(plan, group.last().endMs)), text, speaker, lbl, if (speaker == "wearer") a.confidence else 0.0))
            group = ArrayList()
        }
        for (w in words) {
            val last = group.lastOrNull()
            if (last != null && (w.speaker != last.speaker || w.startMs - last.endMs > pauseMs || group.sumOf { it.text.length } > 600)) flush()
            group.add(w)
        }
        flush()
        return out
    }

    private val HALLUCINATIONS = listOf("legendas pela comunidade", "amara org", "obrigado por assistir", "obrigada por assistir",
        "inscreva se no canal", "ative o sininho", "legenda adriana", "transcricao e legendas")

    /** Typical STT inventions on silence/noise. */
    fun hallucination(text: String): Boolean {
        val t = searchNormalize(text).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        return t.isEmpty() || HALLUCINATIONS.any { t.contains(it) }
    }
}
