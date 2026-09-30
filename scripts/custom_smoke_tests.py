#!/usr/bin/env python3
"""Manual smoke test for the SMAgent API.

Usage:
    export SMAGENT_TEST_PASSWORD='your-admin-password'
    python3 scripts/custom_smoke_tests.py

Assumes the app is running on http://localhost:8080 with an owner account
seeded from SMAGENT_ADMIN_USERNAME (default admin) / SMAGENT_ADMIN_PASSWORD.
"""



checks = []


def ts2_list_posts(api: SmagentApi):
    s, d = api.login()
    check("login -> 200 owner", s == 200 and d.get("role") == "OWNER", f"got {s} {d}")

    s, d = api.list_posts()
    print("Posts:", d)

def ts3_create_sa(api: SmagentApi):
    s, d = api.login()
    check("login -> 200 owner", s == 200 and d.get("role") == "OWNER", f"got {s} {d}")

    s, fb = api.create_account("FACEBOOK", FACEBOOK_CREDS)
    print(s, fb)

    s, d = api.list_accounts()
    print(s, d)


def main():
    if not PASSWORD:
        print("Export SMAGENT_TEST_PASSWORD first (the owner password you seeded with).")
        sys.exit(1)

    # c = Client(BASE)
    api = SmagentApi(BASE)

    # ts2_list_posts(api)
    ts3_create_sa(api)

if __name__ == "__main__":
    main()
