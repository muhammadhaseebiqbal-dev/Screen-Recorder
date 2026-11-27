package com.haseeb.recorder.recording

/** Keeps the recovery marker until publication or cleanup has actually succeeded. */
internal class InterruptedRecordingRecovery(
    private val isPlayable: () -> Boolean,
    private val publish: () -> Unit,
    private val rememberNotice: (Result) -> Unit,
    private val discard: () -> Unit,
    private val clear: () -> Unit
) {
    enum class Result { SAVED, REMOVED, RETRY }

    companion object {
        fun canBeginNewSession(isFinishing: Boolean, hasPendingSession: Boolean): Boolean =
            !isFinishing && !hasPendingSession
    }

    fun recover(reportSuccess: Boolean = true): Result {
        return try {
            if (isPlayable()) {
                publish()
                if (reportSuccess) rememberNotice(Result.SAVED)
                clear()
                Result.SAVED
            } else {
                // Persist the explanation before deleting anything, even if notifications are denied.
                rememberNotice(Result.REMOVED)
                discard()
                clear()
                Result.REMOVED
            }
        } catch (_: Exception) {
            rememberNotice(Result.RETRY)
            Result.RETRY
        }
    }
}
