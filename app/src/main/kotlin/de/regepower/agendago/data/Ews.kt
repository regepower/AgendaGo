package de.regepower.agendago.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Xml
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Open tasks of an on-premises Exchange mailbox via EWS (FindItem on the Tasks folder).
 * Auth: Basic or NTLMv2, chosen from the server's WWW-Authenticate header.
 * The network fetch runs in [de.regepower.agendago.widget.EwsSyncJob]; the widget only reads
 * the cached result, so drawing never waits for the network.
 * URL and user are part of the exported config; the password is AES-GCM encrypted with a key
 * in the Android Keystore and kept in a separate, non-exported prefs file.
 */
object Ews {
    /** Keys in the exported widget store (global, not per widget). */
    const val KEY_URL = "ews.url"
    const val KEY_USER = "ews.user"

    private const val STATE = "ews_state"
    private const val KEY_PASSWORD = "pw"
    private const val KEY_SCHEME = "scheme"
    private const val KEY_CACHE = "cache"
    private const val KEY_SYNCED = "synced"
    private const val KEY_ERROR = "error"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "agendago_ews"
    private const val GCM_IV = 12
    private const val GCM_TAG_BITS = 128
    private const val TIMEOUT_MS = 20_000
    private const val WORKSTATION = "AGENDAGO"
    private const val MAX_TASKS = 500

    /** Exchange blue. */
    const val COLOR = 0xFF0078D4.toInt()

    class Task(
        val id: String,
        val title: String,
        /** Due date as epoch millis (Exchange stores the local midnight as UTC), 0 = none. */
        val due: Long,
    )

    class Result(
        val tasks: List<Task>?,
        val error: String?,
    )

    /** Status shown in the settings: last successful sync and last error. */
    class Status(
        val synced: Long,
        val count: Int,
        val error: String?,
    )

    fun url(context: Context): String = WidgetPrefs.store(context).getString(KEY_URL, "").orEmpty()

    fun user(context: Context): String = WidgetPrefs.store(context).getString(KEY_USER, "").orEmpty()

    fun hasPassword(context: Context) = state(context).contains(KEY_PASSWORD)

    fun configured(context: Context) = url(context).isNotBlank() && user(context).isNotBlank() && hasPassword(context)

