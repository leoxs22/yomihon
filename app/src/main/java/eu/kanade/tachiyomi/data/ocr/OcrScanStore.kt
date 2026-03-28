package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.AndroidPreferenceStore

internal class OcrScanStore(
    context: Context,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val preferenceStore = AndroidPreferenceStore(context)
    private val queuePreference = preferenceStore.getString(QUEUE_KEY, "")
    private val pausedPreference = preferenceStore.getBoolean(PAUSED_KEY, false)
    private val legacyActiveChapterPreference = preferenceStore.getLong(ACTIVE_CHAPTER_KEY, NO_ACTIVE_CHAPTER)

    fun snapshot(): OcrScanStoreSnapshot {
        return OcrScanStoreSnapshot(
            entries = OcrScanStoreSerializer.restore(
                json = json,
                encodedEntries = queuePreference.get(),
                legacyActiveChapterId = legacyActiveChapterPreference.get().takeIf { it != NO_ACTIVE_CHAPTER },
            ),
            isPaused = pausedPreference.get(),
        )
    }

    suspend fun save(snapshot: OcrScanStoreSnapshot) {
        mutex.withLock {
            queuePreference.set(
                OcrScanStoreSerializer.serialize(
                    json = json,
                    entries = snapshot.entries,
                ),
            )
            pausedPreference.set(snapshot.isPaused)
            legacyActiveChapterPreference.set(NO_ACTIVE_CHAPTER)
        }
    }

    companion object {
        // Keep the persisted keys stable so existing queued scan work survives upgrades.
        internal const val QUEUE_KEY = "ocr_preprocess_queue"
        internal const val ACTIVE_CHAPTER_KEY = "ocr_preprocess_active_chapter"
        internal const val PAUSED_KEY = "ocr_preprocess_paused"
        private const val NO_ACTIVE_CHAPTER = -1L
    }
}

internal object OcrScanStoreSerializer {
    fun restore(
        json: Json,
        encodedEntries: String,
        legacyActiveChapterId: Long? = null,
    ): List<OcrScanQueueEntry> {
        val persistedEntries = decodeEntries(json, encodedEntries)
        val normalizedPersistedEntries = persistedEntries.map { entry ->
            entry.copy(
                state = if (entry.state == OcrScanQueueEntry.State.SCANNING) {
                    OcrScanQueueEntry.State.QUEUED
                } else {
                    entry.state
                },
                lastError = null,
            )
        }

        return buildList {
            legacyActiveChapterId?.let { add(OcrScanQueueEntry(it, OcrScanQueueEntry.State.QUEUED)) }
            normalizedPersistedEntries
                .filterNot { entry -> entry.chapterId == legacyActiveChapterId }
                .forEach { entry ->
                    if (none { existing -> existing.chapterId == entry.chapterId }) {
                        add(entry)
                    }
                }
        }
    }

    fun serialize(
        json: Json,
        entries: List<OcrScanQueueEntry>,
    ): String {
        val persistedEntries = entries
            .distinctBy(OcrScanQueueEntry::chapterId)
            .map { entry ->
                PersistedOcrScanQueueEntry(
                    chapterId = entry.chapterId,
                    state = entry.state,
                )
            }
        return json.encodeToString(persistedEntries)
    }

    private fun decodeEntries(
        json: Json,
        encodedEntries: String,
    ): List<OcrScanQueueEntry> {
        if (encodedEntries.isBlank()) {
            return emptyList()
        }

        return try {
            json.decodeFromString<List<PersistedOcrScanQueueEntry>>(encodedEntries)
                .distinctBy(PersistedOcrScanQueueEntry::chapterId)
                .map { entry ->
                    OcrScanQueueEntry(
                        chapterId = entry.chapterId,
                        state = entry.state,
                    )
                }
        } catch (_: Throwable) {
            encodedEntries.split(',')
                .mapNotNull(String::toLongOrNull)
                .distinct()
                .map { chapterId ->
                    OcrScanQueueEntry(
                        chapterId = chapterId,
                        state = OcrScanQueueEntry.State.QUEUED,
                    )
                }
        }
    }
}

@Serializable
private data class PersistedOcrScanQueueEntry(
    val chapterId: Long,
    val state: OcrScanQueueEntry.State,
)
