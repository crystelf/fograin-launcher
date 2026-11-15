package com.fograin.launcher.app

import com.fograin.launcher.platform.CoreAccountList
import com.fograin.launcher.platform.CoreYggdrasilLoginResult
import com.fograin.launcher.platform.decode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Account panel (M7): offline accounts and third-party Yggdrasil /
 * authlib-injector sign-in.
 *
 * The panel is a sheet opened from the home avatar rather than a fifth
 * destination, so the four-tab shell stays intact. Passwords only travel in the
 * login RPC and are never stored in [AccountUiState].
 */
internal class AccountActions(private val model: AppModel) {

    private var loading = false

    fun open() {
        model.setState {
            it.copy(
                account = it.account.copy(
                    open = true,
                    stage = AccountStage.LIST,
                    error = null,
                    pendingId = null,
                    pendingServerName = "",
                    pendingProfiles = emptyList(),
                ),
            )
        }
        refresh()
    }

    fun close() {
        model.setState {
            it.copy(
                account = it.account.copy(
                    open = false,
                    stage = AccountStage.LIST,
                    error = null,
                    pendingId = null,
                    pendingProfiles = emptyList(),
                ),
            )
        }
    }

    /** Handles Esc/back inside the panel; returns false when it is closed. */
    fun back(): Boolean {
        val current = model.state.value.account
        if (!current.open) return false
        if (current.stage == AccountStage.LIST) {
            close()
            return true
        }
        model.setState {
            it.copy(
                account = it.account.copy(
                    stage = AccountStage.LIST,
                    error = null,
                    pendingId = null,
                    pendingServerName = "",
                    pendingProfiles = emptyList(),
                ),
            )
        }
        return true
    }

    fun showOfflineForm() {
        model.setState { it.copy(account = it.account.copy(stage = AccountStage.OFFLINE, error = null)) }
    }

    fun showYggdrasilForm() {
        model.setState {
            it.copy(account = it.account.copy(stage = AccountStage.YGGDRASIL, error = null))
        }
    }

    fun refresh() {
        model.scope.launch { load(showLoading = true) }
    }

    /** Clears the inline error as soon as the user edits a field. */
    fun clearError() {
        if (model.state.value.account.error == null) return
        model.setState { it.copy(account = it.account.copy(error = null)) }
    }

    /** Loads the account list; safe to call from the core refresh loop. */
    suspend fun load(showLoading: Boolean = false) {
        if (loading) return
        loading = true
        if (showLoading) {
            model.setState { it.copy(account = it.account.copy(isLoading = true, error = null)) }
        }
        try {
            val listed = model.core.rpc("accounts.list").decode<CoreAccountList>()
            model.setState {
                it.copy(
                    account = it.account.copy(
                        accounts = listed.accounts,
                        selectedId = listed.selectedId ?: listed.accounts.firstOrNull()?.id,
                        isLoading = false,
                        error = null,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            model.setState {
                it.copy(
                    account = it.account.copy(
                        isLoading = false,
                        error = model.errorMessage(error),
                    ),
                )
            }
        } finally {
            loading = false
        }
    }

    fun addOffline(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            setError("请输入玩家名称。")
            return
        }
        submit("无法创建离线账户") {
            model.core.rpc(
                "accounts.addOffline",
                buildJsonObject { put("name", trimmed) },
            )
            load()
            model.setState { it.copy(account = it.account.copy(stage = AccountStage.LIST, error = null)) }
            model.showNotice("已添加离线账户 $trimmed", Notice.Tone.SUCCESS)
        }
    }

    /** Signs in to a third-party server; a multi-profile account moves to the picker. */
    fun loginYggdrasil(server: String, username: String, password: String) {
        if (server.isBlank() || username.isBlank() || password.isEmpty()) {
            setError("请填写服务器地址、账户和密码。")
            return
        }
        submit("第三方登录失败") {
            val result = model.core.rpc(
                "accounts.yggdrasil.login",
                buildJsonObject {
                    put("server", server.trim())
                    put("username", username.trim())
                    put("password", password)
                },
            ).decode<CoreYggdrasilLoginResult>()

            if (result.selectionRequired) {
                val pendingId = result.pendingId
                if (pendingId.isNullOrBlank() || result.profiles.isEmpty()) {
                    setError("服务器没有返回可选择的角色。")
                    return@submit
                }
                model.setState {
                    it.copy(
                        account = it.account.copy(
                            stage = AccountStage.PROFILES,
                            pendingId = pendingId,
                            pendingServerName = result.serverName,
                            pendingProfiles = result.profiles,
                            error = null,
                            isSubmitting = false,
                        ),
                    )
                }
            } else {
                load()
                model.setState {
                    it.copy(account = it.account.copy(stage = AccountStage.LIST, error = null))
                }
                model.showNotice(
                    "已登录 ${result.account?.serverName ?: result.serverName.ifBlank { "第三方服务器" }}",
                    Notice.Tone.SUCCESS,
                )
            }
        }
    }

    fun selectYggdrasilProfile(profileId: String) {
        val pendingId = model.state.value.account.pendingId
        if (pendingId.isNullOrBlank()) {
            setError("角色选择已过期，请重新登录。")
            return
        }
        submit("角色选择失败") {
            model.core.rpc(
                "accounts.yggdrasil.selectProfile",
                buildJsonObject {
                    put("pendingId", pendingId)
                    put("profileId", profileId)
                },
            )
            load()
            model.setState {
                it.copy(
                    account = it.account.copy(
                        stage = AccountStage.LIST,
                        pendingId = null,
                        pendingServerName = "",
                        pendingProfiles = emptyList(),
                        error = null,
                    ),
                )
            }
            model.showNotice("已登录第三方账户", Notice.Tone.SUCCESS)
        }
    }

    fun select(id: String) {
        submit("无法切换账户") {
            model.core.rpc(
                "accounts.select",
                buildJsonObject { put("id", id) },
            )
            load()
            model.showNotice("已切换账户", Notice.Tone.SUCCESS)
        }
    }

    fun remove(id: String) {
        submit("无法删除账户") {
            model.core.rpc(
                "accounts.remove",
                buildJsonObject { put("id", id) },
            )
            load()
            model.showNotice("已删除账户")
        }
    }

    fun refreshAccount(id: String? = null) {
        submit("无法刷新登录") {
            val params = buildJsonObject {
                if (id != null) put("id", id)
            }
            model.core.rpc("accounts.refresh", params)
            load()
            model.showNotice("登录状态已刷新", Notice.Tone.SUCCESS)
        }
    }

    private fun setError(message: String) {
        model.setState {
            it.copy(account = it.account.copy(error = message, isSubmitting = false))
        }
    }

    private fun submit(title: String, block: suspend () -> Unit) {
        if (model.state.value.account.isSubmitting) return
        model.setState { it.copy(account = it.account.copy(isSubmitting = true, error = null)) }
        model.scope.launch {
            try {
                block()
                model.setState { it.copy(account = it.account.copy(isSubmitting = false)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                model.platform.logger.warn("AccountActions", title, error)
                model.setState {
                    it.copy(
                        account = it.account.copy(
                            isSubmitting = false,
                            error = model.errorMessage(error),
                        ),
                    )
                }
            }
        }
    }
}
