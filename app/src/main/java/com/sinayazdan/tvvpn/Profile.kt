package com.sinayazdan.tvvpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Profile(
    val id: String,
    val name: String,
    val protocol: String,
    val raw: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("protocol", protocol)
        put("raw", raw)
    }

    companion object {
        fun fromJson(json: JSONObject): Profile = Profile(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "بدون نام"),
            protocol = json.optString("protocol", "?"),
            raw = json.optString("raw", ""),
        )
    }
}

object ProfileStore {
    private const val PREFS = "profiles"
    private const val KEY_LIST = "list"
    private const val KEY_SELECTED = "selected_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): MutableList<Profile> {
        val raw = prefs(context).getString(KEY_LIST, "[]") ?: "[]"
        val result = mutableListOf<Profile>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                result.add(Profile.fromJson(array.getJSONObject(i)))
            }
        }
        return result
    }

    fun save(context: Context, profiles: List<Profile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    fun add(context: Context, profile: Profile) {
        val profiles = list(context)
        profiles.add(0, profile)
        save(context, profiles)
        if (selectedId(context) == null) setSelected(context, profile.id)
    }

    fun delete(context: Context, id: String) {
        val profiles = list(context).filter { it.id != id }
        save(context, profiles)
        if (selectedId(context) == id) {
            setSelected(context, profiles.firstOrNull()?.id)
        }
    }

    fun get(context: Context, id: String?): Profile? =
        id?.let { selected -> list(context).firstOrNull { it.id == selected } }

    fun selectedId(context: Context): String? = prefs(context).getString(KEY_SELECTED, null)

    fun setSelected(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_SELECTED, id).apply()
    }

    fun selected(context: Context): Profile? = get(context, selectedId(context))
}
