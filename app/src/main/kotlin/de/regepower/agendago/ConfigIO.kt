package de.regepower.agendago

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Export/import of one SharedPreferences file as JSON (typed values), used by the
 * header's save/load buttons. Generic: copy unchanged into other apps (only the package line changes).
 * [keep]: device-specific keys (boot counters, calendar IDs …) that are neither exported nor overwritten.
 * Pass a set (`keep = DEVICE_KEYS::contains`) or any predicate (`{ it.endsWith(".cals") }`).
 */
object ConfigIO {
    const val MIME = "application/json"
    private const val FORMAT = 1

    fun toJson(
        sp: SharedPreferences,
        app: String,
        keep: (String) -> Boolean = { false },
    ): String {
        val values = JSONObject()
        for ((key, value) in sp.all) {
            if (keep(key)) continue
            val entry =
                when (value) {
                    is Boolean -> typed("b", value)
                    is Int -> typed("i", value)
                    is Long -> typed("l", value)
                    is Float -> typed("f", value.toDouble())
                    is String -> typed("s", value)
                    is Set<*> -> typed("ss", JSONArray(value.map { it.toString() }))
                    else -> null
                } ?: continue
            values.put(key, entry)
        }
        return JSONObject()
            .put("app", app)
            .put("format", FORMAT)
            .put("values", values)
            .toString(2)
    }

    /** Replaces all values with the file's content. False if [json] is not a valid config of [app]. */
    fun fromJson(
        sp: SharedPreferences,
        json: String,
        app: String,
        keep: (String) -> Boolean = { false },
    ): Boolean {
        val parsed =
            try {
                parse(json, app)
            } catch (_: JSONException) {
                null
            } ?: return false
        val editor = sp.edit()
        sp.all.keys
            .filterNot(keep)
            .forEach { editor.remove(it) }
        parsed.filterKeys { !keep(it) }.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        return editor.commit()
    }

    private fun typed(
        type: String,
        value: Any,
    ) = JSONObject().put("t", type).put("v", value)

    /** Validates everything before anything is written. */
    private fun parse(
        json: String,
        app: String,
    ): Map<String, Any>? {
        val root = JSONObject(json)
        if (root.optString("app") != app) return null
        val values = root.getJSONObject("values")
        val out = LinkedHashMap<String, Any>()
        for (key in values.keys()) {
            val e = values.getJSONObject(key)
            out[key] =
                when (e.getString("t")) {
                    "b" -> e.getBoolean("v")
                    "i" -> e.getInt("v")
                    "l" -> e.getLong("v")
                    "f" -> e.getDouble("v").toFloat()
                    "s" -> e.getString("v")
                    "ss" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> return null
                }
        }
        return out
    }
}
