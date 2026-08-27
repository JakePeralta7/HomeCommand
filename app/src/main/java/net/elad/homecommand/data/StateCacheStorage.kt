package net.elad.homecommand.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persistence for [TopicHistory] snapshots; shares the backup-excluded prefs file. */
object StateCacheStorage {
    private const val PREFS_NAME = "my_automations"
    private const val KEY_STATE_HISTORY = "state_history"

    private val gson = Gson()
    private val historyType = object : TypeToken<Map<String, List<String>>>() {}.type

    suspend fun save(
        context: Context,
        snapshot: Map<String, List<String>>,
    ): Unit =
        withContext(Dispatchers.IO) {
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STATE_HISTORY, gson.toJson(snapshot))
                .apply()
        }

    suspend fun load(context: Context): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_STATE_HISTORY, null)
                ?.let { json -> gson.fromJson<Map<String, List<String>>>(json, historyType) }
                .orEmpty()
        }
}
