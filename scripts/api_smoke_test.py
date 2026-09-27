#!/usr/bin/env python3
"""Manual smoke test for the SMAgent API.

Usage:
    export SMAGENT_TEST_PASSWORD='your-admin-password'
    python3 scripts/api_smoke_test.py

Assumes the app is running on http://localhost:8080 with an owner account
seeded from SMAGENT_ADMIN_USERNAME (default admin) / SMAGENT_ADMIN_PASSWORD.

High-level API methods (one per endpoint) live in smagent_api.SmagentApi;
`Client` is the raw escape hatch.

NOTE: publish/verify hit the real Facebook/Reddit APIs. With fake credentials
they exercise the failure path (FAILED publication rows, INVALID account) —
with real credentials they post for real.
"""

import json
import os
import sys

from smagent_api import BASE, USERNAME, PASSWORD, Client, SmagentApi

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


checks = []


def check(name: str, ok: bool, detail=""):
    checks.append((name, ok))
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {name}" + (f"  -- {detail}" if detail and not ok else ""))

def test_suit_1(api: SmagentApi):
    print("===== test_suit_1 =====\n")

    # --- auth ---
    s, d = api.login(password="wrong")
    check("login with wrong password -> 401", s == 401, f"got {s}")

    s, d = api.login()
    check("login -> 200 owner", s == 200 and d.get("role") == "OWNER", f"got {s} {d}")

    s, d = api.me()
    check("me -> same user", s == 200 and d.get("username") == api.username, f"got {s} {d}")

    # --- anonymous access blocked ---
    anon = SmagentApi(BASE)
    s, _ = anon.list_posts()
    check("anonymous /api/posts -> 401", s == 401, f"got {s}")

    # --- social accounts ---
    s, fb = api.create_account("FACEBOOK", FACEBOOK_CREDS)
    check("create facebook account -> 201, masked", s == 201 and "••••" in json.dumps(fb), f"got {s} {fb}")
    fb_id = fb.get("id") if isinstance(fb, dict) else None

    s, rd = api.create_account("REDDIT", REDDIT_CREDS)
    check("create reddit account -> 201", s == 201, f"got {s} {rd}")
    rd_id = rd.get("id") if isinstance(rd, dict) else None

    s, bad = api.create_account("FACEBOOK", {"pageId": "123"})
    check("create account missing field -> 400", s == 400, f"got {s} {bad}")

    s, v = api.verify_account(fb_id)
    # real Graph call with fake token -> ok=false, account INVALID
    check("verify (fake token) -> ok=false, INVALID", s == 200 and v.get("ok") is False
          and v.get("account", {}).get("status") == "INVALID", f"got {s} {v}")

    # --- posts ---
    s, post = api.create_post(
        title="Manual test post",
        text="generic body",
        overrides={"FACEBOOK": {"title": None, "text": "facebook variant"}},
    )
    check("create post -> 201 DRAFT", s == 201 and post.get("status") == "DRAFT", f"got {s} {post}")
    post_id = post.get("id")

    s, _ = api.create_post(title="no body")
    check("post without text -> 400", s == 400, f"got {s}")

    s, upd = api.update_post(post_id, text="edited body")
    check("edit DRAFT -> 200", s == 200 and upd.get("content", {}).get("text") == "edited body",
          f"got {s} {upd}")

    s, d = api.list_posts(status="DRAFT")
    check("list DRAFT posts contains it", s == 200 and any(p.get("id") == post_id for p in d), f"got {s}")

    # --- publish (fake credentials -> failure path) ---
    s, pub = api.publish(post_id, [fb_id, rd_id])
    check("publish -> 200 with per-target results", s == 200 and len(pub.get("results", [])) == 2, f"got {s} {pub}")
    check("publish: post stays DRAFT (all failed)", pub.get("postStatus") == "DRAFT",
          f"got {pub.get('postStatus')}")

    s, pls = api.publications(post_id)
    check("publication log has 2 attempts", s == 200 and len(pls) == 2, f"got {s} ({len(pls) if isinstance(pls, list) else pls})")

    # --- cleanup ---
    s, _ = api.delete_post(post_id)
    check("delete post -> 204", s == 204, f"got {s}")
    s, _ = api.delete_account(fb_id)
    check("delete fb account -> 204", s == 204, f"got {s}")
    if rd_id:
        api.delete_account(rd_id)

    # --- logout ---
    api.logout()
    s, d = api.me()
    check("after logout -> 401", s == 401, f"got {s}")

    failed = [n for n, ok in checks if not ok]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} passed")
    if failed:
        print("Failed:", ", ".join(failed))
        sys.exit(1)


def main():
    if not PASSWORD:
        print("Export SMAGENT_TEST_PASSWORD first (the owner password you seeded with).")
        sys.exit(1)

    # c = Client(BASE)
    api = SmagentApi(BASE)

    test_suit_1(api)

if __name__ == "__main__":
    main()
