#!/usr/bin/env python3
"""Smoke test: create a real Facebook post via the SMAgent API.

Usage:
    export SMAGENT_TEST_PASSWORD='your-admin-password'
    export SMAGENT_FB_PAGE_ID='123456789012345'
    export SMAGENT_FB_PAGE_ACCESS_TOKEN='EAAB...'
    python3 scripts/fb_post_smoke_test.py

Pauses after each step (so you can inspect the post on Facebook before
cleanup). Set SMAGENT_AUTO_MODE=1 to skip the pauses.

Posts for real to the page, verifies it landed (verify + publication log),
then cleans up the post and the social account.
"""

import json
import os
import sys
import urllib.request
import urllib.error
from http.cookiejar import CookieJar

AUTO_MODE = os.environ.get("SMAGENT_AUTO_MODE", "").lower() in ("1", "true", "yes")

BASE = os.environ.get("SMAGENT_BASE_URL", "http://localhost:8080")
USERNAME = os.environ.get("SMAGENT_TEST_USERNAME", "admin")
PASSWORD = os.environ.get("SMAGENT_TEST_PASSWORD", "")
FB_PAGE_ID = os.environ.get("SMAGENT_FB_PAGE_ID", "")
FB_TOKEN = os.environ.get("SMAGENT_FB_PAGE_ACCESS_TOKEN", "")

POST_TITLE = "SMAgent FB smoke test"
POST_TEXT = "Hello from SMAgent published✅ smoke test"

checks = []


def check(name: str, ok: bool, detail=""):
    checks.append((name, ok))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  -- {detail}" if detail and not ok else ""))


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
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        try:
            resp = opener.open(req)
            status, raw = resp.status, resp.read().decode()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read().decode()
        try:
            return status, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return status, raw


def pause(step: str):
    """Stop for manual inspection unless auto_mode is on."""
    if AUTO_MODE:
        return
    input(f"\n-- {step} -- press Enter to continue (Ctrl+C to abort)...")


def main():
    missing = [v for v in (PASSWORD, FB_PAGE_ID, FB_TOKEN) if not v]
    if missing:
        print("Export SMAGENT_TEST_PASSWORD, SMAGENT_FB_PAGE_ID, "
              "SMAGENT_FB_PAGE_ACCESS_TOKEN first.")
        sys.exit(1)

    c = Client(BASE)

    s, d = c.request("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})
    check("login -> 200 owner", s == 200 and d.get("role") == "OWNER", f"got {s} {d}")

    s, fb = c.request("POST", "/api/social-accounts", {
        "platform": "FACEBOOK",
        "credentials": {"pageId": FB_PAGE_ID, "pageAccessToken": FB_TOKEN},
    })
    check("create facebook account -> 201", s == 201, f"got {s} {fb}")
    fb_id = fb.get("id") if isinstance(fb, dict) else None

    s, v = c.request("POST", f"/api/social-accounts/{fb_id}/verify")
    check("verify -> ok=true, ACTIVE", s == 200 and v.get("ok") is True
          and v.get("account", {}).get("status") == "ACTIVE", f"got {s} {v}")
    pause("account verified")

    s, post = c.request("POST", "/api/posts", {
        "title": POST_TITLE,
        "text": POST_TEXT
    })
    check("create post -> 201 DRAFT", s == 201 and post.get("status") == "DRAFT", f"got {s} {post}")
    post_id = post.get("id")

    s, pub = c.request("POST", f"/api/posts/{post_id}/publish", {"accountIds": [fb_id]})
    check("publish -> 200, 1 result", s == 200 and len(pub.get("results", [])) == 1, f"got {s} {pub}")
    check("publish: post PUBLISHED", pub.get("postStatus") == "PUBLISHED", f"got {pub.get('postStatus')}")
    pause("post published — check it on Facebook")

    s, pls = c.request("GET", f"/api/publications?postId={post_id}")
    ok_pub = isinstance(pls, list) and pls and pls[0].get("status") == "SUCCESS" \
        and pls[0].get("externalUrl")
    check("publication SUCCESS with externalUrl", s == 200 and ok_pub, f"got {s} {pls}")
    if ok_pub:
        print(f"   post URL: {pls[0].get('externalUrl')}")
    pause("publication log checked")
    # --- cleanup ---
    pause("cleanup")
    s, _ = c.request("DELETE", f"/api/posts/{post_id}")
    check("delete post -> 204", s == 204, f"got {s}")
    s, _ = c.request("DELETE", f"/api/social-accounts/{fb_id}")
    check("delete fb account -> 204", s == 204, f"got {s}")

    failed = [n for n, ok in checks if not ok]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} passed")
    if failed:
        print("Failed:", ", ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
