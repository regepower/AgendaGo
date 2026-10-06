package de.regepower.agendago.data

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.accounts.OperationCanceledException
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Google Tasks without Tasks.org: Tasks REST API with a token from the Google account on the
 * phone (AccountManager, no Play Services library). Google issues the token only if the
 * Cloud project has an Android OAuth client for this package and signing certificate.
 * Like EWS the network fetch runs in the sync job; the widget reads the cache.
 */
object GTasks {
    private const val STATE = "gtasks_state"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_LISTS = "lists"
    private const val KEY_CACHE = "cache"
    private const val KEY_SYNCED = "synced"
    private const val KEY_ERROR = "error"
    private const val ACCOUNT_TYPE = "com.google"
    const val SCOPE = "oauth2:https://www.googleapis.com/auth/tasks.readonly"
    private const val API = "https://tasks.googleapis.com/tasks/v1"
    private const val TIMEOUT_MS = 20_000
    private const val MAX_PAGES = 10
    private const val MAX_TASKS = 1000

    /** Google Tasks blue. */
    const val COLOR = 0xFF1A73E8.toInt()

    /** Error text when Google wants the user to confirm access again (shown in the settings). */
    const val NEEDS_CONSENT = "consent"

    private class Task(
        val id: String,
        val title: String,
        /** Due date (Google stores only the date), null = none. */
        val due: LocalDate?,
        val listKey: Long,
        val link: String?,
    )

    class Result(
        val ok: Boolean,
        val error: String?,
    )

    fun account(context: Context): String? = state(context).getString(KEY_ACCOUNT, null)

    fun configured(context: Context) = account(context) != null

    fun setAccount(
        context: Context,
        name: String?,
    ) {
        val e = state(context).edit()
        if (name == null) {
            e.clear()
        } else if (name != account(context)) {
            e.clear().putString(KEY_ACCOUNT, name)
        }
        e.apply()
    }

    /** Google list ids are strings; the widget prefs keep Long ids, so lists are keyed by hash. */
    fun listKey(id: String): Long = id.hashCode().toLong() and 0xFFFF_FFFFL

    fun lists(context: Context): List<TaskList> {
        val json = state(context).getString(KEY_LISTS, null) ?: return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { TaskList(it.getLong("k"), it.getString("t"), account(context).orEmpty(), COLOR) }
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun status(context: Context): Ews.Status {
        val st = state(context)
        return Ews.Status(st.getLong(KEY_SYNCED, 0L), cached(context).size, st.getString(KEY_ERROR, null))
    }

    /**
     * Interactive authorization after choosing the account: Android shows Google's consent
     * screen if needed. [done] runs on the main thread with true when a token was issued.
     */
    fun authorize(
        activity: Activity,
        done: (Boolean, String?) -> Unit,
    ) {
        val name = account(activity) ?: return done(false, null)
        AccountManager.get(activity).getAuthToken(
            Account(name, ACCOUNT_TYPE),
            SCOPE,
            null,
            activity,
            { future ->
                val error =
                    try {
                        if (future.result.getString(AccountManager.KEY_AUTHTOKEN) != null) null else "no token"
                    } catch (e: OperationCanceledException) {
                        "cancelled"
                    } catch (e: AuthenticatorException) {
                        e.message ?: "authenticator"
                    } catch (e: IOException) {
                        e.message ?: "network"
                    }
                done(error == null, error)
            },
            null,
        )
    }

    /** Fetches lists and open tasks into the cache. Network: call from a background thread. */
    fun sync(context: Context): Result {
        val name = account(context) ?: return Result(false, "no account")
        val result =
            try {
                val token = token(context, name) ?: return save(context, Result(false, NEEDS_CONSENT), null, null)
                var response = fetchAll(token)
                if (response == null) {
                    // Expired token: drop it from the cache and try once more.
                    AccountManager.get(context).invalidateAuthToken(ACCOUNT_TYPE, token)
                    val fresh = token(context, name) ?: return save(context, Result(false, NEEDS_CONSENT), null, null)
                    response = fetchAll(fresh) ?: return save(context, Result(false, "HTTP 401"), null, null)
                }
                return save(context, Result(true, null), response.first, response.second)
            } catch (e: IOException) {
                Result(false, e.message ?: e.javaClass.simpleName)
            } catch (e: JSONException) {
                Result(false, "invalid response")
            } catch (e: AuthenticatorException) {
                Result(false, e.message ?: "authenticator")
            } catch (e: OperationCanceledException) {
                Result(false, NEEDS_CONSENT)
            } catch (e: SecurityException) {
                // Account no longer visible to the app (removed or access revoked).
                Result(false, e.message ?: "account")
            }
        return save(context, result, null, null)
    }

    /** Non-interactive token; null if the user has to confirm access first. */
    private fun token(
        context: Context,
        name: String,
    ): String? {
        val bundle =
            AccountManager
                .get(context)
                .getAuthToken(Account(name, ACCOUNT_TYPE), SCOPE, null, false, null, null)
                .result
        return bundle.getString(AccountManager.KEY_AUTHTOKEN)
    }

    /** Lists + open tasks; null on HTTP 401 (token expired). */
    private fun fetchAll(token: String): Pair<JSONArray, JSONArray>? {
        val lists = JSONArray()
        val tasks = JSONArray()
        val listItems = get("$API/users/@me/lists?maxResults=100", token) ?: return null
        val items = listItems.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val list = items.getJSONObject(i)
            val id = list.getString("id")
            val key = listKey(id)
            lists.put(JSONObject().put("k", key).put("t", list.optString("title")))
            var page: String? = null
            var pages = 0
            do {
                val url =
                    "$API/lists/${enc(id)}/tasks?showCompleted=false&showHidden=false&maxResults=100" +
                        (page?.let { "&pageToken=${enc(it)}" } ?: "")
                val body = get(url, token) ?: return null
                val ts = body.optJSONArray("items") ?: JSONArray()
                for (j in 0 until ts.length()) {
                    val t = ts.getJSONObject(j)
                    if (t.optString("status") == "completed" || t.optBoolean("deleted") || tasks.length() >= MAX_TASKS) continue
                    tasks.put(
                        JSONObject()
                            .put("id", t.getString("id"))
                            .put("t", t.optString("title"))
                            .put("d", t.optString("due"))
                            .put("k", key)
                            .put("l", t.optString("webViewLink")),
                    )
                }
                page = body.optString("nextPageToken").takeIf { it.isNotEmpty() }
            } while (page != null && ++pages < MAX_PAGES)
        }
        return lists to tasks
    }

