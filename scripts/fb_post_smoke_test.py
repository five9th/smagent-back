#!/usr/bin/env python3
"""Smoke test: create a real Facebook post via the SMAgent API.

Usage:
    export SMAGENT_TEST_PASSWORD='your-admin-password'
    export SMAGENT_FB_PAGE_ID='123456789012345'
    export SMAGENT_FB_PAGE_ACCESS_TOKEN='EAAB...'
    (optional) export SMAGENT_POST_TEXT='My Post Text'
    python3 scripts/fb_post_smoke_test.py (optional) 'My Post Text'

Pauses after each step (so you can inspect the post on Facebook before
cleanup). Set SMAGENT_AUTO_MODE=1 to skip the pauses.

Posts for real to the page, verifies it landed (verify + publication log),
then cleans up the post and the social account.
"""

import os
import sys

from smagent_api import SmagentApi

AUTO_MODE = os.environ.get("SMAGENT_AUTO_MODE", "").lower() in ("1", "true", "yes")

PASSWORD = os.environ.get("SMAGENT_TEST_PASSWORD", "")
FB_PAGE_ID = os.environ.get("SMAGENT_FB_PAGE_ID", "")
FB_TOKEN = os.environ.get("SMAGENT_FB_PAGE_ACCESS_TOKEN", "")

POST_TITLE = "SMAgent FB smoke test"
DEFAULT_TEXT = "Hello from SMAgent published✅ smoke test"
POST_TEXT = (sys.argv[1] if len(sys.argv) > 1 else "") \
    or os.environ.get("SMAGENT_POST_TEXT") or DEFAULT_TEXT

checks = []


def check(name: str, ok: bool, detail=""):
    checks.append((name, ok))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  -- {detail}" if detail and not ok else ""))


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

    api = SmagentApi()

    s, d = api.login()
    check("login -> 200 owner", s == 200 and d.get("role") == "OWNER", f"got {s} {d}")

    s, fb = api.create_account("FACEBOOK", {"pageId": FB_PAGE_ID, "pageAccessToken": FB_TOKEN})
    check("create facebook account -> 201", s == 201, f"got {s} {fb}")
    fb_id = fb.get("id") if isinstance(fb, dict) else None

    s, v = api.verify_account(fb_id)
    check("verify -> ok=true, ACTIVE", s == 200 and v.get("ok") is True
          and v.get("account", {}).get("status") == "ACTIVE", f"got {s} {v}")
    pause("account verified")

    s, post = api.create_post(title=POST_TITLE, text=POST_TEXT)
    check("create post -> 201 DRAFT", s == 201 and post.get("status") == "DRAFT", f"got {s} {post}")
    post_id = post.get("id")

    s, pub = api.publish(post_id, [fb_id])
    check("publish -> 200, 1 result", s == 200 and len(pub.get("results", [])) == 1, f"got {s} {pub}")
    check("publish: post PUBLISHED", pub.get("postStatus") == "PUBLISHED", f"got {pub.get('postStatus')}")
    pause("post published — check it on Facebook")

    s, pls = api.publications(post_id)
    ok_pub = isinstance(pls, list) and pls and pls[0].get("status") == "SUCCESS" \
        and pls[0].get("externalUrl")
    check("publication SUCCESS with externalUrl", s == 200 and ok_pub, f"got {s} {pls}")
    if ok_pub:
        print(f"   post URL: {pls[0].get('externalUrl')}")
    pause("publication log checked")
    # --- cleanup ---
    pause("cleanup")
    s, _ = api.delete_post(post_id, delete_publications=False)
    check("delete post -> 204", s == 204, f"got {s}")
    s, _ = api.delete_account(fb_id)
    check("delete fb account -> 204", s == 204, f"got {s}")

    failed = [n for n, ok in checks if not ok]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} passed")
    if failed:
        print("Failed:", ", ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
