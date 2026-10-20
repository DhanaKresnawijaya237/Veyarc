package com.veyarc.recording

/** Pause boundaries split analysis so setup time never becomes a wait in the workflow. */
fun RecordingJournalSnapshot.segmentAfter(eventCount: Int): RecordingJournalSnapshot {
    require(eventCount in 0..events.size)
    val segment = events.drop(eventCount)
    val origin = segment.firstOrNull()?.let {
        when (it) {
            is RecordingJournalLine.Touch -> it.elapsedMillis
            is RecordingJournalLine.ForegroundPackage -> it.elapsedMillis
            is RecordingJournalLine.Header -> error("Unexpected journal header")
        }
    } ?: 0L
    val rebased = segment.map {
        when (it) {
            is RecordingJournalLine.Touch -> it.copy(elapsedMillis = it.elapsedMillis - origin)
            is RecordingJournalLine.ForegroundPackage -> it.copy(elapsedMillis = it.elapsedMillis - origin)
            is RecordingJournalLine.Header -> error("Unexpected journal header")
        }
    }
    return copy(events = rebased, cursor = rebased.fold(RecordingJournalCursor()) { cursor, line -> cursor.accept(line) })
}
