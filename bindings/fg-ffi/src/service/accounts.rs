use super::helpers::*;
use super::params::*;
use super::*;

impl Service {
    pub(super) fn account_rows(&self) -> (Vec<AccountRow>, String) {
        let store = self.accounts.lock().expect("accounts poisoned");
        let selected = store.selected_id().unwrap_or_default().to_string();
        let rows = store
            .accounts()
            .iter()
            .map(|account| AccountRow::from_account(account, &selected))
            .collect();
        (rows, selected)
    }

    pub(super) fn accounts_list(&self) -> Result<Value> {
        let (accounts, selected_id) = self.account_rows();
        Ok(json!({ "accounts": accounts, "selectedId": selected_id }))
    }

    pub(super) fn accounts_add_offline(&self, params: &Value) -> Result<Value> {
        let request: AccountNameRequest = from_value(params)?;
        let name = request.name.trim();
        let mut store = self.accounts.lock().expect("accounts poisoned");
        let account = store.add_offline(name)?.clone();
        store.save()?;
        let selected = store.selected_id().unwrap_or_default().to_string();
        Ok(json!({
            "account": AccountRow::from_account(&account, &selected),
            "selectedId": selected,
        }))
    }

    pub(super) fn accounts_select(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        let mut store = self.accounts.lock().expect("accounts poisoned");
        store.select(&request.id)?;
        store.save()?;
        Ok(json!({ "selectedId": request.id }))
    }

    pub(super) async fn accounts_remove(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        let account = {
            let store = self.accounts.lock().expect("accounts poisoned");
            store.account(&request.id).cloned()
        };
        if let Some(account) = account.as_ref()
            && account.is_yggdrasil()
        {
            self.invalidate_best_effort(account).await;
        }

        let mut store = self.accounts.lock().expect("accounts poisoned");
        store.remove(&request.id);
        if store.accounts().is_empty() {
            let fallback = self.config()?.game.username;
            let name = if validate_name(&fallback).is_ok() {
                fallback
            } else {
                "Steve".to_string()
            };
            store.add_offline(&name)?;
        }
        store.save()?;
        let selected = store.selected_id().unwrap_or_default().to_string();
        Ok(json!({ "ok": true, "selectedId": selected }))
    }

    pub(super) async fn accounts_refresh(&self, params: &Value) -> Result<Value> {
        let request: OptionalIdRequest = from_value(params)?;
        let account = {
            let store = self.accounts.lock().expect("accounts poisoned");
            match request.id.as_deref() {
                Some(id) => store.account(id).cloned(),
                None => store.selected().cloned(),
            }
        }
        .ok_or_else(|| {
            Error::new(
                ErrorKind::NotFound,
                if request.id.is_some() {
                    "error.auth.account_not_found"
                } else {
                    "error.auth.no_account"
                },
            )
        })?;

        let refreshed = account.ensure_valid(&self.yggdrasil_client()?).await?;
        let mut store = self.accounts.lock().expect("accounts poisoned");
        store.update_account(refreshed.clone())?;
        store.save()?;
        let selected = store.selected_id().unwrap_or_default().to_string();
        Ok(json!({
            "account": AccountRow::from_account(&refreshed, &selected),
            "selectedId": selected,
        }))
    }

    pub(super) async fn accounts_yggdrasil_discover(&self, params: &Value) -> Result<Value> {
        let request: ServerRequest = from_value(params)?;
        let server = self.yggdrasil_client()?.discover(&request.server).await?;
        Ok(json!({
            "server": server.api_root(),
            "serverName": server.server_name(),
            "implementationName": server.implementation_name(),
            "implementationVersion": server.implementation_version(),
        }))
    }

    pub(super) async fn accounts_yggdrasil_login(&self, params: &Value) -> Result<Value> {
        let request: YggdrasilLoginRequest = from_value(params)?;
        let client = self.yggdrasil_client()?;
        let server = client.discover(&request.server).await?;
        let client_token = random_token();
        let response = client
            .authenticate(
                &server,
                request.username.trim(),
                &request.password,
                &client_token,
            )
            .await?;

        if let Some(profile) = response.selected_profile.clone() {
            let mut store = self.accounts.lock().expect("accounts poisoned");
            let account = store
                .upsert_yggdrasil(&server, &response, &profile, Some(request.username.trim()))?
                .clone();
            store.save()?;
            let selected = store.selected_id().unwrap_or_default().to_string();
            return Ok(json!({
                "account": AccountRow::from_account(&account, &selected),
                "selectedId": selected,
            }));
        }

        if response.available_profiles.is_empty() {
            return Err(Error::new(ErrorKind::Auth, "error.auth.no_profiles"));
        }

        let pending_id = random_token();
        {
            let mut pending = self.pending_logins.lock().expect("pending logins poisoned");
            pending.retain(|_, value| value.created.elapsed() < PENDING_LOGIN_TTL);
            pending.insert(
                pending_id.clone(),
                PendingLogin {
                    server: server.clone(),
                    response: response.clone(),
                    login_name: request.username.trim().to_string(),
                    created: Instant::now(),
                },
            );
        }
        Ok(json!({
            "selectionRequired": true,
            "pendingId": pending_id,
            "server": server.api_root(),
            "serverName": server.server_name(),
            "profiles": response.available_profiles,
        }))
    }

