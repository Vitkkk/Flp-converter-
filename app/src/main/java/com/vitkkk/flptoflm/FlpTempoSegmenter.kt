package com.vitkkk.flptoflm

import kotlin.math.max
import kotlin.math.min

/** A constant-tempo portion that will become one independent FLM file. */
data class FlpTempoSegment(
    val index: Int,
    val startTick: Long,
    val endTick: Long,
    val bpm: Double,
    val project: FlpProject
)

/**
 * Flattens Playlist pattern placements into one synthetic pattern per BPM part.
 * Every part starts at tick zero. Notes crossing a boundary are trimmed and
 * restarted at zero in the following file, so sustained notes and slide chains
 * are not silently lost at the cut.
 */
object FlpTempoSegmenter {
    fun split(project: FlpProject, scan: FlpTempoScan): List<FlpTempoSegment> {
        val songEnd = songEndTick(project).coerceAtLeast(project.ppq.toLong() * 4L)
        val changes = normalizeChanges(project, scan, songEnd)
        if (changes.size <= 1) {
            return listOf(
                FlpTempoSegment(
                    index = 1,
                    startTick = 0L,
                    endTick = songEnd,
                    bpm = project.tempo,
                    project = project
                )
            )
        }

        return changes.mapIndexedNotNull { index, change ->
            val end = changes.getOrNull(index + 1)?.tick?.coerceAtMost(songEnd) ?: songEnd
            if (end <= change.tick) return@mapIndexedNotNull null
            val notes = collectSegmentNotes(project, change.tick, end)
            val synthetic = project.copy(
                tempo = change.bpm,
                patterns = listOf(
                    FlpPattern(
                        id = 1,
                        name = "BPM ${formatBpm(change.bpm)} - Parte ${index + 1}",
                        length = end - change.tick,
                        notes = notes
                    )
                ),
                playlist = emptyList()
            )
            FlpTempoSegment(
                index = index + 1,
                startTick = change.tick,
                endTick = end,
                bpm = change.bpm,
                project = synthetic
            )
        }.ifEmpty {
            listOf(FlpTempoSegment(1, 0L, songEnd, project.tempo, project))
        }
    }

    private fun normalizeChanges(
        project: FlpProject,
        scan: FlpTempoScan,
        songEnd: Long
    ): List<FlpTempoChange> {
        val source = scan.changes.ifEmpty { listOf(FlpTempoChange(0L, project.tempo)) }
        val byTick = linkedMapOf<Long, Double>()
        byTick[0L] = project.tempo
        source.forEach { change ->
            if (change.tick in 0 until songEnd) byTick[change.tick] = change.bpm
        }

        val result = mutableListOf<FlpTempoChange>()
        byTick.entries.sortedBy { it.key }.forEach { (tick, bpm) ->
            if (result.lastOrNull()?.bpm != bpm) result += FlpTempoChange(tick, bpm)
        }
        return result
    }

    private fun collectSegmentNotes(
        project: FlpProject,
        segmentStart: Long,
        segmentEnd: Long
    ): List<FlpNote> {
        val output = mutableListOf<FlpNote>()
        val patterns = project.patterns.associateBy { it.id }

        fun appendAbsolute(note: FlpNote, absoluteStart: Long, maximumEnd: Long?) {
            val sourceEnd = absoluteStart + note.length.coerceAtLeast(1L)
            val boundedEnd = maximumEnd?.let { min(sourceEnd, it) } ?: sourceEnd
            val clippedStart = max(absoluteStart, segmentStart)
            val clippedEnd = min(boundedEnd, segmentEnd)
            if (clippedEnd <= clippedStart) return

            output += note.copy(
                position = clippedStart - segmentStart,
                length = (clippedEnd - clippedStart).coerceAtLeast(1L)
            )
        }

        if (project.playlist.isNotEmpty()) {
            for (item in project.playlist) {
                val pattern = patterns[item.patternId] ?: continue
                val itemEnd = if (item.length > 0L) item.position + item.length else null
                for (note in pattern.notes) {
                    appendAbsolute(note, item.position + note.position, itemEnd)
                }
            }
        } else {
            for (pattern in project.patterns) {
                for (note in pattern.notes) appendAbsolute(note, note.position, null)
            }
        }

        return output.sortedWith(
            compareBy<FlpNote> { it.position }
                .thenBy { it.rackChannel }
                .thenBy { it.key }
                .thenBy { it.slide }
        )
    }

    private fun songEndTick(project: FlpProject): Long {
        if (project.playlist.isNotEmpty()) {
            val patterns = project.patterns.associateBy { it.id }
            var maximum = 0L
            for (item in project.playlist) {
                maximum = max(maximum, item.position + item.length)
                val pattern = patterns[item.patternId] ?: continue
                for (note in pattern.notes) {
                    val noteEnd = item.position + note.position + note.length
                    val bounded = if (item.length > 0L) min(noteEnd, item.position + item.length) else noteEnd
                    maximum = max(maximum, bounded)
                }
            }
            return maximum
        }

        return project.patterns.asSequence()
            .flatMap { it.notes.asSequence() }
            .maxOfOrNull { it.position + it.length }
            ?: 0L
    }

    private fun formatBpm(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.3f".format(value)
}
