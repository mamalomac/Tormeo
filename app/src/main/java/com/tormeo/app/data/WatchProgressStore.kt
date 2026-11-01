package com.tormeo.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class WatchEntry(
    val request: PlayRequest,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
}

/** Remembers playback position per video and the last source used, for "Continue watching". */
class WatchProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<WatchEntry>> = _entries

    private fun load(): List<WatchEntry> = runCatching {
        StremioJson.decodeFromString(ListSerializer(WatchEntry.serializer()), prefs.getString("list", "[]")!!)
    }.getOrDefault(emptyList())

    fun get(videoId: String): WatchEntry? = _entries.value.firstOrNull { it.request.videoId == videoId }

    fun save(request: PlayRequest, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val list = _entries.value.filterNot { it.request.videoId == request.videoId }.toMutableList()
        list.add(0, WatchEntry(request, positionMs, durationMs, System.currentTimeMillis()))
        commit(list.take(60))
    }

    fun remove(videoId: String) = commit(_entries.value.filterNot { it.request.videoId == videoId })

    /** Started but not finished, newest first, one entry per show. */
    fun continueWatching(): List<WatchEntry> =
        _entries.value.filter { it.fraction in 0.01f..0.94f }.distinctBy { it.request.metaId }.take(20)

    private fun commit(list: List<WatchEntry>) {
        _entries.value = list
        prefs.edit().putString("list", StremioJson.encodeToString(ListSerializer(WatchEntry.serializer()), list)).apply()
    }
}
