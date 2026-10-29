#!/usr/bin/env python3
"""Manual smoke test for the SMAgent API.

Usage:
    export SMAGENT_TEST_PASSWORD='your-admin-password'
    python3 scripts/custom_smoke_tests.py

Assumes the app is running on http://localhost:8080 with an owner account
seeded from SMAGENT_ADMIN_USERNAME (default admin) / SMAGENT_ADMIN_PASSWORD.
"""



from smagent_api import BASE, PASSWORD, SmagentApi
from api_smoke_test import FACEBOOK_CREDS


def ts2_list_posts(api: SmagentApi):
    s, d = api.login()
    check("login -> 200 owner", s == 200 and d.get("role") == "OWNER", f"got {s} {d}")

    s, d = api.list_posts()
    print("Posts:", d)

def ts_3_merge_credentials(api: SmagentApi):
    s, d = api.login()
    print(s, d)

    print("creating account...")
    s, fb = api.create_account("FACEBOOK", "PAGE_ACCESS_TOKEN", FACEBOOK_CREDS)
    print(s, fb, '\n')
    id = fb['id']

    print("account:")
    s, d = api.get_account(id)
    print(s, d, '\n')

    new_creds = {
        # "pageId": "123456789012345",
        "pageAccessToken": "111111111111111111new",
    }

    print("updating creds...")
    s, fb = api.update_account(id, True, credentials=new_creds)
    print(s, fb, '\n')

    print("acc with updated creds:")
    s, d = api.get_account(id)
    print(s, d, '\n')

    s, d = api.delete_account(id)
    print(s, d, '\n')


def main():
    if not PASSWORD:
        print("Export SMAGENT_TEST_PASSWORD first (the owner password you seeded with).")
        sys.exit(1)

    # c = Client(BASE)
    api = SmagentApi(BASE)

    ts_3_merge_credentials(api)

if __name__ == "__main__":
    main()
