/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Tiny JSON disk store backing the canvas-matching caches ([IsrcResolver]
 * ISRC map + Apple `CanvasIndex`). One file, two named sections —
 * callers in other packages serialize their own entries so this object
 * never needs cross-package model visibility.
 *
 * Why disk at all: both caches were in-memory, so every process restart
 * re-ran Deezer + Apple searches for the whole play history (slow and
 * flaky on bad networks). A learned ISRC/canvas is stable catalog data —
 * learn once, keep forever.
 *
 * Writes are debounced per section (20s) and always best-effort; reads
 * are defensive (a corrupt file is dropped, never thrown).
 */
internal object IsrcDiskStore {
    private const val TAG = "IsrcDiskStore"
    private const val FILE_NAME = "canvas-match-store.json"
    private const val MAX_ENTRIES_PER_SECTION = 3000
    private const val SAVE_DEBOUNCE_MS = 20_000L

    @Volatile
    private var storeDir: File? = null
    private val lock = Any()
    private val lastSaveMs = mutableMapOf<String, Long>()

    fun init(context: Context) {
        if (storeDir != null) return
        storeDir = context.applicationContext.filesDir
    }

    fun loadSection(name: String): JSONArray? {
        val file = sectionFile() ?: return null
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            root.optJSONArray(name)
        }.getOrElse { error ->
            Timber.tag(TAG).d(error, "Dropping corrupt match store")
            null
        }
    }

    fun saveSection(name: String, entries: JSONArray) {
        val dir = storeDir ?: return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - (lastSaveMs[name] ?: 0L) < SAVE_DEBOUNCE_MS) return
            lastSaveMs[name] = now
            runCatching {
                val file = File(dir, FILE_NAME)
                val root = if (file.exists()) {
                    runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
                } else {
                    JSONObject()
                }
                // Cap: keep the most recently touched entries.
                val capped = JSONArray()
                val start = maxOf(0, entries.length() - MAX_ENTRIES_PER_SECTION)
                for (i in start until entries.length()) {
                    capped.put(entries.opt(i))
                }
                root.put(name, capped)
                file.writeText(root.toString())
            }.onFailure { error ->
                Timber.tag(TAG).d(error, "Failed to save match store section $name")
            }
        }
    }

    private fun sectionFile(): File? = storeDir?.let { File(it, FILE_NAME) }
}
