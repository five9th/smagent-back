#!/usr/bin/env python3
"""High-level SMAgent API wrapper for smoke tests.

`SmagentApi` wraps the raw `Client` with one method per endpoint and named
params, so a smoke test reads like the API instead of paths-and-dicts:

    api = SmagentApi()
    s, me = api.login()
    s, post = api.create_post(text="hello", title="hi")
    s, pub = api.publish(post["id"], [fb_account_id])

Every method returns the same `(status, parsed_body)` tuple as
`Client.request`, so assert-on-status style keeps working — including for
tests that *expect* 4xx responses. Override shorthand: a plain string is
accepted where the API expects content, e.g.
`overrides={"FACEBOOK": "just text"}` becomes `{"title": None, "text": ...}`.

`UNSET` marks "parameter not provided" and is distinct from `None` (JSON
null) — e.g. `update_post(post_id, title=None)` explicitly clears the title.
"""

import json
import os
import urllib.error
import urllib.request
from http.cookiejar import CookieJar

BASE = os.environ.get("SMAGENT_BASE_URL", "http://localhost:8080")
USERNAME = os.environ.get("SMAGENT_TEST_USERNAME", "admin")
PASSWORD = os.environ.get("SMAGENT_TEST_PASSWORD", "")

# marker for "not provided" (None means JSON null)
UNSET = object()


class Client:
    """Tiny JSON client with a cookie jar (session auth)."""

    def __init__(self, base: str):
        self.base = base
        self.jar = CookieJar()

    def request(self, method: str, path: str, body: dict | None = None):
        req = urllib.request.Request(
            self.base + path,
            data=json.dumps(body).encode() if body is not None else None,
            method=method,
        )
        req.add_header("Content-Type", "application/json")
        cookie_processor = urllib.request.HTTPCookieProcessor(self.jar)
        opener = urllib.request.build_opener(cookie_processor)
        try:
            resp = opener.open(req)
            status = resp.status
            raw = resp.read().decode()
        except urllib.error.HTTPError as e:
            status = e.code
            raw = e.read().decode()
        data = None
        if raw:
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                data = raw
        return status, data


class SmagentApi:
    """One method per SMAgent endpoint; all return (status, parsed_body)."""

    def __init__(self, base: str = BASE, username: str = USERNAME, password: str = PASSWORD):
        self.base = base
        self.username = username
        self.password = password
        self._client = Client(base)

    # --- escape hatch for anything without a dedicated method ---
    def request(self, method: str, path: str, body: dict | None = None):
        return self._client.request(method, path, body)

    # --- actuator (no auth) ---
    def health(self):
        return self._client.request("GET", "/actuator/health")

    def info(self):
        return self._client.request("GET", "/actuator/info")

    # --- auth ---
    def login(self, username: str | None = None, password: str | None = None):
        return self._client.request("POST", "/api/auth/login", {
            "username": username or self.username,
            "password": password or self.password,
        })

    def logout(self):
        return self._client.request("POST", "/api/auth/logout")

    def me(self):
        return self._client.request("GET", "/api/auth/me")

    # --- platforms ---
    def platforms(self):
        return self._client.request("GET", "/api/platforms")

    # --- social accounts ---
    def create_account(self, platform: str, auth_method: str, credentials: dict,
                       display_name: str | None = None):
        body: dict = {"platform": platform, "authMethod": auth_method, "credentials": credentials}
        if display_name is not None:
            body["displayName"] = display_name
        return self._client.request("POST", "/api/social-accounts", body)

    def list_accounts(self, show_deleted: bool = False):
        path = "/api/social-accounts" + ("?showDeleted=true" if show_deleted else "")
        return self._client.request("GET", path)

    def get_account(self, account_id):
        return self._client.request("GET", f"/api/social-accounts/{account_id}")

    def update_account(self, account_id, display_name=UNSET, auth_method=UNSET, credentials=UNSET):
        body: dict = {}
        if display_name is not UNSET:
            body["displayName"] = display_name
        if auth_method is not UNSET:
            body["authMethod"] = auth_method
        if credentials is not UNSET:
            body["credentials"] = credentials
        return self._client.request("PUT", f"/api/social-accounts/{account_id}", body)

    def delete_account(self, account_id):
        return self._client.request("DELETE", f"/api/social-accounts/{account_id}")

    def verify_account(self, account_id):
        return self._client.request("POST", f"/api/social-accounts/{account_id}/verify")

    # --- posts ---
    def create_post(self, text=UNSET, title=UNSET, overrides: dict | None = None):
        body: dict = {"content": self._content_dict(title, text)}
        if overrides is not None:
            body["overrides"] = self._overrides_dict(overrides)
        return self._client.request("POST", "/api/posts", body)

    def list_posts(self, status: str | None = None, show_deleted: bool = False):
        params = [p for p in (f"status={status}" if status else None,
                              "showDeleted=true" if show_deleted else None) if p]
        path = "/api/posts" + (("?" + "&".join(params)) if params else "")
        return self._client.request("GET", path)

    def get_post(self, post_id):
        return self._client.request("GET", f"/api/posts/{post_id}")

    def update_post(self, post_id, title=UNSET, text=UNSET, overrides: dict | None = None):
        body: dict = {}
        content = self._content_dict(title, text)
        if content:
            body["content"] = content  # note: title=None here means JSON null -> clears the title
        if overrides is not None:
            body["overrides"] = self._overrides_dict(overrides)
        return self._client.request("PUT", f"/api/posts/{post_id}", body)

    def delete_post(self, post_id, delete_publications=UNSET):
        # PUBLISHED posts require an explicit choice; true -> 501 until implemented
        path = f"/api/posts/{post_id}"
        if delete_publications is not UNSET:
            path += f"?deletePublications={'true' if delete_publications else 'false'}"
        return self._client.request("DELETE", path)

    # --- publishing ---
    def publish(self, post_id, account_ids: list):
        return self._client.request("POST", f"/api/posts/{post_id}/publish",
                                    {"accountIds": account_ids})

    def publications(self, post_id):
        return self._client.request("GET", f"/api/publications?postId={post_id}")

    # --- helpers ---
    @staticmethod
    def _content_dict(title, text) -> dict:
        content: dict = {}
        if title is not UNSET:
            content["title"] = title
        if text is not UNSET:
            content["text"] = text
        return content

    @staticmethod
    def _overrides_dict(overrides: dict) -> dict:
        # str shorthand -> {"title": None, "text": <str>}
        return {platform: ({"title": None, "text": v} if isinstance(v, str) else v)
                for platform, v in overrides.items()}
