package com.haseeb.recorder.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises real MediaExtractor, SharedPreferences, and MediaStore on an Android device. */
@RunWith(AndroidJUnit4::class)
class RecordingSessionStoreTest {
    private lateinit var context: Context
    private lateinit var name: String
    private var staging: File? = null
    private val state get() = context.getSharedPreferences("recording_session", Context.MODE_PRIVATE)

    @Before fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        waitForRecovery()
        check(!RecordingSessionStore.hasSession(context)) { "Finish an existing debug recording before testing" }
        state.edit().clear().commit()
        name = "ScreenRecord_test_${UUID.randomUUID()}.mp4"
    }

    @After fun removeOnlyTestOutput() {
        waitForRecovery()
        context.contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name))
        staging?.delete()
        state.edit().clear().commit()
        RecordingSessionStore.acknowledgeNotice(context)
    }

    private fun begin(treeUri: String? = null): File = RecordingSessionStore.begin(context, name, treeUri)
        .also { staging = it }

    private fun writeFixture(file: File) {
        InstrumentationRegistry.getInstrumentation().context.assets.open("recovery-fixture.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    private fun waitForRecovery() {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (RecordingSessionStore.isFinishing && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertFalse("Recovery timed out", RecordingSessionStore.isFinishing)
    }

    private fun outputs(): List<Pair<android.net.Uri, Int>> {
        val rows = mutableListOf<Pair<android.net.Uri, Int>>()
        context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { cursor ->
            while (cursor.moveToNext()) rows += android.content.ContentUris.withAppendedId(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)) to cursor.getInt(1)
        }
        return rows
    }

    @Test fun activeRecordingIsPrivateAndMarkerIsDurableBeforeMuxerWrites() {
        val file = begin()
        file.writeBytes(ByteArray(4096))
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        assertTrue(file.name.endsWith(".partial"))
        assertEquals(file.absolutePath, state.getString("staging_path", null))
        assertTrue(outputs().isEmpty())
    }

    @Test fun incompleteMp4IsRemovedWithPersistentExplanation() {
        val file = begin().apply { writeText("no moov index") }
        RecordingSessionStore.recoverInterruptedSession(context)
        waitForRecovery()
        assertFalse(file.exists())
        assertFalse(RecordingSessionStore.hasSession(context))
        assertEquals("RecordingSessionStore_removed", state.getString("notice", null))
        assertNotNull(RecordingSessionStore.notice(context))
        assertTrue(outputs().isEmpty())
    }

    @Test fun emptyRecordingIsNotPublished() {
        begin().writeBytes(byteArrayOf())
        RecordingSessionStore.finishAsync(context)
        waitForRecovery()
        assertTrue(outputs().isEmpty())
        assertEquals("RecordingSessionStore_removed", state.getString("notice", null))
    }

    @Test fun finalizedInterruptedRecordingIsPublishedAndPlayable() {
        val file = begin().also(::writeFixture)
        RecordingSessionStore.recoverInterruptedSession(context)
        waitForRecovery()
        assertFalse(file.exists())
        assertFalse(RecordingSessionStore.hasSession(context))
        assertEquals("RecordingSessionStore_recovered", state.getString("notice", null))
        val rows = outputs()
        assertEquals(1, rows.size)
        assertEquals(0, rows.single().second)
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(rows.single().first, "r")!!.use {
                extractor.setDataSource(it.fileDescriptor)
            }
            assertTrue(extractor.trackCount > 0)
            extractor.selectTrack(0)
            assertTrue(extractor.sampleTime >= 0)
        } finally { extractor.release() }
    }

    @Test fun normalStopPublishesWithoutInterruptionNotice() {
        begin().also(::writeFixture)
        RecordingSessionStore.finishAsync(context)
        waitForRecovery()
        assertEquals(1, outputs().size)
        assertNull(RecordingSessionStore.notice(context))
    }

    @Test fun memoryPressureStopReportsSavedVideo() {
        begin().also(::writeFixture)
        RecordingSessionStore.finishAsync(context, lowMemory = true)
        waitForRecovery()
        assertEquals(1, outputs().size)
        assertEquals("RecordingSessionStore_memory_saved", state.getString("notice", null))
    }

    @Test fun revokedCustomFolderAccessRetainsPrivateVideoForRetry() {
        val file = begin("content://com.android.externalstorage.documents/tree/primary%3ANonexistentRecorderTest")
            .also(::writeFixture)
        RecordingSessionStore.recoverInterruptedSession(context)
        waitForRecovery()
        assertTrue(file.exists())
        assertTrue(RecordingSessionStore.hasSession(context))
        assertEquals("RecordingSessionStore_retry", state.getString("notice", null))
        assertTrue(outputs().isEmpty())
    }

    @Test fun interruptionDuringCopyRetriesSamePendingRow() {
        val file = begin().also(::writeFixture)
        val pending = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ScreenRecorder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        context.contentResolver.openOutputStream(pending)!!.use { it.write(byteArrayOf(0, 1, 2)) }
        state.edit().putString("output_uri", pending.toString()).putBoolean("copied", false).commit()
        RecordingSessionStore.recoverInterruptedSession(context)
        waitForRecovery()
        assertEquals(listOf(pending to 0), outputs())
        assertFalse(file.exists())
    }

    @Test fun interruptedPublicationDoesNotDuplicateAlreadyPublishedRow() {
        begin().also(::writeFixture)
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ScreenRecorder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        context.contentResolver.openOutputStream(uri)!!.use { output -> staging!!.inputStream().use { it.copyTo(output) } }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        state.edit().putString("output_uri", uri.toString()).putBoolean("copied", true).commit()
        RecordingSessionStore.recoverInterruptedSession(context)
        waitForRecovery()
        assertEquals(listOf(uri to 0), outputs())
    }

    @Test fun missingDestinationIsRecreatedFromRetainedSource() {
        begin().also(::writeFixture)
        state.edit().putString("output_uri", "content://media/external/video/media/999999999")
            .putBoolean("copied", true).commit()
        RecordingSessionStore.recoverInterruptedSession(context)
        waitForRecovery()
        assertEquals(1, outputs().size)
        assertFalse(RecordingSessionStore.hasSession(context))
    }

    @Test fun recoveryNoticeSurvivesUntilUserAcknowledgesIt() {
        begin().writeText("unfinished")
        RecordingSessionStore.recoverInterruptedSession(context)
        waitForRecovery()
        assertNotNull(RecordingSessionStore.notice(context))
        assertNotNull(RecordingSessionStore.notice(context))
        RecordingSessionStore.acknowledgeNotice(context)
        assertNull(RecordingSessionStore.notice(context))
    }

    @Test fun interruptionAfterPrivateSourceCleanupDoesNotDeleteSavedVideo() {
        val file = begin().also(::writeFixture)
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ScreenRecorder")
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        })!!
        context.contentResolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
        state.edit().putString("output_uri", uri.toString()).putBoolean("published", true).commit()
        assertTrue(file.delete())
        RecordingSessionStore.recoverInterruptedSession(context)
        waitForRecovery()
        assertEquals(listOf(uri to 0), outputs())
        assertFalse(RecordingSessionStore.hasSession(context))
    }

    @Test fun legacyUnfinalizedOutputIsRemovedWithNoticeOnUpgrade() {
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        })!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write("missing moov".toByteArray()) }
        state.edit().putString("output_uri", uri.toString()).commit()
        assertTrue(RecordingSessionStore.hasSession(context))
        RecordingSessionStore.recoverInterruptedSession(context)
        waitForRecovery()
        assertTrue(outputs().isEmpty())
        assertFalse(RecordingSessionStore.hasSession(context))
        assertEquals("RecordingSessionStore_removed", state.getString("notice", null))
    }

    @Test fun acknowledgingOldDialogDoesNotClearNewerRecoveryNotice() {
        state.edit().putString("notice", "RecordingSessionStore_removed").commit()
        val oldMessage = RecordingSessionStore.notice(context)
        state.edit().putString("notice", "RecordingSessionStore_memory_saved").commit()
        RecordingSessionStore.acknowledgeNotice(context, oldMessage)
        assertEquals("RecordingSessionStore_memory_saved", state.getString("notice", null))
    }
}
