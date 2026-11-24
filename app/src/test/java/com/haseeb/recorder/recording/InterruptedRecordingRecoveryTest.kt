package com.haseeb.recorder.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import com.haseeb.recorder.recording.InterruptedRecordingRecovery.Result

class InterruptedRecordingRecoveryTest {
    private class Session {
        val events = mutableListOf<String>()
        var playable = true
        var marker = true
        var source = true
        var published = false
        var notice: Result? = null
        var failure: String? = null
        private fun event(name: String) {
            events += name
            if (failure == name) throw IOException(name)
        }
        fun recover(reportSuccess: Boolean = true): Result = InterruptedRecordingRecovery(
            isPlayable = { event("inspect"); playable },
            publish = { event("publish"); published = true },
            rememberNotice = { result -> event("notice:$result"); notice = result },
            discard = { event("discard"); source = false },
            clear = { event("clear"); marker = false }
        ).recover(reportSuccess)
    }

    @Test fun finalizedVideoIsPublishedAndReportedBeforeClearingMarker() {
        val session = Session()
        assertEquals(Result.SAVED, session.recover())
        assertEquals(listOf("inspect", "publish", "notice:SAVED", "clear"), session.events)
        assertTrue(session.published)
        assertFalse(session.marker)
    }

    @Test fun missingIndexIsRemovedOnlyAfterPersistingExplanation() {
        val session = Session().apply { playable = false }
        assertEquals(Result.REMOVED, session.recover())
        assertEquals(listOf("inspect", "notice:REMOVED", "discard", "clear"), session.events)
        assertFalse(session.published)
        assertFalse(session.source)
        assertFalse(session.marker)
    }

    @Test fun normalSuccessfulStopDoesNotProduceInterruptionNotice() {
        val session = Session()
        assertEquals(Result.SAVED, session.recover(reportSuccess = false))
        assertEquals(listOf("inspect", "publish", "clear"), session.events)
        assertNull(session.notice)
    }

    @Test fun failedNormalStopStillExplainsRemoval() {
        val session = Session().apply { playable = false }
        assertEquals(Result.REMOVED, session.recover(reportSuccess = false))
        assertEquals(Result.REMOVED, session.notice)
    }

    @Test fun inaccessibleSourceIsRetainedForRetry() {
        val session = Session().apply { failure = "inspect" }
        assertEquals(Result.RETRY, session.recover())
        assertTrue(session.source)
        assertTrue(session.marker)
        assertFalse(session.published)
        assertEquals(listOf("inspect", "notice:RETRY"), session.events)
    }

    @Test fun fullDiskOrUnavailableDestinationRetainsVideo() {
        val session = Session().apply { failure = "publish" }
        assertEquals(Result.RETRY, session.recover())
        assertTrue(session.marker)
        assertTrue(session.source)
        assertFalse(session.events.contains("discard"))
        assertEquals(Result.RETRY, session.notice)
    }

    @Test fun failedPublishCanBeRetriedAfterStorageIsAvailable() {
        val session = Session().apply { failure = "publish" }
        session.recover()
        session.failure = null
        assertEquals(Result.SAVED, session.recover())
        assertTrue(session.published)
        assertFalse(session.marker)
        assertEquals(Result.SAVED, session.notice)
    }

    @Test fun deletionFailureKeepsMarkerSoCleanupCanRetry() {
        val session = Session().apply { playable = false; failure = "discard" }
        assertEquals(Result.RETRY, session.recover())
        assertTrue(session.marker)
        assertTrue(session.source)
        assertFalse(session.events.contains("clear"))
    }

    @Test fun failedDeletionCanBeRetried() {
        val session = Session().apply { playable = false; failure = "discard" }
        session.recover()
        session.failure = null
        assertEquals(Result.REMOVED, session.recover())
        assertFalse(session.source)
        assertFalse(session.marker)
    }

    @Test fun explanationPersistenceFailurePreventsSilentDeletion() {
        val session = Session().apply { playable = false; failure = "notice:REMOVED" }
        assertEquals(Result.RETRY, session.recover())
        assertTrue(session.source)
        assertTrue(session.marker)
        assertFalse(session.events.contains("discard"))
    }

    @Test fun savedNoticeFailureKeepsRecoveryMarker() {
        val session = Session().apply { failure = "notice:SAVED" }
        assertEquals(Result.RETRY, session.recover())
        assertTrue(session.published)
        assertTrue(session.marker)
        assertTrue(session.source)
    }

    @Test fun markerClearFailureRetainsSourceAfterPublication() {
        val session = Session().apply { failure = "clear" }
        assertEquals(Result.RETRY, session.recover())
        assertTrue(session.published)
        assertTrue(session.source)
        assertTrue(session.marker)
    }
}
