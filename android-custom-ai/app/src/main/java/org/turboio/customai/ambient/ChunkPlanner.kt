package org.turboio.customai.ambient

/**
 * Turns per-frame speech flags of one sealed segment into transcription requests.
 *
 *  1. speech runs → spans (runs closer than mergeGap joined), padded, too-little-speech spans dropped;
 *  2. a span longer than maxSpeech is cut at the longest pause after splitSearchFrom; with no pause at all,
 *     a forced cut with [ChunkPolicy.overlapMs] overlap (the duplicate words are removed after transcription);
 *  3. spans are packed into chunks (≤ maxSpeech of audio) with short separators, so silence is never billed.
 */
object ChunkPlanner {
    data class Span(val start: Int, val end: Int, val forced: Boolean = false)

    fun spans(speech: BooleanArray, frameMs: Int, policy: ChunkPolicy): List<Span> {
        val n = speech.size
        fun frames(ms: Long) = ((ms + frameMs - 1) / frameMs).toInt()
        // 1. raw runs
        val runs = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (!speech[i]) { i++; continue }
            val s = i; while (i < n && speech[i]) i++
            runs.add(intArrayOf(s, i))
        }
        // merge close runs
        val merged = ArrayList<IntArray>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && (r[0] - last[1]) * frameMs.toLong() < policy.mergeGapMs) last[1] = r[1] else merged.add(r.copyOf())
        }
        // drop little speech, pad, clamp
        val pad = frames(policy.padMs)
        val padded = merged.filter { r -> (r[0] until r[1]).count { speech[it] } * frameMs.toLong() >= policy.minSpeechMs }
            .map { intArrayOf(maxOf(0, it[0] - pad), minOf(n, it[1] + pad)) }
        val clean = ArrayList<IntArray>()
        for (r in padded) { val last = clean.lastOrNull(); if (last != null && r[0] <= last[1]) last[1] = maxOf(last[1], r[1]) else clean.add(r) }
        // 2. split long spans
        val maxF = (policy.maxSpeechMs / frameMs).toInt()
        val searchF = (policy.splitSearchFromMs / frameMs).toInt()
        val overlapF = frames(policy.overlapMs)
        val out = ArrayList<Span>()
        for (r in clean) {
            var start = r[0]; val end = r[1]; var forced = false
            while (end - start > maxF) {
                var bestLen = 0; var bestMid = -1
                var j = start + searchF
                while (j < start + maxF) {
                    if (!speech[j]) {
                        val s = j; while (j < start + maxF && !speech[j]) j++
                        if (j - s > bestLen) { bestLen = j - s; bestMid = (s + j) / 2 }
                    } else j++
                }
                if (bestMid > start) { out.add(Span(start, bestMid, forced)); start = bestMid; forced = false }
                else { val cut = start + maxF; out.add(Span(start, cut, forced)); start = maxOf(start + 1, cut - overlapF); forced = true }
            }
            out.add(Span(start, end, forced))
        }
        return out
    }

    fun plan(speech: BooleanArray, frameMs: Int, policy: ChunkPolicy): List<ChunkPlan> {
        val chunks = ArrayList<ChunkPlan>()
        var pieces = ArrayList<Piece>(); var cursor = 0L; var forced = false
        for (span in spans(speech, frameMs, policy)) {
            val len = (span.end - span.start).toLong() * frameMs
            val needed = if (pieces.isEmpty()) len else cursor + policy.separatorMs + len
            if (pieces.isNotEmpty() && needed > policy.maxSpeechMs) {
                chunks.add(ChunkPlan(pieces, frameMs, forced)); pieces = ArrayList(); cursor = 0; forced = false
            }
            if (pieces.isNotEmpty()) cursor += policy.separatorMs
            pieces.add(Piece(cursor, span.start, span.end)); cursor += len
            forced = forced || span.forced
        }
        if (pieces.isNotEmpty()) chunks.add(ChunkPlan(pieces, frameMs, forced))
        return chunks
    }

    /** Maps a time inside the assembled chunk back to an absolute frame index of the segment. */
    fun frameAt(plan: ChunkPlan, chunkMs: Long): Int {
        var best = plan.pieces.first()
        for (p in plan.pieces) if (p.chunkStartMs <= chunkMs) best = p
        val offset = ((chunkMs - best.chunkStartMs) / plan.frameMs).toInt()
        return (best.frameStart + offset).coerceIn(best.frameStart, maxOf(best.frameStart, best.frameEnd - 1))
    }
}