    private fun get(
        url: String,
        token: String,
    ): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) return null
            if (code != HttpURLConnection.HTTP_OK) {
                val msg = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw IOException("HTTP $code" + apiError(msg)?.let { ": $it" }.orEmpty())
            }
            return JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    /** "Tasks API has not been used in project …" and similar, so setup errors are readable. */
    private fun apiError(body: String): String? =
        try {
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
        } catch (e: JSONException) {
            null
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun save(
        context: Context,
        result: Result,
        lists: JSONArray?,
        tasks: JSONArray?,
    ): Result {
        val e = state(context).edit()
        if (result.ok && lists != null && tasks != null) {
            e
                .putString(KEY_LISTS, lists.toString())
                .putString(KEY_CACHE, tasks.toString())
                .putLong(KEY_SYNCED, System.currentTimeMillis())
                .remove(KEY_ERROR)
        } else {
            e.putString(KEY_ERROR, result.error)
        }
        e.apply()
        return result
    }

    private fun cached(context: Context): List<Task> {
        val json = state(context).getString(KEY_CACHE, null) ?: return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Task(o.getString("id"), o.getString("t"), parseDue(o.optString("d")), o.getLong("k"), o.optString("l").ifEmpty { null })
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /** "2026-10-07T00:00:00.000Z": only the date part is meaningful. */
    private fun parseDue(s: String): LocalDate? =
        if (s.isEmpty()) {
            null
        } else {
            try {
                Instant.parse(s).atZone(ZoneOffset.UTC).toLocalDate()
            } catch (e: DateTimeParseException) {
                null
            }
        }

    fun upcoming(
        context: Context,
        prefs: WidgetPrefs,
        lastDay: LocalDate,
    ): List<Event> =
        cached(context).mapNotNull { t ->
            if (prefs.gtaskListIds.isNotEmpty() && t.listKey !in prefs.gtaskListIds) return@mapNotNull null
            TaskSources.row(
                stableId = TaskSources.GTASKS_ID_BASE - (t.id.hashCode().toLong() and 0x7FFF_FFFFL),
                title = t.title,
                due = 0L,
                allDay = true,
                day = t.due,
                color = COLOR,
                link = t.link,
                prefs = prefs,
                lastDay = lastDay,
            )
        }

    private fun state(context: Context): SharedPreferences = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
}
