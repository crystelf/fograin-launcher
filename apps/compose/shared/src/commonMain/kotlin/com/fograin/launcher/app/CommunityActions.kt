package com.fograin.launcher.app

import com.fograin.launcher.community.CommunityAuth
import com.fograin.launcher.community.CommunityConfig
import com.fograin.launcher.community.CommunityException
import com.fograin.launcher.community.CommunityFailure
import com.fograin.launcher.community.CommunityFailureKind
import com.fograin.launcher.community.CommunityFile
import com.fograin.launcher.community.CommunityNote
import com.fograin.launcher.community.CommunityNoteDraft
import com.fograin.launcher.community.CommunityPollDraft
import com.fograin.launcher.community.CommunityReactionAcceptance
import com.fograin.launcher.community.CommunitySession
import com.fograin.launcher.community.CommunityTab
import com.fograin.launcher.community.CommunityUser
import com.fograin.launcher.community.CommunityVisibility
import com.fograin.launcher.community.LocalAttachment
import com.fograin.launcher.community.PendingCommunityLogin
import com.fograin.launcher.community.absoluteUrl
import com.fograin.launcher.community.formatCommunityTimestamp
import com.fograin.launcher.community.toAccount
import com.fograin.launcher.platform.FilePick
import com.fograin.launcher.platform.FileSavePick
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalTime::class)
class CommunityActions(private val model: AppModel) {

    private val gateway get() = model.platform.community
    private val api get() = gateway.api

    private var token: String? = null
    private var refreshJob: Job? = null
    private var loadMoreJob: Job? = null
    private var detailJob: Job? = null
    private var loginJob: Job? = null
    private var mentionJob: Job? = null
    private var mentionPickerJob: Job? = null
    private var profileJob: Job? = null
    private var pendingTabAfterLogin: CommunityTab? = null

    val isEnabled: Boolean get() = gateway.isEnabled

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------

