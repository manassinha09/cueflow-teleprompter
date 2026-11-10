package com.novaflow.cueflow

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedScript(val id: String, val title: String, val markdown: String)

internal data class ScriptLibrary(val scripts: List<SavedScript>, val selectedId: String?) {
    val selected: SavedScript? get() = scripts.firstOrNull { it.id == selectedId }

    fun save(preferences: SharedPreferences) {
        val items = JSONArray()
        scripts.forEach { script ->
            items.put(JSONObject().put("id", script.id).put("title", script.title).put("markdown", script.markdown))
        }
        val stored = JSONObject().put("scripts", items).put("selected", selectedId ?: JSONObject.NULL)
        preferences.edit().putString(KEY, stored.toString()).apply()
    }

    companion object {
        private const val KEY = "script_library"

        fun load(preferences: SharedPreferences, sample: String): ScriptLibrary {
            preferences.getString(KEY, null)?.let { raw ->
                runCatching {
                    val stored = JSONObject(raw)
                    val items = stored.getJSONArray("scripts")
                    val scripts = List(items.length()) { index ->
                        val script = items.getJSONObject(index)
                        SavedScript(script.getString("id"), script.getString("title"), script.getString("markdown"))
                    }
                    val selected = stored.optString("selected").takeIf { id -> scripts.any { it.id == id } }
                    return ScriptLibrary(scripts, selected ?: scripts.firstOrNull()?.id)
                }
            }
            val legacy = preferences.getString("script", null)
            val original = SavedScript("original", if (legacy == null) "Sample script" else "My script", legacy ?: sample)
            return ScriptLibrary(listOf(original), original.id)
        }
    }
}
