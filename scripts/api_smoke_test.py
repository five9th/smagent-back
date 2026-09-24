#!/usr/bin/env python3
"""Manual smoke test for the SMAgent API.

Usage:
    python3 scripts/api_smoke_test.py

Assumes the app is running on http://localhost:8080 with an owner account
seeded from SMAGENT_ADMIN_USERNAME (default admin) / SMAGENT_ADMIN_PASSWORD.

Set credentials via env vars:
    export SMAGENT_TEST_PASSWORD='your-admin-password'
    python3 scripts/api_smoke_test.py

NOTE: publish/verify hit the real Facebook/Reddit APIs. With fake credentials
they exercise the failure path (FAILED publication rows, INVALID account) —
with real credentials they post for real.
"""

import json
import os
import sys
import urllib.request
import urllib.error
from http.cookiejar import CookieJar

BASE = os.environ.get("SMAGENT_BASE_URL", "http://localhost:8080")
USERNAME = os.environ.get("SMAGENT_TEST_USERNAME", "admin")
PASSWORD = os.environ.get("SMAGENT_TEST_PASSWORD", "")

# Fake-but-well-formed credentials; swap for real ones to test the happy path.
FACEBOOK_CREDS = {
    "pageId": "123456789012345",
    "pageAccessToken": "EAABfakeTokenForManualTesting1234",
}
REDDIT_CREDS = {
    "clientId": "fake-client-id",
    "clientSecret": "fake-client-secret",
    "username": "fake-user",
    "password": "fake-password",
    "subreddit": "test",
}


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
        # load cookies for this request, store any new ones
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


checks = []


def check(name: str, ok: bool, detail=""):
    checks.append((name, ok))
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {name}" + (f"  -- {detail}" if detail and not ok else ""))

def test_suit_1(c: Client):
    print("===== test_suit_1 =====\n")

    # --- auth ---
    s, d = c.request("POST", "/api/auth/login", {"username": USERNAME, "password": "wrong"})
    check("login with wrong password -> 401", s == 401, f"got {s}")

    s, d = c.request("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})
    check("login -> 200 owner", s == 200 and d.get("role") == "OWNER", f"got {s} {d}")

    s, d = c.request("GET", "/api/auth/me")
    check("me -> same user", s == 200 and d.get("username") == USERNAME, f"got {s} {d}")

    # --- anonymous access blocked ---
    anon = Client(BASE)
    s, _ = anon.request("GET", "/api/posts")
    check("anonymous /api/posts -> 401", s == 401, f"got {s}")

    # --- social accounts ---
    s, fb = c.request("POST", "/api/social-accounts", {
        "platform": "FACEBOOK", "credentials": FACEBOOK_CREDS,
    })
    check("create facebook account -> 201, masked", s == 201 and "••••" in json.dumps(fb), f"got {s} {fb}")
    fb_id = fb.get("id") if isinstance(fb, dict) else None

    s, rd = c.request("POST", "/api/social-accounts", {
        "platform": "REDDIT", "credentials": REDDIT_CREDS,
    })
    check("create reddit account -> 201", s == 201, f"got {s} {rd}")
    rd_id = rd.get("id") if isinstance(rd, dict) else None

    s, bad = c.request("POST", "/api/social-accounts", {
        "platform": "FACEBOOK", "credentials": {"pageId": "123"},
    })
    check("create account missing field -> 400", s == 400, f"got {s} {bad}")

    s, v = c.request("POST", f"/api/social-accounts/{fb_id}/verify")
    # real Graph call with fake token -> ok=false, account INVALID
    check("verify (fake token) -> ok=false, INVALID", s == 200 and v.get("ok") is False
          and v.get("account", {}).get("status") == "INVALID", f"got {s} {v}")

    # --- posts ---
    s, post = c.request("POST", "/api/posts", {
        "title": "Manual test post",
        "text": "generic body",
        "overrides": {"FACEBOOK": "facebook variant"},
    })
    check("create post -> 201 DRAFT", s == 201 and post.get("status") == "DRAFT", f"got {s} {post}")
    post_id = post.get("id")

    s, _ = c.request("POST", "/api/posts", {"title": "no body"})
    check("post without text -> 400", s == 400, f"got {s}")

    s, upd = c.request("PUT", f"/api/posts/{post_id}", {"text": "edited body"})
    check("edit DRAFT -> 200", s == 200 and upd.get("text") == "edited body", f"got {s} {upd}")

    s, d = c.request("GET", f"/api/posts?status=DRAFT")
    check("list DRAFT posts contains it", s == 200 and any(p.get("id") == post_id for p in d), f"got {s}")

    # --- publish (fake credentials -> failure path) ---
    s, pub = c.request("POST", f"/api/posts/{post_id}/publish", {"accountIds": [fb_id, rd_id]})
    check("publish -> 200 with per-target results", s == 200 and len(pub.get("results", [])) == 2, f"got {s} {pub}")
    check("publish: post stays DRAFT (all failed)", pub.get("postStatus") == "DRAFT",
          f"got {pub.get('postStatus')}")

    s, pls = c.request("GET", f"/api/publications?postId={post_id}")
    check("publication log has 2 attempts", s == 200 and len(pls) == 2, f"got {s} ({len(pls) if isinstance(pls, list) else pls})")

    # --- cleanup ---
    s, _ = c.request("DELETE", f"/api/posts/{post_id}")
    check("delete post -> 204", s == 204, f"got {s}")
    s, _ = c.request("DELETE", f"/api/social-accounts/{fb_id}")
    check("delete fb account -> 204", s == 204, f"got {s}")
    if rd_id:
        c.request("DELETE", f"/api/social-accounts/{rd_id}")

    # --- logout ---
    s, _ = c.request("POST", "/api/auth/logout")
    s, d = c.request("GET", "/api/auth/me")
    check("after logout -> 401", s == 401, f"got {s}")

    failed = [n for n, ok in checks if not ok]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} passed")
    if failed:
        print("Failed:", ", ".join(failed))
        sys.exit(1)

