package com.filecleaner.app.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists user-defined search filters (name + query string) so they can be
 * quickly reapplied from the Browse screen.
 *
 * Stored as a JSON array in SharedPreferences. Max [MAX_SAVED_SEARCHES] entries.
 */
object SavedSearchManager {

    private const val PREFS_NAME = "saved_searches"
    private const val KEY_SEARCHES = "searches"
    private const val MAX_SAVED_SEARCHES = 20

    data class SavedSearch(
        val name: String,
        val query: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns all saved searches sorted by most recently created first. */
    fun getSavedSearches(context: Context): List<SavedSearch> {
        val json = prefs(context).getString(KEY_SEARCHES, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SavedSearch(
                    name = obj.getString("name"),
                    query = obj.getString("query"),
                    createdAt = obj.optLong("createdAt", 0)
                )
            }.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Saves a new search filter. Replaces any existing entry with the same name. */
    fun saveSearch(context: Context, name: String, query: String) {
        if (name.isBlank() || query.isBlank()) return
        val searches = getSavedSearches(context).toMutableList()
        searches.removeAll { it.name == name }
        searches.add(0, SavedSearch(name, query))

        // Cap at maximum
        val capped = searches.take(MAX_SAVED_SEARCHES)
        persist(context, capped)
    }

    /** Deletes a saved search by name. */
    fun deleteSearch(context: Context, name: String) {
        val searches = getSavedSearches(context).toMutableList()
        searches.removeAll { it.name == name }
        persist(context, searches)
    }

    /** Returns true if any saved searches exist. */
    fun hasSavedSearches(context: Context): Boolean =
        getSavedSearches(context).isNotEmpty()

    private fun persist(context: Context, searches: List<SavedSearch>) {
        val array = JSONArray()
        for (s in searches) {
            array.put(JSONObject().apply {
                put("name", s.name)
                put("query", s.query)
                put("createdAt", s.createdAt)
            })
        }
        prefs(context).edit().putString(KEY_SEARCHES, array.toString()).apply()
    }
}
