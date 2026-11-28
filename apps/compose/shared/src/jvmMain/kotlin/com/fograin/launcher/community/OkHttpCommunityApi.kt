package com.fograin.launcher.community

import com.fograin.launcher.platform.AppLogger
import com.fograin.launcher.platform.LocalPreferences
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

private val JsonMedia = "application/json; charset=utf-8".toMediaTypeOrNull()

/**
 * OkHttp implementation of the Misskey endpoints used by the community page.
 *
 * The launcher already ships OkHttp through Coil, so this avoids a second HTTP
 * stack. Every call is off-main-thread; failures are normalized into
 * [CommunityException] so the UI never displays raw JSON.
 */
class OkHttpCommunityApi(
    private val logger: AppLogger,
    private val baseUrl: String = CommunityConfig.BASE_URL,
) : CommunityApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun meta(): CommunityMeta = withContext(Dispatchers.IO) {
        CommunityJson.decodeFromString(
            postJson("meta", buildJsonObject {}, token = null).toString(),
        )
    }

    override suspend fun stats(): CommunityStats = withContext(Dispatchers.IO) {
        CommunityJson.decodeFromString(
            postJson("stats", buildJsonObject {}, token = null).toString(),
        )
    }

    override suspend fun timeline(
        tab: CommunityTab,
        token: String?,
        untilId: String?,
        limit: Int,
    ): List<CommunityNote> = withContext(Dispatchers.IO) {
        val endpoint = when (tab) {
            CommunityTab.LOCAL -> "notes/local-timeline"
            CommunityTab.GLOBAL -> "notes/global-timeline"
            // This is Misskey's home timeline: notes from people the account
            // follows. `hybrid-timeline` also injects the local timeline, which
            // made the "Following" tab show the same posts as "Local".
            CommunityTab.FOLLOWING -> "notes/timeline"
        }
        val body = buildJsonObject {
            put("limit", limit)
            put("withRenotes", true)
            if (tab == CommunityTab.LOCAL) {
                put("withReplies", true)
                put("allowPartial", true)
            }
            if (tab == CommunityTab.FOLLOWING) {
                put("allowPartial", true)
            }
            untilId?.takeIf { it.isNotBlank() }?.let { put("untilId", it) }
        }
        val payload = postJson(endpoint, body, token)
        CommunityJson.decodeFromString<List<CommunityNote>>(payload.toString())
    }

    override suspend fun showNote(noteId: String, token: String?): CommunityNote =
        withContext(Dispatchers.IO) {
            val payload = postJson(
                "notes/show",
                buildJsonObject { put("noteId", noteId) },
                token,
            )
            CommunityJson.decodeFromString<CommunityNote>(payload.toString())
        }

    override suspend fun children(
        noteId: String,
        token: String?,
        untilId: String?,
        limit: Int,
    ): List<CommunityNote> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("noteId", noteId)
            put("limit", limit)
            untilId?.takeIf { it.isNotBlank() }?.let { put("untilId", it) }
        }
        val payload = postJson("notes/children", body, token)
        CommunityJson.decodeFromString<List<CommunityNote>>(payload.toString())
    }

    override suspend fun createNote(
        token: String,
        draft: CommunityNoteDraft,
    ): CommunityNote = withContext(Dispatchers.IO) {
        val payload = postJson("notes/create", noteBody(draft), token)
        CommunityJson.decodeFromString<CommunityCreatedNote>(payload.toString()).createdNote
    }

    override suspend fun scheduleNote(
        token: String,
        draft: CommunityNoteDraft,
        scheduledAtEpochMillis: Long,
    ) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("scheduledAt", scheduledAtEpochMillis)
            put("isActuallyScheduled", true)
            noteFields(draft).forEach { (key, value) -> put(key, value) }
        }
        val request = Request.Builder()
            .url("$baseUrl/api/notes/drafts/create")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JsonMedia))
            .build()
        execute(request) { }
    }

    override suspend fun deleteNote(token: String, noteId: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/api/notes/delete")
                .header("Authorization", "Bearer $token")
                .post(
                    buildJsonObject { put("noteId", noteId) }
                        .toString()
                        .toRequestBody(JsonMedia),
                )
                .build()
            execute(request) { }
        }

    override suspend fun votePoll(token: String, noteId: String, choice: Int) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/api/notes/polls/vote")
                .header("Authorization", "Bearer $token")
                .post(
                    buildJsonObject {
                        put("noteId", noteId)
                        put("choice", choice)
                    }.toString().toRequestBody(JsonMedia),
                )
                .build()
            execute(request) { }
        }

    override suspend fun reportAbuse(token: String, userId: String, comment: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/api/users/report-abuse")
                .header("Authorization", "Bearer $token")
                .post(
                    buildJsonObject {
                        put("userId", userId)
                        put("comment", comment)
                    }.toString().toRequestBody(JsonMedia),
                )
                .build()
            execute(request) { }
        }

    override suspend fun uploadFile(
        token: String,
        path: String,
        name: String,
        isSensitive: Boolean,
        mimeType: String,
    ): CommunityFile = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            throw CommunityException("FILE_UNREADABLE", "无法读取所选文件")
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("force", "true")
            .addFormDataPart("name", name)
            .apply {
                if (isSensitive) addFormDataPart("isSensitive", "true")
            }
            .addFormDataPart(
                "file",
                name,
                file.asRequestBody(mimeType.toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url("$baseUrl/api/drive/files/create")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        execute(request) { text ->
            CommunityJson.decodeFromString<CommunityFile>(text)
        }
    }

    override suspend fun me(token: String): CommunityUser = withContext(Dispatchers.IO) {
        val payload = postJson("i", buildJsonObject {}, token)
        CommunityJson.decodeFromString<CommunityUser>(payload.toString())
    }

    override suspend fun showUser(userId: String, token: String?): CommunityUser =
        withContext(Dispatchers.IO) {
            val payload = postJson(
                "users/show",
                buildJsonObject { put("userId", userId) },
                token,
            )
            CommunityJson.decodeFromString<CommunityUser>(payload.toString())
        }

    override suspend fun userNotes(
        userId: String,
        token: String?,
        limit: Int,
    ): List<CommunityNote> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("userId", userId)
            put("limit", limit)
            put("withRenotes", true)
            put("withReplies", false)
            put("allowPartial", true)
        }
        val payload = postJson("users/notes", body, token)
        CommunityJson.decodeFromString<List<CommunityNote>>(payload.toString())
    }

    override suspend fun followUser(token: String, userId: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/api/following/create")
                .header("Authorization", "Bearer $token")
                .post(
                    buildJsonObject { put("userId", userId) }
                        .toString()
                        .toRequestBody(JsonMedia),
                )
                .build()
            execute(request) { }
        }

    override suspend fun unfollowUser(token: String, userId: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/api/following/delete")
                .header("Authorization", "Bearer $token")
                .post(
                    buildJsonObject { put("userId", userId) }
                        .toString()
                        .toRequestBody(JsonMedia),
                )
                .build()
            execute(request) { }
        }

    override suspend fun downloadFile(url: String, name: String): String =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://")) {
                throw CommunityException("INVALID_MEDIA_URL", "媒体地址不可用")
            }
            val suffix = name.substringAfterLast('.', "")
                .takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
                ?.let { ".$it" }
                ?: ".bin"
            val temp = File.createTempFile("fograin-community-", suffix)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "FograinLauncher/community")
                .build()
            try {
                client.newBuilder()
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            throw CommunityException(
                                "HTTP_${response.code}",
                                "HTTP ${response.code}",
                                response.code,
                            )
                        }
                        val body = response.body
                            ?: throw CommunityException("EMPTY_MEDIA", "媒体内容为空")
                        body.byteStream().use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                temp.absolutePath
            } catch (error: Throwable) {
                temp.delete()
                throw error
            }
        }

    override suspend fun searchUsers(
        query: String,
        token: String?,
        limit: Int,
    ): List<CommunityUser> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("username", query)
            put("limit", limit)
            put("detail", false)
        }
        // `users/search` currently throws a server-side null error on this
        // instance; the username/host search is public and returns the same
        // lightweight user rows.
        val payload = postJson("users/search-by-username-and-host", body, token)
        CommunityJson.decodeFromString<List<CommunityUser>>(payload.toString())
    }

    override suspend fun checkMiAuth(session: String): CommunityMiAuthCheck =
        withContext(Dispatchers.IO) {
            val payload = postJson(
                "miauth/$session/check",
                buildJsonObject {},
                token = null,
            )
            CommunityJson.decodeFromString<CommunityMiAuthCheck>(payload.toString())
        }

    override suspend fun revokeToken(token: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/i/revoke-token")
            .header("Authorization", "Bearer $token")
            .post(
                buildJsonObject { put("token", token) }
                    .toString()
                    .toRequestBody(JsonMedia),
            )
            .build()
        execute(request) { }
    }

    override suspend fun createReaction(
        token: String,
        noteId: String,
        reaction: String,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/notes/reactions/create")
            .header("Authorization", "Bearer $token")
            .post(
                buildJsonObject {
                    put("noteId", noteId)
                    put("reaction", reaction)
                }.toString().toRequestBody(JsonMedia),
            )
            .build()
        execute(request) { }
    }

    override suspend fun deleteReaction(token: String, noteId: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/api/notes/reactions/delete")
                .header("Authorization", "Bearer $token")
                .post(
                    buildJsonObject { put("noteId", noteId) }
                        .toString()
                        .toRequestBody(JsonMedia),
                )
                .build()
            execute(request) { }
        }

    private fun noteBody(draft: CommunityNoteDraft): JsonObject = buildJsonObject {
        noteFields(draft).forEach { (key, value) -> put(key, value) }
    }

    /**
     * Misskey's create and draft endpoints share nearly all parameters. The
     * two exceptions (scheduledAt/isActuallyScheduled) are added by
     * [scheduleNote].
     */
    private fun noteFields(draft: CommunityNoteDraft): Map<String, JsonElement> = buildMap {
        draft.text?.takeIf { it.isNotBlank() }?.let { put("text", JsonPrimitive(it)) }
        put("visibility", JsonPrimitive(draft.visibility.wireValue))
        draft.cw?.takeIf { it.isNotBlank() }?.let { put("cw", JsonPrimitive(it)) }
        draft.replyId?.takeIf { it.isNotBlank() }?.let { put("replyId", JsonPrimitive(it)) }
        draft.renoteId?.takeIf { it.isNotBlank() }?.let { put("renoteId", JsonPrimitive(it)) }
        draft.reactionAcceptance.wireValue?.let { put("reactionAcceptance", JsonPrimitive(it)) }
        if (draft.fileIds.isNotEmpty()) {
            put(
                "fileIds",
                buildJsonArray { draft.fileIds.forEach { add(JsonPrimitive(it)) } },
            )
        }
        draft.poll?.let { poll ->
            put(
                "poll",
                buildJsonObject {
                    put(
                        "choices",
                        buildJsonArray { poll.choices.forEach { add(JsonPrimitive(it)) } },
                    )
                    put("multiple", poll.multiple)
                    poll.expiresAtEpochMillis?.let { put("expiresAt", it) }
                },
            )
        }
    }

    private fun postJson(
        endpoint: String,
        body: JsonObject,
        token: String?,
    ): JsonElement {
        val request = Request.Builder()
            .url("$baseUrl/api/$endpoint")
            .header("User-Agent", "FograinLauncher/community")
            .apply { token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .post(body.toString().toRequestBody(JsonMedia))
            .build()
        return execute(request) { text ->
            if (text.isBlank()) JsonNull else CommunityJson.decodeFromString<JsonElement>(text)
        }
    }

    private fun <T> execute(request: Request, parse: (String) -> T): T {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching {
                    CommunityJson.decodeFromString<CommunityErrorEnvelope>(text).error
                }.getOrNull()
                val code = error?.code?.takeIf { it.isNotBlank() }
                    ?: "HTTP_${response.code}"
                val message = error?.message?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.code}"
                logger.warn(
                    "CommunityApi",
                    "${request.url.encodedPath} failed: $code (${response.code})",
                    null,
                )
                throw CommunityException(code, message, response.code)
            }
            return parse(text)
        }
    }
}

/** Real gateway for Android and desktop. */
class JvmCommunityGateway(
    preferences: LocalPreferences,
    logger: AppLogger,
) : CommunityGateway {
    override val api: CommunityApi = OkHttpCommunityApi(logger)
    override val sessions: CommunitySessionStore = PreferencesCommunitySessionStore(preferences)
    override val isEnabled: Boolean = true
}