def test_suit_2(c: Client):
    print("===== test_suit_2 =====\n")

    print('c.request("GET", "/api/auth/me")')
    s, d = c.request("GET", "/api/auth/me")
    print(s, d)

    print(
        """
        s, post = c.request("POST", "/api/posts", {
        "title": "Manual test post",
        "text": "generic body",
        "overrides": {"FACEBOOK": "facebook variant"},
    })
        """
    )
    s, post = c.request("POST", "/api/posts", {
        "title": "Manual test post",
        "text": "generic body",
        "overrides": {"FACEBOOK": "facebook variant"},
    })
    print(s, post)

    print('c.request("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})')
    s, d = c.request("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})
    print(s, d)

    print('c.request("GET", "/api/auth/me")')
    s, d = c.request("GET", "/api/auth/me")
    print(s, d)

    print("creating posts...")
    s, post = c.request("POST", "/api/posts", {
        "title": "post 1",
        "text": "generic body",
        "overrides": {"FACEBOOK": "facebook variant"},
    })
    print(s, post)

    s, post = c.request("POST", "/api/posts", {
        "title": "post 2",
        "text": "generic body",
    })
    print(s, post)

    s, post = c.request("POST", "/api/posts", {
        "titleee": "post 3",
        "text": "generic body",
    })
    print(s, post)

    s, post = c.request("POST", "/api/posts", {
        "title": "post 4",
        "text": "generic body",
        "overrides": {"FACEBOOK": "facebook variant"},
        "somefield": 123
    })
    print(s, post)

    print('s, d = c.request("GET", f"/api/posts?status=DRAFT")')
    s, d = c.request("GET", f"/api/posts?status=DRAFT")
    print(s, d)

def test_suit_3(c: Client):
    print("===== test_suit_3 =====\n")

    print('c.request("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})')
    s, d = c.request("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})
    print(s, d)

    print('c.request("GET", "/api/auth/me")')
    s, d = c.request("GET", "/api/auth/me")
    print(s, d)

    print('s, d = c.request("GET", f"/api/posts?status=DRAFT")')
    s, d = c.request("GET", f"/api/posts?status=DRAFT")
    print(s, d)

def ts4(c: Client):
    print("===== test_suit_4 =====\n")

    print('c.request("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})')
    s, d = c.request("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})
    print(s, d)

    print('"POST", "/api/social-accounts"')
    s, fb = c.request("POST", "/api/social-accounts", {
        "platform": "FACEBOOK", "credentials": FACEBOOK_CREDS,
    })
    print(s, fb)

    print('"POST", "/api/social-accounts"')
    s, rd = c.request("POST", "/api/social-accounts", {
        "platform": "REDDIT", "credentials": REDDIT_CREDS,
    })
    print(s, rd)

    print('GET, "/api/social-accounts"')
    s, d = c.request("GET", "/api/social-accounts")
    print(s,d)

def ts5_publish(c: Client):
    print("===== ts5_publish =====\n")

    print('c.request("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})')
    s, d = c.request("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})
    print(s, d, '\n')

    post_id = 6
    account_ids = [5, 6]

    print('"POST", f"/api/posts/{post_id}/publish", {"accountIds": account_ids}')
    s, pub = c.request("POST", f"/api/posts/{post_id}/publish", {"accountIds": account_ids})
    print(s, pub, '\n')

    print('s, d = c.request("GET", f"/api/posts")')
    s, d = c.request("GET", f"/api/posts")
    print(s, d, '\n')


def main():
    if not PASSWORD:
        print("Export SMAGENT_TEST_PASSWORD first (the owner password you seeded with).")
        sys.exit(1)

    c = Client(BASE)

    # test_suit_3(c)
    # ts4(c)
    ts5_publish(c)

if __name__ == "__main__":
    main()