    fun saveAccount(
        context: Context,
        url: String,
        user: String,
        password: String?,
    ) {
        val changed = url != url(context) || user != user(context) || !password.isNullOrEmpty()
        WidgetPrefs
            .store(context)
            .edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_USER, user.trim())
            .apply()
        val st = state(context).edit()
        if (!password.isNullOrEmpty()) encrypt(password)?.let { st.putString(KEY_PASSWORD, it) }
        // Other server or account: detect the auth scheme again, drop the old tasks.
        if (changed) {
            st
                .remove(KEY_SCHEME)
                .remove(KEY_CACHE)
                .remove(KEY_SYNCED)
                .remove(KEY_ERROR)
        }
        st.apply()
    }

    fun status(context: Context): Status {
        val st = state(context)
        return Status(st.getLong(KEY_SYNCED, 0L), cached(context).size, st.getString(KEY_ERROR, null))
    }

    /** Fetches and caches the open tasks. Network: call from a background thread. */
    fun sync(context: Context): Result {
        val password = state(context).getString(KEY_PASSWORD, null)?.let(::decrypt)
        val url = url(context)
        val user = user(context)
        if (url.isBlank() || user.isBlank() || password == null) return Result(null, "not configured")
        val result = fetch(context, url, user, password)
        val st = state(context).edit()
        if (result.tasks != null) {
            st
                .putString(KEY_CACHE, toJson(result.tasks))
                .putLong(KEY_SYNCED, System.currentTimeMillis())
                .remove(KEY_ERROR)
        } else {
            st.putString(KEY_ERROR, result.error)
        }
        st.apply()
        return result
    }

    /** Cached tasks as widget rows, filtered like the other task sources. */
    fun upcoming(
        context: Context,
        prefs: WidgetPrefs,
        lastDay: LocalDate,
    ): List<Event> {
        val zone = ZoneId.systemDefault()
        val link = webLink(url(context))
        return cached(context).mapNotNull { t ->
            TaskSources.row(
                stableId = TaskSources.EWS_ID_BASE - (t.id.hashCode().toLong() and 0x7FFF_FFFFL),
                title = t.title,
                due = t.due,
                allDay = true,
                day = if (t.due == 0L) null else Instant.ofEpochMilli(t.due).atZone(zone).toLocalDate(),
                color = COLOR,
                link = link,
                prefs = prefs,
                lastDay = lastDay,
            )
        }
    }

    /** Tapping an Exchange task opens Outlook on the web (tasks are not on the phone). */
    private fun webLink(url: String): String? {
        val i = url.indexOf("/EWS/", ignoreCase = true)
        return if (i > 0) url.substring(0, i) + "/owa/" else null
    }

    // ---- HTTP + auth --------------------------------------------------------------------

    private class Response(
        val code: Int,
        val auth: List<String>,
        val body: String,
    )

    private fun fetch(
        context: Context,
        url: String,
        user: String,
        password: String,
    ): Result {
        return try {
            val st = state(context)
            var scheme = st.getString(KEY_SCHEME, null)
            if (scheme == null) {
                scheme = detectScheme(url) ?: return Result(null, "no Basic/NTLM offered")
                st.edit().putString(KEY_SCHEME, scheme).apply()
            }
            val body = FIND_TASKS.trimIndent()
            val response =
                if (scheme == SCHEME_BASIC) {
                    val token = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
                    post(url, body, "Basic $token")
                } else {
                    postNtlm(url, body, user, password)
                }
            when (response.code) {
                HttpURLConnection.HTTP_OK -> parse(response.body)
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    // Scheme may have changed on the server: detect again next time.
                    st.edit().remove(KEY_SCHEME).apply()
                    Result(null, "HTTP 401")
                }
                else -> Result(null, "HTTP ${response.code}" + (soapFault(response.body)?.let { ": $it" } ?: ""))
            }
        } catch (e: IOException) {
            Result(null, e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            Result(null, e.message ?: "invalid URL")
        } catch (e: SecurityException) {
            Result(null, e.message ?: "network not allowed")
        }
    }

    /** Unauthenticated request; the 401 lists the accepted schemes. Basic wins (one round trip). */
    private fun detectScheme(url: String): String? {
        val offered = post(url, "", null).auth.map { it.trim().substringBefore(' ').lowercase() }
        return when {
            "basic" in offered -> SCHEME_BASIC
            "ntlm" in offered -> SCHEME_NTLM
            else -> null
        }
    }

    /**
     * NTLM is connection based: type 1 → 401 with the challenge → type 3 with the body on the
     * same keep-alive connection (the 401 body is drained so the connection gets reused).
     */
    private fun postNtlm(
        url: String,
        body: String,
        user: String,
        password: String,
    ): Response {
        val challenge =
            post(url, "", "NTLM ${Ntlm.type1()}")
                .auth
                .firstOrNull { it.startsWith("NTLM ", ignoreCase = true) }
                ?.substring(NTLM_PREFIX)
                ?.let(Ntlm::parseType2)
                ?: return Response(HttpURLConnection.HTTP_UNAUTHORIZED, emptyList(), "")
        val (domain, name) =
            when {
                '\\' in user -> user.substringBefore('\\') to user.substringAfter('\\')
                else -> "" to user // UPN (user@domain) goes as is
            }
        return post(url, body, "NTLM ${Ntlm.type3(challenge, name, domain, password, WORKSTATION)}")
    }

    private fun post(
        url: String,
        body: String,
        authorization: String?,
    ): Response {
        // No disconnect(): it would close the keep-alive socket the NTLM handshake depends on.
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
        conn.setRequestProperty("Accept", "text/xml")
        if (authorization != null) conn.setRequestProperty("Authorization", authorization)
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        val auth =
            conn.headerFields.entries
                .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                ?.value
                .orEmpty()
        val stream: InputStream? = if (code < HttpURLConnection.HTTP_BAD_REQUEST) conn.inputStream else conn.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return Response(code, auth, text)
    }

    // ---- XML ----------------------------------------------------------------------------

    private fun parse(xml: String): Result =
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(xml.reader())
            val tasks = mutableListOf<Task>()
            var id = ""
            var title = ""
            var due = 0L
            var status = ""
            var inTask = false
            var responseCode: String? = null
            var message: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG ->
                        when (parser.name) {
                            "Task" -> {
                                inTask = true
                                id = ""
                                title = ""
                                due = 0L
                                status = ""
                            }
                            "ItemId" -> if (inTask) id = parser.getAttributeValue(null, "Id").orEmpty()
                            "Subject" -> if (inTask) title = parser.nextText().trim()
                            "DueDate" -> if (inTask) due = parseTime(parser.nextText())
                            "Status" -> if (inTask) status = parser.nextText().trim()
                            "ResponseCode" -> responseCode = parser.nextText().trim()
                            "MessageText" -> message = parser.nextText().trim()
                        }
                    XmlPullParser.END_TAG ->
                        if (parser.name == "Task" && inTask) {
                            inTask = false
                            if (status != "Completed") tasks += Task(id, title, due)
                        }
                }
            }
            if (responseCode != null && responseCode != "NoError") {
                Result(null, message ?: responseCode)
            } else {
                Result(tasks, null)
            }
        } catch (e: XmlPullParserException) {
            Result(null, "invalid response")
        } catch (e: IOException) {
            Result(null, "invalid response")
        }

    private fun soapFault(xml: String): String? = Regex("<faultstring[^>]*>([^<]*)<").find(xml)?.groupValues?.get(1)

    private fun parseTime(text: String): Long =
        try {
            Instant.parse(text.trim()).toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            0L
        }

    // ---- cache --------------------------------------------------------------------------

    private fun cached(context: Context): List<Task> {
        val json = state(context).getString(KEY_CACHE, null) ?: return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { Task(it.getString("id"), it.getString("t"), it.getLong("d")) }
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun toJson(tasks: List<Task>): String =
        JSONArray()
            .also { a ->
                tasks.take(MAX_TASKS).forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("d", it.due)) }
            }.toString()

    private fun state(context: Context): SharedPreferences = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    // ---- password (Android Keystore) ----------------------------------------------------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun encrypt(plain: String): String? =
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IOException) {
            null
        }

    private fun decrypt(stored: String): String? =
        try {
            val raw = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV))
            String(cipher.doFinal(raw, GCM_IV, raw.size - GCM_IV), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    private const val SCHEME_BASIC = "basic"
    private const val SCHEME_NTLM = "ntlm"
    private const val NTLM_PREFIX = 5

    /** Open tasks of the default Tasks folder; completed ones are dropped while parsing. */
    private const val FIND_TASKS = """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
            xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
            xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
          <soap:Header><t:RequestServerVersion Version="Exchange2010_SP1"/></soap:Header>
          <soap:Body>
            <m:FindItem Traversal="Shallow">
              <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:AdditionalProperties>
                  <t:FieldURI FieldURI="item:Subject"/>
                  <t:FieldURI FieldURI="task:DueDate"/>
                  <t:FieldURI FieldURI="task:Status"/>
                </t:AdditionalProperties>
              </m:ItemShape>
              <m:IndexedPageItemView MaxEntriesReturned="500" Offset="0" BasePoint="Beginning"/>
              <m:ParentFolderIds><t:DistinguishedFolderId Id="tasks"/></m:ParentFolderIds>
            </m:FindItem>
          </soap:Body>
        </soap:Envelope>
    """
}