    fun restore() {
        if (!gateway.isEnabled) return
        val session = gateway.sessions.load()
        if (session != null) {
            if (session.version < CommunitySession.CURRENT_VERSION) {
                // New features need extra MiAuth scopes. A token created by an
                // older build cannot obtain them retroactively, so ask once for
                // a fresh login instead of letting every write fail with 403.
                val staleToken = session.token
                gateway.sessions.clear()
                model.scope.launch {
                    runCatching { api.revokeToken(staleToken) }
                }
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            account = null,
                            sessionState = CommunitySessionState.LOGGED_OUT,
                        ),
                    )
                }
                model.showNotice("社区权限已更新，请重新登录", Notice.Tone.INFO)
            } else {
                token = session.token
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            account = session.toAccount(),
                            sessionState = CommunitySessionState.VALIDATING,
                        ),
                    )
                }
                model.scope.launch { validateSession(session) }
            }
        }
        resumePendingLogin()
    }

    private suspend fun validateSession(session: CommunitySession) {
        try {
            val user = api.me(session.token)
            val refreshed = session.copy(user = user, savedAtEpochMillis = nowMillis())
            gateway.sessions.save(refreshed)
            model.setState {
                it.copy(
                    community = it.community.copy(
                        account = refreshed.toAccount(),
                        sessionState = CommunitySessionState.LOGGED_IN,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val failure = error.toCommunityFailure()
            if (failure.kind == CommunityFailureKind.AUTH) {
                expireSession(showNotice = true)
            } else {
                // Keep the cached identity while offline; the next refresh or
                // write will retry with the same token.
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            sessionState = CommunitySessionState.OFFLINE,
                        ),
                    )
                }
            }
        }
    }

    private fun resumePendingLogin() {
        val pending = gateway.sessions.loadPending() ?: return
        val age = nowMillis() - pending.startedAtEpochMillis
        if (age !in 0..PENDING_LOGIN_TTL_MS) {
            gateway.sessions.clearPending()
            return
        }
        startLoginPolling(pending.session, showDialog = false)
    }

    fun beginLogin() {
        if (!gateway.isEnabled) return
        loginJob?.cancel()
        val session = CommunityAuth.newSession()
        val url = CommunityAuth.url(session)
        gateway.sessions.savePending(
            PendingCommunityLogin(session, nowMillis()),
        )
        model.setState {
            it.copy(
                community = it.community.copy(
                    login = CommunityLoginUiState(
                        open = true,
                        waiting = true,
                        expiresAtEpochMillis = nowMillis() + LOGIN_TIMEOUT_MS,
                    ),
                ),
            )
        }
        if (!model.platform.links.openUrl(url)) {
            model.platform.clipboard.copy(url)
            model.setState {
                it.copy(
                    community = it.community.copy(
                        login = it.community.login.copy(copied = true),
                    ),
                )
            }
        }
        startLoginPolling(session, showDialog = true)
    }

    fun copyLoginLink() {
        val pending = gateway.sessions.loadPending() ?: return
        model.platform.clipboard.copy(CommunityAuth.url(pending.session))
        model.setState {
            it.copy(
                community = it.community.copy(
                    login = it.community.login.copy(copied = true),
                ),
            )
        }
        retryLoginPollingIfNeeded()
    }

    fun openLoginInBrowser() {
        val pending = gateway.sessions.loadPending()
        val url = pending?.let { CommunityAuth.url(it.session) } ?: return
        if (!model.platform.links.openUrl(url)) {
            model.platform.clipboard.copy(url)
            model.setState {
                it.copy(
                    community = it.community.copy(
                        login = it.community.login.copy(copied = true),
                    ),
                )
            }
        }
        retryLoginPollingIfNeeded()
    }

    /** After a timeout, opening/copying the page starts a fresh poll window. */
    private fun retryLoginPollingIfNeeded() {
        val login = model.state.value.community.login
        if (!login.open || login.waiting) return
        val pending = gateway.sessions.loadPending() ?: return
        model.setState {
            it.copy(
                community = it.community.copy(
                    login = it.community.login.copy(waiting = true, error = null),
                ),
            )
        }
        startLoginPolling(pending.session, showDialog = true)
    }

    fun cancelLogin() {
        loginJob?.cancel()
        loginJob = null
        pendingTabAfterLogin = null
        gateway.sessions.clearPending()
        model.setState {
            it.copy(
                community = it.community.copy(
                    login = CommunityLoginUiState(),
                ),
            )
        }
    }

    private fun startLoginPolling(session: String, showDialog: Boolean) {
        loginJob?.cancel()
        loginJob = model.scope.launch {
            var errors = 0
            repeat(LOGIN_ATTEMPTS) {
                delay(LOGIN_POLL_MS)
                if (!isActive) return@launch
                try {
                    val result = api.checkMiAuth(session)
                    errors = 0
                    val checkedToken = result.token
                    val user = result.user
                    if (result.ok && !checkedToken.isNullOrBlank() && user != null) {
                        // The MiAuth check user may be a lite projection. Fetch
                        // the full profile once so admin/moderator flags are
                        // available to the moderation menu immediately.
                        val detailed = runCatching { api.me(checkedToken) }.getOrDefault(user)
                        completeLogin(checkedToken, detailed)
                        return@launch
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    errors += 1
                    if (errors >= 3 && showDialog) {
                        model.setState {
                            it.copy(
                                community = it.community.copy(
                                    login = it.community.login.copy(
                                        error = error.toCommunityFailure(),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            if (!isActive) return@launch
            if (showDialog) {
                // Keep the pending MiAuth session so the dialog's "Open in
                // browser" / "Copy link" buttons can retry the same approval.
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            login = it.community.login.copy(
                                waiting = false,
                                error = CommunityFailure(
                                    kind = CommunityFailureKind.NETWORK,
                                    message = "login-timeout",
                                ),
                            ),
                        ),
                    )
                }
            } else {
                gateway.sessions.clearPending()
            }
        }
    }

    private fun completeLogin(checkedToken: String, user: CommunityUser) {
        val session = CommunitySession(
            token = checkedToken,
            user = user,
            savedAtEpochMillis = nowMillis(),
        )
        token = checkedToken
        gateway.sessions.save(session)
        gateway.sessions.clearPending()
        val targetTab = pendingTabAfterLogin
        pendingTabAfterLogin = null
        model.setState {
            it.copy(
                community = it.community.copy(
                    account = session.toAccount(),
                    sessionState = CommunitySessionState.LOGGED_IN,
                    login = CommunityLoginUiState(),
                    tab = targetTab ?: it.community.tab,
                ),
            )
        }
        model.showNotice("已登录 Crystelf Lab 社区", Notice.Tone.SUCCESS)
        if (model.state.value.destination == com.fograin.launcher.ui.AppDestination.COMMUNITY) {
            refresh()
        }
    }

    fun logout() {
        val currentToken = token
        loginJob?.cancel()
        loginJob = null
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        detailJob?.cancel()
        discardAttachments(model.state.value.community.composer.attachments)
        token = null
        pendingTabAfterLogin = null
        gateway.sessions.clear()
        gateway.sessions.clearPending()
        model.setState {
            it.copy(
                community = it.community.copy(
                    account = null,
                    sessionState = CommunitySessionState.LOGGED_OUT,
                    tab = if (it.community.tab == CommunityTab.FOLLOWING) {
                        CommunityTab.LOCAL
                    } else {
                        it.community.tab
                    },
                    selectedNote = null,
                    replies = emptyList(),
                    composer = CommunityComposerUiState(),
                    profile = CommunityProfileUiState(),
                    login = CommunityLoginUiState(),
                    feeds = it.community.feeds - CommunityTab.FOLLOWING,
                ),
            )
        }
        if (currentToken != null) {
            model.scope.launch {
                runCatching { api.revokeToken(currentToken) }
            }
        }
        model.showNotice("已退出社区账号", Notice.Tone.INFO)
    }

    private fun expireSession(showNotice: Boolean) {
        token = null
        pendingTabAfterLogin = null
        gateway.sessions.clear()
        model.setState {
            it.copy(
                community = it.community.copy(
                    account = null,
                    sessionState = CommunitySessionState.EXPIRED,
                    tab = if (it.community.tab == CommunityTab.FOLLOWING) {
                        CommunityTab.LOCAL
                    } else {
                        it.community.tab
                    },
                    feeds = it.community.feeds - CommunityTab.FOLLOWING,
                ),
            )
        }
        if (showNotice) {
            model.showNotice("社区登录已过期，请重新登录", Notice.Tone.WARNING)
        }
    }

    // ------------------------------------------------------------------
    // Feed
    // ------------------------------------------------------------------

    fun open() {
        if (!gateway.isEnabled) return
        if (model.state.value.community.meta == null) loadMeta()
        val feed = model.state.value.community.feed
        val stale = nowMillis() - feed.lastLoadedAt > FEED_STALE_MS
        if ((feed.notes.isEmpty() && !feed.isLoading) || (stale && !feed.isLoading && feed.notes.isNotEmpty())) {
            refresh()
        }
    }

    fun selectTab(tab: CommunityTab) {
        val current = model.state.value.community
        if (tab == CommunityTab.FOLLOWING && token == null) {
            pendingTabAfterLogin = tab
            beginLogin()
            return
        }
        val available = when (tab) {
            CommunityTab.LOCAL -> current.meta?.policies?.ltlAvailable != false
            CommunityTab.GLOBAL -> current.meta?.policies?.gtlAvailable != false
            CommunityTab.FOLLOWING -> true
        }
        if (!available) return
        if (current.tab == tab) return
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        model.setState {
            it.copy(community = it.community.copy(tab = tab))
        }
        // Always revalidate the selected timeline. Local, global and following
        // are three different Misskey endpoints; showing a stale cache made
        // them look identical after the first tab had loaded.
        refresh()
    }

    fun refresh() {
        if (!gateway.isEnabled) return
        val current = model.state.value.community
        if (token == null && current.meta?.clientOptions?.showTimelineForVisitor == false) {
            return
        }
        val tab = current.tab
        refreshJob?.cancel()
        loadMoreJob?.cancel()
        updateFeed(tab) {
            it.copy(isLoading = true, error = null)
        }
        refreshJob = model.scope.launch {
            try {
                val notes = api.timeline(
                    tab = tab,
                    token = token,
                    untilId = null,
                    limit = CommunityConfig.PAGE_SIZE,
                )
                updateFeed(tab) {
                    it.copy(
                        notes = notes,
                        isLoading = false,
                        error = null,
                        endReached = notes.size < CommunityConfig.PAGE_SIZE,
                        lastLoadedAt = nowMillis(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failure = error.toCommunityFailure()
                if (failure.kind == CommunityFailureKind.AUTH) {
                    expireSession(showNotice = true)
                }
                updateFeed(tab) {
                    it.copy(isLoading = false, error = failure)
                }
            }
        }
    }

    fun loadMore() {
        if (!gateway.isEnabled) return
        val state = model.state.value.community
        val feed = state.feed
        if (feed.isLoading || feed.isLoadingMore || feed.endReached || feed.notes.isEmpty()) return
        if (state.tab == CommunityTab.FOLLOWING && token == null) return
        val tab = state.tab
        val untilId = feed.notes.last().id
        updateFeed(tab) { it.copy(isLoadingMore = true, error = null) }
        loadMoreJob = model.scope.launch {
            try {
                val notes = api.timeline(
                    tab = tab,
                    token = token,
                    untilId = untilId,
                    limit = CommunityConfig.PAGE_SIZE,
                )
                updateFeed(tab) { current ->
                    val merged = (current.notes + notes).distinctBy { it.id }
                    current.copy(
                        notes = merged,
                        isLoadingMore = false,
                        error = null,
                        endReached = notes.size < CommunityConfig.PAGE_SIZE,
                        lastLoadedAt = nowMillis(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failure = error.toCommunityFailure()
                if (failure.kind == CommunityFailureKind.AUTH) {
                    expireSession(showNotice = true)
                }
                updateFeed(tab) { it.copy(isLoadingMore = false, error = failure) }
            }
        }
    }

    private fun loadMeta() {
        if (!gateway.isEnabled) return
        model.scope.launch {
            try {
                val meta = api.meta()
                val stats = runCatching { api.stats() }.getOrNull()
                var switchedTab = false
                model.setState { state ->
                    val currentTab = state.community.tab
                    val nextTab = when {
                        currentTab == CommunityTab.LOCAL &&
                            !meta.policies.ltlAvailable &&
                            meta.policies.gtlAvailable -> CommunityTab.GLOBAL

                        currentTab == CommunityTab.GLOBAL &&
                            !meta.policies.gtlAvailable &&
                            meta.policies.ltlAvailable -> CommunityTab.LOCAL

                        else -> currentTab
                    }
                    switchedTab = nextTab != currentTab
                    state.copy(
                        community = state.community.copy(
                            meta = meta,
                            stats = stats,
                            tab = nextTab,
                        ),
                    )
                }
                if (switchedTab) refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // The feed remains usable without instance metadata.
            }
        }
    }

    // ------------------------------------------------------------------
    // Note detail
    // ------------------------------------------------------------------

    fun openNote(note: CommunityNote) {
        detailJob?.cancel()
        model.setState {
            it.copy(
                community = it.community.copy(
                    selectedNote = note,
                    replies = emptyList(),
                    detailLoading = true,
                    detailError = null,
                ),
            )
        }
        detailJob = model.scope.launch {
            try {
                val fresh = api.showNote(note.id, token)
                val replies = runCatching {
                    api.children(note.id, token, limit = CommunityConfig.PAGE_SIZE)
                }.getOrDefault(emptyList())
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            selectedNote = fresh,
                            replies = replies,
                            detailLoading = false,
                            detailError = null,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            detailLoading = false,
                            detailError = error.toCommunityFailure(),
                        ),
                    )
                }
            }
        }
    }

    fun closeNote() {
        detailJob?.cancel()
        model.setState {
            it.copy(
                community = it.community.copy(
                    selectedNote = null,
                    replies = emptyList(),
                    detailLoading = false,
                    detailError = null,
                ),
            )
        }
    }

    fun openUserProfile(user: CommunityUser) {
        profileJob?.cancel()
        model.setState {
            it.copy(
                community = it.community.copy(
                    selectedNote = null,
                    replies = emptyList(),
                    detailLoading = false,
                    detailError = null,
                    profile = CommunityProfileUiState(
                        user = user,
                        isLoading = true,
                    ),
                ),
            )
        }
        profileJob = model.scope.launch {
            try {
                val detailed = api.showUser(user.id, token)
                val notes = runCatching {
                    api.userNotes(detailed.id, token, limit = CommunityConfig.PAGE_SIZE)
                }.getOrDefault(emptyList())
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            profile = it.community.profile.copy(
                                user = detailed,
                                notes = notes,
                                isLoading = false,
                                error = null,
                            ),
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            profile = it.community.profile.copy(
                                isLoading = false,
                                error = error.toCommunityFailure(),
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun closeUserProfile() {
        profileJob?.cancel()
        model.setState {
            it.copy(
                community = it.community.copy(
                    profile = CommunityProfileUiState(),
                ),
            )
        }
    }

    fun retryUserProfile() {
        val user = model.state.value.community.profile.user ?: return
        openUserProfile(user)
    }

    fun toggleFollow() {
        val current = model.state.value.community
        val user = current.profile.user ?: return
        val authToken = token
        if (authToken == null) {
            beginLogin()
            return
        }
        if (current.account?.user?.id == user.id || current.profile.followBusy) return
        model.setState {
            it.copy(
                community = it.community.copy(
                    profile = it.community.profile.copy(followBusy = true),
                ),
            )
        }
        model.scope.launch {
            val wasFollowing = user.isFollowing
            try {
                if (wasFollowing) {
                    api.unfollowUser(authToken, user.id)
                } else {
                    api.followUser(authToken, user.id)
                }
                updateProfileUser {
                    it.copy(
                        isFollowing = !wasFollowing,
                        followersCount = (it.followersCount + if (wasFollowing) -1 else 1)
                            .coerceAtLeast(0),
                    )
                }
                if (model.state.value.community.tab == CommunityTab.FOLLOWING) {
                    refresh()
                }
                model.showNotice(
                    if (wasFollowing) "已取消关注" else "已关注",
                    Notice.Tone.SUCCESS,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failure = error.toCommunityFailure()
                if (failure.kind == CommunityFailureKind.AUTH) {
                    expireSession(showNotice = true)
                } else {
                    // Re-sync the button when the server says our cached follow
                    // state is stale (already following / not following).
                    runCatching { api.showUser(user.id, authToken) }
                        .getOrNull()
                        ?.let { fresh -> updateProfileUser { fresh } }
                    model.showNotice(communityFailureNotice(failure), Notice.Tone.WARNING)
                }
            } finally {
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            profile = it.community.profile.copy(followBusy = false),
                        ),
                    )
                }
            }
        }
    }

    fun openUserInBrowser(user: CommunityUser) {
        if (!model.platform.links.openUrl("${CommunityConfig.BASE_URL}/${user.handle}")) {
            model.platform.clipboard.copy("${CommunityConfig.BASE_URL}/${user.handle}")
            model.showNotice("用户主页地址已复制", Notice.Tone.INFO)
        }
    }

    fun saveMedia(file: CommunityFile) {
        if (file.url.isBlank()) return
        if (file.id in model.state.value.community.savingMediaIds) return
        model.setState {
            it.copy(
                community = it.community.copy(
                    savingMediaIds = it.community.savingMediaIds + file.id,
                ),
            )
        }
        model.scope.launch {
            var tempPath: String? = null
            try {
                val name = mediaFileName(file)
                val downloaded = api.downloadFile(file.url, name)
                tempPath = downloaded
                val extension = name.substringAfterLast('.', "")
                val target = model.platform.files.save(
                    title = if (file.isVideo) "保存视频" else "保存图片",
                    suggestedName = name,
                    extensions = listOfNotNull(extension.takeIf { it.isNotBlank() }),
                )
                when (target) {
                    is FileSavePick.Picked -> {
                        val written = model.platform.files.writePicked(target, downloaded)
                        if (written) {
                            model.showNotice("已保存到本地", Notice.Tone.SUCCESS)
                        } else {
                            model.showNotice("保存失败，请检查目标位置", Notice.Tone.WARNING)
                        }
                    }

                    FileSavePick.Cancelled -> Unit
                    is FileSavePick.Unavailable ->
                        model.showNotice("无法打开保存对话框", Notice.Tone.WARNING)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.showNotice(
                    error.message?.takeIf { it.isNotBlank() } ?: "保存失败，请稍后重试",
                    Notice.Tone.WARNING,
                )
            } finally {
                tempPath?.let { model.platform.files.discard(it) }
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            savingMediaIds = it.community.savingMediaIds - file.id,
                        ),
                    )
                }
            }
        }
    }

    fun saveRemoteImage(url: String, name: String) {
        saveMedia(
            CommunityFile(
                id = "remote-${url.hashCode()}",
                name = name,
                type = "image/jpeg",
                url = url,
            ),
        )
    }

    private fun mediaFileName(file: CommunityFile): String {
        val raw = file.name.ifBlank { file.id }
        val name = raw.replace('/', '_').replace('\\', '_')
        if (name.contains('.')) return name
        val extension = when {
            file.type == "image/jpeg" -> "jpg"
            file.type == "image/png" -> "png"
            file.type == "image/webp" -> "webp"
            file.type == "image/gif" -> "gif"
            file.type == "video/mp4" -> "mp4"
            file.type == "video/quicktime" -> "mov"
            file.type == "video/webm" -> "webm"
            else -> "bin"
        }
        return "$name.$extension"
    }

    private fun updateProfileUser(transform: (CommunityUser) -> CommunityUser) {
        model.setState { state ->
            val user = state.community.profile.user ?: return@setState state
            state.copy(
                community = state.community.copy(
                    profile = state.community.profile.copy(user = transform(user)),
                ),
            )
        }
    }

    fun openInBrowser() {
        if (!model.platform.links.openUrl(CommunityConfig.BASE_URL)) {
            model.platform.clipboard.copy(CommunityConfig.BASE_URL)
            model.showNotice("社区地址已复制", Notice.Tone.INFO)
        }
    }

    fun openNoteInBrowser(note: CommunityNote) {
        if (!model.platform.links.openUrl(note.absoluteUrl())) {
            model.platform.clipboard.copy(note.absoluteUrl())
            model.showNotice("帖子地址已复制", Notice.Tone.INFO)
        }
    }

    // ------------------------------------------------------------------
    // Composer
    // ------------------------------------------------------------------

    fun openComposer(
        replyTo: CommunityNote? = null,
        quotedNote: CommunityNote? = null,
    ) {
        if (token == null) {
            beginLogin()
            return
        }
        if (model.state.value.community.meta == null) loadMeta()
        val prefix = replyTo?.let { note -> "${note.user.handle} " }.orEmpty()
        model.setState {
            it.copy(
                community = it.community.copy(
                    composer = CommunityComposerUiState(
                        open = true,
                        text = prefix,
                        selection = prefix.length,
                        replyTo = replyTo,
                        quotedNote = quotedNote,
                        visibility = if (it.community.meta?.policies?.canPublicNote == false) {
                            CommunityVisibility.HOME
                        } else {
                            CommunityVisibility.PUBLIC
                        },
                    ),
                ),
            )
        }
    }

    fun quoteNote(note: CommunityNote) {
        if (note.visibility == "specified") {
            model.showNotice("暂不支持引用仅指定用户可见的帖子", Notice.Tone.WARNING)
            return
        }
        openComposer(quotedNote = note)
    }

    fun closeComposer() {
        val current = model.state.value.community.composer
        if (current.isPosting) return
        mentionJob?.cancel()
        mentionPickerJob?.cancel()
        discardAttachments(current.attachments)
        model.setState {
            it.copy(
                community = it.community.copy(
                    composer = CommunityComposerUiState(),
                ),
            )
        }
    }

    fun discardDraftAttachments() {
        discardAttachments(model.state.value.community.composer.attachments)
    }

    private fun discardAttachments(attachments: List<LocalAttachment>) {
        if (attachments.isEmpty()) return
        model.scope.launch {
            attachments.forEach { attachment ->
                runCatching { model.platform.files.discard(attachment.path) }
            }
        }
    }

    fun setComposerText(text: String, selection: Int) {
        val safeSelection = selection.coerceIn(0, text.length)
        updateComposer { it.copy(text = text, selection = safeSelection) }
        updateMentionQuery(text, safeSelection)
    }

    fun setComposerSelection(selection: Int) {
        val text = model.state.value.community.composer.text
        updateComposer { it.copy(selection = selection.coerceIn(0, text.length)) }
        updateMentionQuery(text, selection.coerceIn(0, text.length))
    }

    fun togglePreview() {
        updateComposer { it.copy(preview = !it.preview) }
    }

    fun setVisibility(visibility: CommunityVisibility) {
        updateComposer { it.copy(visibility = visibility) }
    }

    fun setCwEnabled(enabled: Boolean) {
        updateComposer { it.copy(cwEnabled = enabled, cw = if (enabled) it.cw else "") }
    }

    fun setCw(cw: String) {
        updateComposer { it.copy(cw = cw.take(100)) }
    }

    fun setSensitiveAttachments(enabled: Boolean) {
        updateComposer { it.copy(sensitiveAttachments = enabled) }
    }

    fun toggleHashtags() {
        updateComposer { it.copy(hashtagsEnabled = !it.hashtagsEnabled) }
    }

    fun setHashtags(value: String) {
        updateComposer { it.copy(hashtags = value.take(300)) }
    }

    fun togglePoll() {
        updateComposer { composer ->
            if (composer.pollEnabled) {
                composer.copy(
                    pollEnabled = false,
                    pollChoices = listOf("", ""),
                    pollMultiple = false,
                    pollExpiresInHours = 24,
                )
            } else {
                composer.copy(pollEnabled = true)
            }
        }
    }

    fun setPollChoice(index: Int, value: String) {
        updateComposer { composer ->
            if (index !in composer.pollChoices.indices) return@updateComposer composer
            composer.copy(
                pollChoices = composer.pollChoices.mapIndexed { i, choice ->
                    if (i == index) value.take(50) else choice
                },
            )
        }
    }

    fun addPollChoice() {
        updateComposer { composer ->
            if (composer.pollChoices.size >= MAX_POLL_CHOICES) {
                composer
            } else {
                composer.copy(pollChoices = composer.pollChoices + "")
            }
        }
    }

    fun removePollChoice(index: Int) {
        updateComposer { composer ->
            if (composer.pollChoices.size <= 2 || index !in composer.pollChoices.indices) {
                composer
            } else {
                composer.copy(
                    pollChoices = composer.pollChoices.filterIndexed { i, _ -> i != index },
                )
            }
        }
    }

    fun setPollMultiple(enabled: Boolean) {
        updateComposer { it.copy(pollMultiple = enabled) }
    }

    fun setPollExpiresInHours(hours: Int?) {
        updateComposer { it.copy(pollExpiresInHours = hours) }
    }

    fun setReactionAcceptance(value: CommunityReactionAcceptance) {
        updateComposer { it.copy(reactionAcceptance = value) }
    }

    fun setScheduledAt(epochMillis: Long?) {
        updateComposer { it.copy(scheduledAtEpochMillis = epochMillis) }
    }

    fun clearSchedule() {
        updateComposer { it.copy(scheduledAtEpochMillis = null) }
    }

    fun openMentionPicker() {
        mentionPickerJob?.cancel()
        updateComposer {
            it.copy(
                mentionPickerOpen = true,
                mentionPickerQuery = "",
                mentionPickerResults = emptyList(),
                mentionPickerLoading = false,
            )
        }
    }

    fun closeMentionPicker() {
        mentionPickerJob?.cancel()
        updateComposer {
            it.copy(
                mentionPickerOpen = false,
                mentionPickerQuery = "",
                mentionPickerResults = emptyList(),
                mentionPickerLoading = false,
            )
        }
    }

    fun setMentionPickerQuery(query: String) {
        updateComposer {
            it.copy(
                mentionPickerQuery = query.take(64),
                mentionPickerLoading = query.isNotBlank(),
            )
        }
        mentionPickerJob?.cancel()
        if (query.isBlank()) {
            updateComposer {
                it.copy(mentionPickerResults = emptyList(), mentionPickerLoading = false)
            }
            return
        }
        mentionPickerJob = model.scope.launch {
            delay(220)
            try {
                val users = api.searchUsers(query = query.trim(), token = token, limit = 10)
                updateComposer {
                    if (it.mentionPickerQuery == query.take(64)) {
                        it.copy(
                            mentionPickerResults = users,
                            mentionPickerLoading = false,
                        )
                    } else {
                        it
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                updateComposer {
                    if (it.mentionPickerQuery == query.take(64)) {
                        it.copy(mentionPickerResults = emptyList(), mentionPickerLoading = false)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun selectPickerMention(user: CommunityUser) {
        val composer = model.state.value.community.composer
        val selection = composer.selection.coerceIn(0, composer.text.length)
        val mention = if (user.host.isNullOrBlank()) {
            "@${user.username} "
        } else {
            "@${user.username}@${user.host} "
        }
        val text = composer.text.substring(0, selection) + mention +
            composer.text.substring(selection)
        mentionPickerJob?.cancel()
        updateComposer {
            it.copy(
                text = text,
                selection = selection + mention.length,
                mentionPickerOpen = false,
                mentionPickerQuery = "",
                mentionPickerResults = emptyList(),
                mentionPickerLoading = false,
                mentionQuery = null,
                mentionSuggestions = emptyList(),
            )
        }
    }

    fun pickAttachment(title: String = "选择图片或视频") {
        val current = model.state.value.community.composer
        if (current.attachments.size >= CommunityConfig.MAX_ATTACHMENTS) {
            updateComposer {
                it.copy(
                    error = CommunityFailure(
                        CommunityFailureKind.VALIDATION,
                        "attachment-limit",
                    ),
                )
            }
            return
        }
        model.scope.launch {
            val result = model.platform.files.pick(
                title = title,
                extensions = MEDIA_EXTENSIONS,
            )
            when (result) {
                is FilePick.Picked -> {
                    if (!model.state.value.community.composer.open) {
                        runCatching { model.platform.files.discard(result.path) }
                        return@launch
                    }
                    val name = result.path.substringAfterLast('/').substringAfterLast('\\')
                    val extension = name.substringAfterLast('.', "").lowercase()
                    val media = LocalAttachment(
                        path = result.path,
                        name = name.ifBlank { "attachment-${nowMillis()}" },
                        isVideo = extension in VIDEO_EXTENSIONS,
                    )
                    updateComposer { it.copy(attachments = it.attachments + media) }
                }

                is FilePick.Unavailable -> updateComposer {
                    it.copy(
                        error = CommunityFailure(
                            CommunityFailureKind.VALIDATION,
                            "file-picker-unavailable",
                        ),
                    )
                }

                FilePick.Cancelled -> Unit
            }
        }
    }

    fun removeAttachment(index: Int) {
        val removed = model.state.value.community.composer.attachments.getOrNull(index)
        if (removed == null) return
        updateComposer { composer ->
            composer.copy(attachments = composer.attachments.filterIndexed { i, _ -> i != index })
        }
        model.scope.launch {
            runCatching { model.platform.files.discard(removed.path) }
        }
    }

    private fun updateMentionQuery(text: String, selection: Int) {
        val range = mentionRangeAt(text, selection)
        if (range == null) {
            mentionJob?.cancel()
            updateComposer {
                it.copy(
                    mentionQuery = null,
                    mentionSuggestions = emptyList(),
                    mentionsLoading = false,
                )
            }
            return
        }
        val query = text.substring(range.first + 1, range.last + 1)
        if (query.isEmpty()) {
            mentionJob?.cancel()
            updateComposer {
                it.copy(
                    mentionQuery = null,
                    mentionSuggestions = emptyList(),
                    mentionsLoading = false,
                )
            }
            return
        }
        updateComposer {
            it.copy(
                mentionQuery = query,
                mentionSuggestions = it.mentionSuggestions,
                mentionsLoading = true,
            )
        }
        mentionJob?.cancel()
        mentionJob = model.scope.launch {
            delay(220)
            try {
                val users = api.searchUsers(query = query, token = token, limit = 6)
                val latest = model.state.value.community.composer
                if (latest.mentionQuery == query) {
                    updateComposer {
                        it.copy(mentionSuggestions = users, mentionsLoading = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                updateComposer {
                    if (it.mentionQuery == query) {
                        it.copy(mentionSuggestions = emptyList(), mentionsLoading = false)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun selectMention(user: CommunityUser) {
        val composer = model.state.value.community.composer
        val range = mentionRangeAt(composer.text, composer.selection) ?: return
        val mention = if (user.host.isNullOrBlank()) {
            "@${user.username}"
        } else {
            "@${user.username}@${user.host}"
        }
        val replacement = "$mention "
        val text = composer.text.replaceRange(range.first, range.last + 1, replacement)
        val selection = range.first + replacement.length
        mentionJob?.cancel()
        updateComposer {
            it.copy(
                text = text,
                selection = selection,
                mentionQuery = null,
                mentionSuggestions = emptyList(),
                mentionsLoading = false,
            )
        }
    }

    fun publish() {
        val current = model.state.value.community
        val composer = current.composer
        val authToken = token
        if (authToken == null) {
            beginLogin()
            return
        }
        if (composer.isPosting) return
        val text = composer.publishText
        if (text.isEmpty() && composer.attachments.isEmpty() && !composer.pollChoicesValid) {
            updateComposer {
                it.copy(
                    error = CommunityFailure(
                        CommunityFailureKind.VALIDATION,
                        "empty-post",
                    ),
                )
            }
            return
        }
        val maxLength = current.meta?.maxNoteTextLength
            ?: CommunityConfig.MAX_NOTE_LENGTH_FALLBACK
        if (text.length > maxLength) {
            updateComposer {
                it.copy(
                    error = CommunityFailure(
                        CommunityFailureKind.VALIDATION,
                        "too-long",
                    ),
                )
            }
            return
        }
        if (composer.pollEnabled && !composer.pollChoicesValid) {
            updateComposer {
                it.copy(
                    error = CommunityFailure(
                        CommunityFailureKind.VALIDATION,
                        "poll-invalid",
                    ),
                )
            }
            return
        }
        if (composer.pollEnabled &&
            composer.pollChoices.any { it.length > MAX_POLL_CHOICE_LENGTH }
        ) {
            updateComposer {
                it.copy(
                    error = CommunityFailure(
                        CommunityFailureKind.VALIDATION,
                        "poll-choice-too-long",
                    ),
                )
            }
            return
        }
        val scheduledAt = composer.scheduledAtEpochMillis
        if (scheduledAt != null && scheduledAt <= nowMillis()) {
            updateComposer {
                it.copy(
                    error = CommunityFailure(
                        CommunityFailureKind.VALIDATION,
                        "schedule-past",
                    ),
                )
            }
            return
        }
        val poll = if (composer.pollEnabled) {
            CommunityPollDraft(
                choices = composer.pollChoices
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                multiple = composer.pollMultiple,
                expiresAtEpochMillis = composer.pollExpiresInHours?.let { hours ->
                    nowMillis() + hours * 60L * 60L * 1_000L
                },
            )
        } else {
            null
        }
        val replyTo = composer.replyTo
        val quotedNote = composer.quotedNote
        val draft = CommunityNoteDraft(
            text = text.takeIf { it.isNotEmpty() },
            visibility = composer.visibility,
            cw = composer.cw.takeIf { composer.cwEnabled && it.isNotBlank() },
            replyId = replyTo?.id,
            renoteId = quotedNote?.id,
            poll = poll,
            reactionAcceptance = composer.reactionAcceptance,
        )
        val attachments = composer.attachments
        updateComposer { it.copy(isPosting = true, error = null, uploadProgress = null) }
        model.scope.launch {
            try {
                val fileIds = attachments.mapIndexed { index, attachment ->
                    updateComposer {
                        it.copy(uploadProgress = "${index + 1}/${attachments.size}")
                    }
                    api.uploadFile(
                        token = authToken,
                        path = attachment.path,
                        name = attachment.name,
                        isSensitive = composer.sensitiveAttachments,
                        mimeType = mimeTypeFor(attachment.name),
                    ).id
                }
                updateComposer { it.copy(uploadProgress = null) }
                val completeDraft = draft.copy(fileIds = fileIds)
                if (scheduledAt != null) {
                    api.scheduleNote(authToken, completeDraft, scheduledAt)
                    finishPosting(attachments)
                    model.showNotice(
                        "帖子已安排在 ${formatCommunityTimestamp(scheduledAt)} 发布",
                        Notice.Tone.SUCCESS,
                    )
                    return@launch
                }
                val created = api.createNote(authToken, completeDraft)
                finishPosting(attachments)
                if (replyTo != null) {
                    if (model.state.value.community.selectedNote?.id == replyTo.id) {
                        openNote(replyTo)
                    }
                } else {
                    prependCreatedNote(created)
                }
                model.showNotice("帖子已发布", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failure = error.toCommunityFailure()
                if (failure.kind == CommunityFailureKind.AUTH) {
                    expireSession(showNotice = true)
                }
                updateComposer {
                    it.copy(
                        isPosting = false,
                        uploadProgress = null,
                        error = failure,
                    )
                }
            }
        }
    }

    private fun finishPosting(attachments: List<LocalAttachment>) {
        discardAttachments(attachments)
        model.setState {
            it.copy(
                community = it.community.copy(
                    composer = CommunityComposerUiState(),
                ),
            )
        }
    }

    private fun prependCreatedNote(note: CommunityNote) {
        val tab = model.state.value.community.tab
        updateFeed(tab) { feed ->
            feed.copy(
                notes = (listOf(note) + feed.notes).distinctBy { it.id },
                lastLoadedAt = nowMillis(),
            )
        }
    }

    // ------------------------------------------------------------------
    // Reactions
    // ------------------------------------------------------------------

    fun toggleReaction(note: CommunityNote, reaction: String) {
        val authToken = token
        if (authToken == null) {
            beginLogin()
            return
        }
        val state = model.state.value.community
        if (note.id in state.reactingNoteIds) return
        model.setState {
            it.copy(
                community = it.community.copy(
                    reactingNoteIds = it.community.reactingNoteIds + note.id,
                    recentReactions = (listOf(reaction) + it.community.recentReactions)
                        .distinct()
                        .take(16),
                ),
            )
        }
        model.scope.launch {
            try {
                val previous = note.myReaction
                when {
                    previous == reaction -> {
                        api.deleteReaction(authToken, note.id)
                        updateNoteEverywhere(note.id) {
                            it.withReactionDelta(remove = previous, add = null)
                        }
                    }

                    previous != null -> {
                        api.deleteReaction(authToken, note.id)
                        api.createReaction(authToken, note.id, reaction)
                        updateNoteEverywhere(note.id) {
                            it.withReactionDelta(remove = previous, add = reaction)
                        }
                    }

                    else -> {
                        api.createReaction(authToken, note.id, reaction)
                        updateNoteEverywhere(note.id) {
                            it.withReactionDelta(remove = null, add = reaction)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failure = error.toCommunityFailure()
                if (failure.kind == CommunityFailureKind.AUTH) {
                    expireSession(showNotice = true)
                } else {
                    model.showNotice(failure.message, Notice.Tone.WARNING)
                }
            } finally {
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            reactingNoteIds = it.community.reactingNoteIds - note.id,
                        ),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Sharing, renotes, moderation and polls
    // ------------------------------------------------------------------

    fun canDelete(note: CommunityNote): Boolean {
        val user = model.state.value.community.account?.user ?: return false
        return note.user.id == user.id || user.canModerate
    }

    fun shareNote(note: CommunityNote) {
        val url = note.absoluteUrl()
        val text = "${note.user.displayName} (${note.user.handle})\n$url"
        if (!model.platform.links.share(text)) {
            model.platform.clipboard.copy(url)
            model.showNotice("帖子链接已复制", Notice.Tone.INFO)
        }
    }

    fun renote(note: CommunityNote) {
        val authToken = token
        if (authToken == null) {
            beginLogin()
            return
        }
        val target = if (
            note.renote != null &&
            note.text.isNullOrBlank() &&
            note.files.isEmpty() &&
            note.poll == null
        ) {
            note.renote
        } else {
            note
        }
        if (target.visibility == "specified") {
            model.showNotice("暂不支持转发仅指定用户可见的帖子", Notice.Tone.WARNING)
            return
        }
        model.scope.launch {
            try {
                val created = api.createNote(
                    authToken,
                    CommunityNoteDraft(
                        visibility = when (target.visibility) {
                            "followers" -> CommunityVisibility.FOLLOWERS
                            "home" -> CommunityVisibility.HOME
                            else -> CommunityVisibility.PUBLIC
                        },
                        renoteId = target.id,
                    ),
                )
                prependCreatedNote(created)
                updateNoteEverywhere(target.id) {
                    it.copy(renoteCount = it.renoteCount + 1)
                }
                model.showNotice("已转发", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failure = error.toCommunityFailure()
                if (failure.kind == CommunityFailureKind.AUTH) {
                    expireSession(showNotice = true)
                } else {
                    model.showNotice(communityFailureNotice(failure), Notice.Tone.WARNING)
                }
            }
        }
    }

    fun deleteNote(note: CommunityNote) {
        val authToken = token
        if (authToken == null) {
            beginLogin()
            return
        }
        if (!canDelete(note)) return
        val community = model.state.value.community
        if (note.id in community.deletingNoteIds) return
        model.setState {
            it.copy(
                community = it.community.copy(
                    deletingNoteIds = it.community.deletingNoteIds + note.id,
                ),
            )
        }
        model.scope.launch {
            try {
                api.deleteNote(authToken, note.id)
                removeNoteEverywhere(note)
                model.showNotice("帖子已删除", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failure = error.toCommunityFailure()
                if (failure.kind == CommunityFailureKind.AUTH) {
                    expireSession(showNotice = true)
                } else {
                    model.showNotice(communityFailureNotice(failure), Notice.Tone.WARNING)
                }
            } finally {
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            deletingNoteIds = it.community.deletingNoteIds - note.id,
                        ),
                    )
                }
            }
        }
    }

    fun reportNote(note: CommunityNote, comment: String) {
        val authToken = token
        if (authToken == null) {
            beginLogin()
            return
        }
        val me = model.state.value.community.account?.user
        if (me?.id == note.user.id) {
            model.showNotice("不能举报自己的帖子", Notice.Tone.WARNING)
            return
        }
        val reason = comment.trim()
        if (reason.isEmpty()) return
        model.scope.launch {
            try {
                // Misskey reports target the author, not a single note. Keep
                // the note permalink in the report so moderators can find it.
                api.reportAbuse(
                    authToken,
                    note.user.id,
                    (reason + "\n\n" + note.absoluteUrl()).take(2048),
                )
                model.showNotice("举报已提交", Notice.Tone.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failure = error.toCommunityFailure()
                if (failure.kind == CommunityFailureKind.AUTH) {
                    expireSession(showNotice = true)
                } else {
                    model.showNotice(communityFailureNotice(failure), Notice.Tone.WARNING)
                }
            }
        }
    }

    fun votePoll(note: CommunityNote, choice: Int) {
        val authToken = token
        if (authToken == null) {
            beginLogin()
            return
        }
        val state = model.state.value.community
        if (note.id in state.votingPollNoteIds) return
        model.setState {
            it.copy(
                community = it.community.copy(
                    votingPollNoteIds = it.community.votingPollNoteIds + note.id,
                ),
            )
        }
        model.scope.launch {
            try {
                api.votePoll(authToken, note.id, choice)
                val fresh = runCatching { api.showNote(note.id, authToken) }.getOrNull()
                if (fresh != null) {
                    updateNoteEverywhere(note.id) { fresh }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failure = error.toCommunityFailure()
                if (failure.kind == CommunityFailureKind.AUTH) {
                    expireSession(showNotice = true)
                } else {
                    model.showNotice(communityFailureNotice(failure), Notice.Tone.WARNING)
                }
            } finally {
                model.setState {
                    it.copy(
                        community = it.community.copy(
                            votingPollNoteIds = it.community.votingPollNoteIds - note.id,
                        ),
                    )
                }
            }
        }
    }

    private fun removeNoteEverywhere(note: CommunityNote) {
        model.setState { state ->
            val community = state.community
            val parentId = note.replyId
            fun adjustParent(existing: CommunityNote): CommunityNote =
                if (parentId != null && existing.id == parentId) {
                    existing.copy(repliesCount = (existing.repliesCount - 1).coerceAtLeast(0))
                } else {
                    existing
                }
            val feeds = community.feeds.mapValues { (_, feed) ->
                feed.copy(notes = feed.notes.filterNot { it.id == note.id }.map(::adjustParent))
            }
            val selected = community.selectedNote
                ?.takeUnless { it.id == note.id }
                ?.let(::adjustParent)
            state.copy(
                community = community.copy(
                    feeds = feeds,
                    selectedNote = selected,
                    replies = community.replies.filterNot { it.id == note.id }.map(::adjustParent),
                ),
            )
        }
    }

    private fun communityFailureNotice(failure: CommunityFailure): String = when (failure.code) {
        "ALREADY_VOTED" -> "你已经投过票了"
        "ALREADY_EXPIRED" -> "投票已结束"
        "ACCESS_DENIED" -> "没有权限执行此操作"
        "CANNOT_REPORT_YOURSELF" -> "不能举报自己的帖子"
        "CANNOT_REPORT_ADMIN" -> "不能举报管理员"
        "ALREADY_FOLLOWING" -> "已经关注该用户"
        "NOT_FOLLOWING" -> "尚未关注该用户"
        "FOLLOWEE_IS_YOURSELF" -> "不能关注自己"
        "BLOCKED" -> "对方已将你屏蔽，无法关注"
        "BLOCKING" -> "你已屏蔽对方，请先解除屏蔽"
        "TOO_MANY_SCHEDULED_NOTES" -> "定时帖子数量已达上限"
        "SCHEDULED_AT_MUST_BE_IN_FUTURE" -> "定时发布时间必须在未来"
        else -> failure.message
    }

    // ------------------------------------------------------------------
    // State helpers
    // ------------------------------------------------------------------

    private fun updateComposer(transform: (CommunityComposerUiState) -> CommunityComposerUiState) {
        model.setState { state ->
            state.copy(
                community = state.community.copy(
                    composer = transform(state.community.composer),
                ),
            )
        }
    }

    private fun updateFeed(
        tab: CommunityTab,
        transform: (CommunityFeed) -> CommunityFeed,
    ) {
        model.setState { state ->
            val current = state.community.feeds[tab] ?: CommunityFeed()
            state.copy(
                community = state.community.copy(
                    feeds = state.community.feeds + (tab to transform(current)),
                ),
            )
        }
    }

    private fun updateNoteEverywhere(
        noteId: String,
        transform: (CommunityNote) -> CommunityNote,
    ) {
        model.setState { state ->
            val community = state.community
            state.copy(
                community = community.copy(
                    feeds = community.feeds.mapValues { (_, feed) ->
                        feed.copy(
                            notes = feed.notes.map { note ->
                                if (note.id == noteId) transform(note) else note
                            },
                        )
                    },
                    selectedNote = community.selectedNote?.let { note ->
                        if (note.id == noteId) transform(note) else note
                    },
                    replies = community.replies.map { note ->
                        if (note.id == noteId) transform(note) else note
                    },
                ),
            )
        }
    }

    private fun CommunityNote.withReactionDelta(
        remove: String?,
        add: String?,
    ): CommunityNote {
        val counts = reactions.toMutableMap()
        var count = reactionCount
        if (!remove.isNullOrBlank()) {
            val old = counts[remove] ?: 0
            if (old <= 1) counts.remove(remove) else counts[remove] = old - 1
            count = max(0, count - 1)
        }
        if (!add.isNullOrBlank()) {
            counts[add] = (counts[add] ?: 0) + 1
            count += 1
        }
        return copy(
            reactions = counts,
            reactionCount = count,
            myReaction = add,
        )
    }

    private fun mentionRangeAt(text: String, selection: Int): IntRange? {
        if (selection <= 0 || selection > text.length) return null
        var start = selection - 1
        while (start >= 0 && !text[start].isWhitespace()) {
            if (text[start] == '@') {
                val before = text.getOrNull(start - 1)
                if (before == null || before.isWhitespace() || before in "([{") {
                    return start..(selection - 1)
                }
                return null
            }
            start -= 1
        }
        return null
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "m4v" -> "video/x-m4v"
        else -> "application/octet-stream"
    }

    private fun Throwable.toCommunityFailure(): CommunityFailure = when (this) {
        is CommunityException -> toFailure()
        else -> CommunityFailure(
            kind = CommunityFailureKind.NETWORK,
            message = message ?: "network-error",
        )
    }

    private companion object {
        const val LOGIN_POLL_MS = 2_000L
        const val LOGIN_TIMEOUT_MS = 3 * 60 * 1_000L
        const val LOGIN_ATTEMPTS = 90
        const val PENDING_LOGIN_TTL_MS = 10 * 60 * 1_000L
        const val FEED_STALE_MS = 60 * 1_000L
        const val MAX_POLL_CHOICES = 10
        const val MAX_POLL_CHOICE_LENGTH = 50
        val MEDIA_EXTENSIONS = listOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif",
            "mp4", "mov", "webm", "mkv", "m4v",
        )
        val VIDEO_EXTENSIONS = setOf("mp4", "mov", "webm", "mkv", "m4v")
    }
}