    pub(super) async fn accounts_yggdrasil_select_profile(&self, params: &Value) -> Result<Value> {
        let request: ProfileSelectRequest = from_value(params)?;
        let pending = {
            let pending = self.pending_logins.lock().expect("pending logins poisoned");
            pending
                .get(&request.pending_id)
                .filter(|value| value.created.elapsed() < PENDING_LOGIN_TTL)
                .cloned()
        }
        .ok_or_else(|| Error::new(ErrorKind::Auth, "error.auth.selection_expired"))?;

        let wanted = request.profile_id.replace('-', "");
        let profile = pending
            .response
            .available_profiles
            .iter()
            .find(|profile| profile.id == request.profile_id || profile.id_hex() == wanted)
            .cloned()
            .ok_or_else(|| Error::new(ErrorKind::Auth, "error.auth.profile_not_found"))?;

        let response = self
            .yggdrasil_client()?
            .refresh(
                &pending.server,
                &pending.response.access_token,
                &pending.response.client_token,
                Some(&profile),
            )
            .await?;

        let mut store = self.accounts.lock().expect("accounts poisoned");
        let account = store
            .upsert_yggdrasil(
                &pending.server,
                &response,
                &profile,
                Some(&pending.login_name),
            )?
            .clone();
        store.save()?;
        let selected = store.selected_id().unwrap_or_default().to_string();
        drop(store);
        self.pending_logins
            .lock()
            .expect("pending logins poisoned")
            .remove(&request.pending_id);
        Ok(json!({
            "account": AccountRow::from_account(&account, &selected),
            "selectedId": selected,
        }))
    }

    /// Best-effort revocation when the user deletes a third-party account.
    pub(super) async fn invalidate_best_effort(&self, account: &Account) {
        let (Some(server), Some(access_token), Some(client_token)) = (
            account.server.as_deref(),
            account.access_token(),
            account.client_token(),
        ) else {
            return;
        };
        let server = YggdrasilServer::new(
            server,
            account.server_name.clone().unwrap_or_default(),
            None,
            None,
        );
        match self.yggdrasil_client() {
            Ok(client) => {
                if let Err(error) = client.invalidate(&server, access_token, client_token).await {
                    tracing::warn!(
                        target: "fg_ffi",
                        server = server.api_root(),
                        detail = %error,
                        "could not invalidate the removed account token"
                    );
                }
            }
            Err(error) => {
                tracing::warn!(
                    target: "fg_ffi",
                    detail = %error,
                    "could not build a client to invalidate the removed account token"
                );
            }
        }
    }

    pub(super) fn http_client(&self) -> Result<fg_net::Client> {
        let config = self.config()?;
        fg_net::Client::builder()
            .from_config(&config.network)?
            .build()
    }

    pub(super) fn yggdrasil_client(&self) -> Result<YggdrasilClient> {
        Ok(YggdrasilClient::new(self.http_client()?))
    }

    pub(super) fn injector_source(&self) -> Result<InjectorSource> {
        let config = self.config()?;
        Ok(InjectorSource::new(
            self.http_client()?,
            self.table.clone(),
            config.download.source,
            self.region,
        ))
    }

    /// Resolves the launch account, refreshing Yggdrasil tokens and preparing
    /// authlib-injector before the command is built.
    pub(super) async fn resolve_auth_session(
        &self,
        account_id: Option<&str>,
        username: Option<&str>,
    ) -> Result<AuthSession> {
        if let Some(username) = username.map(str::trim).filter(|name| !name.is_empty()) {
            return Ok(AuthSession::offline(&OfflineAccount::new(username)?));
        }

        let account = {
            let store = self.accounts.lock().expect("accounts poisoned");
            match account_id {
                Some(id) => store.account(id).cloned(),
                None => store.selected().cloned(),
            }
        };
        let Some(account) = account else {
            return Err(Error::new(
                ErrorKind::NotFound,
                if account_id.is_some() {
                    "error.auth.account_not_found"
                } else {
                    "error.auth.no_account"
                },
            ));
        };

        match account.kind {
            AccountKind::Offline => account.session(None),
            AccountKind::Yggdrasil => {
                let refreshed = account.ensure_valid(&self.yggdrasil_client()?).await?;
                {
                    let mut store = self.accounts.lock().expect("accounts poisoned");
                    store.update_account(refreshed.clone())?;
                    store.save()?;
                }
                let server = refreshed
                    .server
                    .clone()
                    .ok_or_else(|| Error::new(ErrorKind::Auth, "error.auth.login_required"))?;
                let jar = self
                    .injector_source()?
                    .ensure(&self.root, &self.downloader)
                    .await?;
                refreshed.session(Some(AuthlibInjector::new(jar, server)))
            }
            AccountKind::Microsoft => Err(Error::new(
                ErrorKind::Unsupported,
                "error.auth.microsoft_pending",
            )),
        }
    }
}
