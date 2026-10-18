# SMAgent API Reference

Version: <!-- gradle:version -->0.9.0<!-- /gradle:version -->

REST API for the SMAgent backend. Base URL: `http://localhost:8080/api` (configurable
via `server.port`). All endpoints require an authenticated session except login and
the two public actuator endpoints in [Operational endpoints](#operational-endpoints).

**Status legend**

| Mark | Meaning                                                 |
|------|---------------------------------------------------------|
| ✅   | Implemented and tested                                  |
| 🚧   | Partially implemented / known limitation                |
| ❌   | Planned — not implemented (design below)                |
| ⛔   | Implemented but blocked externally (unusable right now) |

## Feature overview

| Feature                             | Status | Notes                                                                                 |
|-------------------------------------|--------|---------------------------------------------------------------------------------------|
| Session auth (login/logout/me)      | ✅     | HttpOnly `SameSite=Lax` cookie                                                        |
| Owner seeding from env              | ✅     | Fail-fast without `SMAGENT_ADMIN_PASSWORD`                                            |
| Social account CRUD + verify        | ✅     | Credentials AES-GCM encrypted; IDs shown, tokens tail-masked, passwords hidden        |
| Platform/login-option catalog       | ✅     | `GET /api/platforms` — drives the account-creation form                               |
| Facebook Page publishing            | ✅     | Graph API v26.0, live-tested                                                          |
| Reddit publishing                   | ⛔     | Adapter implemented; Reddit closed free API access — credentials unobtainable for now |
| LinkedIn publishing                 | ✅     | Member + company page via `POST /rest/posts` (text, optional link); tokens expire ~60 days |
| LinkedIn token via OAuth            | ✅     | `GET /api/oauth/linkedin/*` — member + company page (scopes picked by login option) |
| X publishing                        | ✅     | `POST /2/tweets` (text, optional link); OAuth2 PKCE flow, ~2h tokens auto-refreshed |
| X token via OAuth                   | ✅     | `GET /api/oauth/x/*` — PKCE + S256, both tokens stored, auto-rotated on refresh |
| Manual publish tracking             | ✅     | `POST /api/posts/{id}/publish-manual` — record posts made by hand                     |
| Share/composer URLs                 | ✅     | `GET /api/posts/{id}/share-url` — prefilled composer for manual accounts              |
| Publication history                 | ✅     | One row per post × account attempt                                                    |
| Post management (drafts, overrides) | ✅     |                                                                                       |
| Soft delete (accounts & posts)      | ✅     | History preserved; `showDeleted` reveals; creds dropped on account delete             |
| Demo feedback list                  | ✅     | Demo-only; standalone items, no relations, no soft delete                             |
| Health & version endpoints          | ✅     | `/actuator/health`, `/actuator/info` — no auth, only exposed actuator endpoints       |
| Delete published post on platform   | ❌     | `deletePublications=true` returns `501` until implemented                             |
| Engagement tracking (comments etc.) | ❌     | Design below                                                                          |
| AI provider management              | ✅     | `ANTHROPIC` + `OPENAI_COMPATIBLE` types; keys encrypted + masked like platform credentials            |
| AI text generation                  | ✅     | `POST /api/ai/generate` — generic + per-platform variants in one call, any provider type              |
| AI platform style rules             | ✅     | `GET/PUT /api/ai/generation-settings` — owner-editable style half of each prompt rule                 |
| AI usage tracking                   | ❌     | Design below (each response already carries token usage)                              |
| AI multi-turn chat                  | ❌     | Design below                                                                          |
| Media upload                        | ❌     | Out of current scope                                                                  |
| Scheduling                          | ❌     | Out of current scope                                                                  |

---

## Conventions

- **Content type:** `application/json` on all request bodies. GET endpoints never mutate.
- **Auth:** session cookie (`JSESSIONID`) set by login. Send `credentials: include` from the SPA (CORS allowlist via `smagent.cors.allowed-origins`).
- **Timestamps:** ISO-8601 UTC with nanoseconds, e.g. `2026-09-22T15:24:58.902685721Z`.
- **Error shape** (global handler):

```json
{ "timestamp": "2026-09-22T15:25:28.79Z", "status": 502, "message": "Facebook API error (400): Malformed access token" }
```

| HTTP | When                                                                                                                         |
|------|------------------------------------------------------------------------------------------------------------------------------|
| 400  | validation failure, malformed body, illegal state (e.g. editing a PUBLISHED post, deleting one without `deletePublications`) |
| 401  | not authenticated (or failed login); wrong-credentials from login returns `{"message": "..."}` (auth-controller-local shape) |
| 404  | unknown id / endpoint — including soft-deleted accounts and posts                                                            |
| 501  | recognized but not implemented (`deletePublications=true`)                                                                   |
| 502  | upstream platform/AI API failure (`PlatformException` / `AiProviderException`)                                               |
| 500  | unexpected server error (logged)                                                                                             |

**Query flags** use camelCase + `true`/`false` (`showDeleted=true`, `deletePublications=false`).
(Spring also accepts `yes/no`, `on/off`, `1/0` — tolerated but undocumented.)

**Enums**

```
Platform:            FACEBOOK | REDDIT | X | LINKEDIN
AuthMethod:          PAGE_ACCESS_TOKEN | PASSWORD_GRANT | ACCESS_TOKEN | ORGANIZATION_ACCESS_TOKEN | PROFILE_URL
CredentialFieldKind: ID | TOKEN | PASSWORD
AccountStatus:       UNVERIFIED | ACTIVE | INVALID
PostStatus:          DRAFT | PUBLISHED
PublicationStatus:   PENDING | SUCCESS | FAILED
AiProviderType:      ANTHROPIC | OPENAI_COMPATIBLE
GeneratePlatform:    GENERIC | FACEBOOK | REDDIT | X | LINKEDIN
GenerateEffort:      LOW | MEDIUM | HIGH | XHIGH | MAX
```

`AuthMethod` availability is per platform (see `GET /api/platforms`); only
`PROFILE_URL` exists on every platform. `CredentialFieldKind` says how a
credential field is rendered in `credentialsMasked`: `ID` in full, `TOKEN` as
`••••` + last 4 characters, `PASSWORD` fully masked.

---

## Authentication

| Method | Path               | Description                      |
|--------|--------------------|----------------------------------|
| POST   | `/api/auth/login`  | Authenticate, set session cookie |
| POST   | `/api/auth/logout` | Invalidate session               |
| GET    | `/api/auth/me`     | Current user                     |

### POST /api/auth/login

```json
{ "username": "admin", "password": "..." }
```

→ `200` (sets `JSESSIONID`; rotates session id on login)

```json
{ "id": 1, "username": "admin", "role": "OWNER" }
```

Wrong credentials → `401 {"message": "Bad credentials"}`. Owner is seeded at startup
from `SMAGENT_ADMIN_USERNAME` / `SMAGENT_ADMIN_PASSWORD`; only one user exists.

### GET /api/auth/me → `200 { "id", "username", "role" }` · anonymous → `401`

### POST /api/auth/logout → `204`

---

## Platforms

| Method | Path             | Description                                         |
|--------|------------------|-----------------------------------------------------|
| GET    | `/api/platforms` | Platforms + their login options (account form menu) |

### GET /api/platforms

Read-only catalog built from the adapter beans — one entry per login option.
`credentialFields` maps each **declared** field (the keys) to its
`CredentialFieldKind`: `ID` (shown in full in `credentialsMasked`), `TOKEN`
(`••••` + last 4), `PASSWORD` (fully masked). The SPA gets its input types
from the same map — plain text / tail display / password field. Which fields
may be left blank is not part of the catalog — see the credential-shapes
table in [Social accounts](#social-accounts). `canShare`
marks options that can compose a prefilled composer URL (`GET /api/posts/{id}/share-url`).

```json
[
  { "platform": "FACEBOOK",
    "loginOptions": [
      { "authMethod": "PAGE_ACCESS_TOKEN", "canPublish": true, "canShare": false,
        "credentialFields": { "pageId": "ID", "pageAccessToken": "TOKEN" } },
      { "authMethod": "PROFILE_URL", "canPublish": false, "canShare": true,
        "credentialFields": { "profileUrl": "ID" } }
    ] },
  { "platform": "REDDIT", "loginOptions": [ "... PASSWORD_GRANT ...", "... PROFILE_URL ..." ] },
  { "platform": "X", "loginOptions": [ "... ACCESS_TOKEN ...", "... PROFILE_URL ..." ] },
  { "platform": "LINKEDIN", "loginOptions": [ "..." ] }
]
```

`canPublish: false` marks login options with no platform API access — accounts
using them exist as manual links only and cannot be publish targets.

---

## Social accounts

| Method | Path                                          | Description                                             |
|--------|-----------------------------------------------|---------------------------------------------------------|
| GET    | `/api/social-accounts?showDeleted=`           | List (credentials masked)                               |
| POST   | `/api/social-accounts`                        | Create                                                  |
| GET    | `/api/social-accounts/{id}`                   | Get one                                                 |
| PUT    | `/api/social-accounts/{id}?mergeCredentials=` | Replace displayName and/or credentials (+ `authMethod`) |
| DELETE | `/api/social-accounts/{id}`                   | Soft delete (credentials dropped)                       |
| POST   | `/api/social-accounts/{id}/verify`            | Cheap "who am I" platform call                          |

### AccountDto (all responses)

```json
{
  "id": 1,
  "platform": "FACEBOOK",
  "authMethod": "PAGE_ACCESS_TOKEN",
  "displayName": "My Page",
  "status": "ACTIVE",
  "canPublish": true,
  "canShare": false,
  "credentialsMasked": { "pageId": "123456789012345", "pageAccessToken": "••••1234" },
  "createdAt": "2026-09-22T15:24:58.902685Z",
  "deletedAt": null
}
```

Masking is per-field kind, declared by the login option (see
`GET /api/platforms`): `ID` fields (page IDs, usernames, subreddits, profile
URLs) are returned in full; `TOKEN` fields (`pageAccessToken`, `clientSecret`)
are reduced to `••••` + last 4 characters; `PASSWORD` fields (`password`) and
anything undeclared are plain `••••`. Updating credentials
resets `status` to `UNVERIFIED`; `authMethod` may be switched in the same PUT
(new credentials required — switching alone → `400`).

**Soft delete:** `DELETE /api/social-accounts/{id}` → `204`. The row survives
(publication history references it), `deletedAt` is set, and the stored
credentials are **dropped** (`credentialsMasked` becomes empty). A deleted
account behaves as missing everywhere: `GET/PUT/verify` → `404`, publishing to
it → `404`. It reappears only in `GET ?showDeleted=true`.

### POST /api/social-accounts

```json
{ "platform": "FACEBOOK", "authMethod": "PAGE_ACCESS_TOKEN", "displayName": "My Page",
  "credentials": { "...": "..." } }
```

`displayName` optional (filled by verify). `authMethod` must be one of the
platform's login options (`GET /api/platforms`); an unknown combination → `400`.
Missing required credential fields → `400` listing them. Fields marked
*(optional)* in the table may be left out — they are stored as `""` and the
adapter copes; everything else is required.

**Credential shapes per login option**

| Platform   | `authMethod`        | Fields                                                          | Notes                                                                              |
|------------|---------------------|-----------------------------------------------------------------|------------------------------------------------------------------------------------|
| `FACEBOOK` | `PAGE_ACCESS_TOKEN` | `pageId`, `pageAccessToken`                                     | Long-lived Page token (README has the walkthrough). Verify → page name.            |
| `REDDIT`   | `PASSWORD_GRANT`    | `clientId`, `clientSecret`, `username`, `password`, `subreddit` | ⛔ Reddit closed API access; adapter works but credentials currently unobtainable. |
| `REDDIT`   | `PROFILE_URL`       | `profileUrl`, `subreddit`                                        | Manual; share URL opens `r/<subreddit>/submit` prefilled.            |
| `LINKEDIN` | `ACCESS_TOKEN`      | `accessToken`, `userId` *(optional)*                             | Member (personal profile) token, scopes `openid profile w_member_social`; expires ~60 days. Verify → member name. Posts to `POST /rest/posts`; the title is ignored, a `link` is appended to the text. Optional `userId` (the `/v2/userinfo` `sub`, a full `urn:li:person:` prefix pastes too) pins the post author and skips the member lookup — handy for tokens without the `openid` scope. |
| `LINKEDIN` | `ORGANIZATION_ACCESS_TOKEN` | `organizationId`, `accessToken`                          | Company page token, scopes `w_organization_social` (posting; needs a page ADMINISTRATOR/CONTENT_ADMIN role) + `rw_organization_admin` (verify's name lookup). `organizationId` is the numeric page id — a full `urn:li:organization:` prefix pastes too. Verify → page name. |
| `X`        | `ACCESS_TOKEN`      | `accessToken`, `refreshToken` *(optional)*                      | User token from the OAuth flow (see OAuth (X) below) — with a manually pasted token leave `refreshToken` out. Access tokens last ~2 hours; when a refresh token is stored the adapter refreshes automatically and persists the rotated pair. Verify → `name (@handle)`. The title is ignored; a `link` is appended to the text. |
| other      | `PROFILE_URL`       | `profileUrl`                                                     | Manual; share URL opens the platform composer (link prefilled where supported). |

Field kinds: `pageId`, LinkedIn's `organizationId`/`userId`, Reddit's
`clientId`/`username`/`subreddit`, `profileUrl`
→ `ID` (shown in full); `pageAccessToken`, `clientSecret`, `accessToken`,
X's `refreshToken`
→ `TOKEN` (`••••` + last 4); `password` → `PASSWORD` (fully masked). Undeclared
fields default to `PASSWORD` — secrets fail closed.

### POST /api/social-accounts/{id}/verify

Calls the platform ("who am I") and updates the account:

```json
{ "account": { [AccountDto](#accountdto-all-responses) }, "ok": true, "message": "Credentials verified" }
```

Failure → `ok: false`, `account.status: "INVALID"`, `message` carries the upstream
error (e.g. `"Facebook API error (400): Malformed access token"`). Always `200`.

`PROFILE_URL` accounts never call a platform API: verify only checks that
`profileUrl` is a well-formed http(s) URL, sets `ACTIVE` on success, and leaves
the display name as set. Publishing to such an account records a `FAILED`
publication whose message carries the link (see publish below).

### PUT /api/social-accounts/{id}?mergeCredentials=false

```json
{ "displayName": "My Page",
  "authMethod": "PAGE_ACCESS_TOKEN",
  "credentials": { "...": "..." } }
```

Replace displayName / authMethod / credentials. Every field is optional but `authMethod` can't be passed without `credentials`.
By default, the credentials get replaced as a whole blob.

`mergeCredentials=true` - Used to partially update credentials.
Override only the credential fields given in the request body and leave other fields intact.
Like a replace, a merge re-validates the resulting blob and resets `status` to `UNVERIFIED`.
If the `authMethod` is different from the current one for this account and `mergeCredentials` is set to `true`
returns `400` "Can't merge the credentials while also changing the `authMethod` from `account.authMethod` to `request.authMethod`".
It is recommended to always pass the `authMethod` when merging credentials to make sure you don't accidentally merge
credentials for a wrong auth method.

---

## OAuth (LinkedIn)

Gets a fresh `accessToken` for a LinkedIn token login option — member profile
(`ACCESS_TOKEN`) or company page (`ORGANIZATION_ACCESS_TOKEN`) — via the
3-legged OAuth2 authorization-code flow, so tokens never pass through the
SPA. The account's login option picks the requested scopes (member:
`openid profile w_member_social`; page: `rw_organization_admin
w_organization_social` — needs the Community Management API product on the
app). Needs `LINKEDIN_CLIENT_ID` / `LINKEDIN_CLIENT_SECRET` (see README) and
`smagent.base-url` set to the public origin — it builds both the
LinkedIn-registered redirect URL and the SPA landing page.

| Method | Path                                     | Description                                          |
|--------|------------------------------------------|------------------------------------------------------|
| GET    | `/api/oauth/linkedin/authorize/{id}`     | Start the flow for account `{id}` → 302 to LinkedIn  |
| GET    | `/api/oauth/linkedin/callback`           | LinkedIn's redirect target; ends the flow → 302 to the SPA |

Client flow (SPA POV):

1. When adding an account: create it first (`POST /api/social-accounts`) with
   the user-entered id(s) and a placeholder `accessToken`, keeping the
   returned account `id`. For an existing account, skip to step 2.
2. Send the browser to `/api/oauth/linkedin/authorize/{id}` (top-level
   navigation — the session cookie must ride along).
3. The user approves on LinkedIn and is bounced back through `/callback`;
   smagent exchanges the code and **merges** the fresh token into the account
   (like `PUT ?mergeCredentials=true`: `status` → `UNVERIFIED`, other
   credential fields kept). The browser finally lands on the SPA accounts
   page — `{base-url}/accounts`, or `{base-url}/accounts?oauthError=...`
   when anything failed. The SPA should show that message and offer a retry.

### GET /api/oauth/linkedin/authorize/{id}

`302` to LinkedIn's consent screen (`response_type=code`, the configured
client id, the scopes of the account's login option, and a random `state`).
The state and the target account travel together in the server session — the
callback path itself is constant
(`{base-url}/api/oauth/linkedin/callback`; register exactly this URL in the
LinkedIn app console). One pending OAuth per session: starting a second flow
overwrites the first.

The account is validated up front: unknown id → `404`, a non-LinkedIn
account or a login option with no OAuth flow (e.g. `PROFILE_URL`) → `400` —
as JSON, not redirects, because unlike the callback these are SPA bugs (only
offer the OAuth button for token login options). Requires authentication →
`401`.

### GET /api/oauth/linkedin/callback

Browser-facing terminal leg — LinkedIn calls it top-level with `code` +
`state` (or `error` + `error_description`). Validates the state (constant-time,
single-use: a replay finds nothing left), exchanges the code (the exchange's
`redirect_uri` byte-matches the authorize leg), merges the token, and `302`s
to the accounts page.

Every expected failure still ends in that redirect — never a JSON error page —
with a human-readable `oauthError` query param: user denied on LinkedIn,
missing code/state, missing/invalid/replayed state, dead session ("restart
the authorization"), failed token exchange (LinkedIn's `error_description`
surfaced), or the account having vanished / changed login option mid-flight.
Unexpected server errors still `500` loudly. Requires authentication → `401`.

---

## OAuth (X)

Gets a fresh token pair for an X `ACCESS_TOKEN` account via OAuth2
authorization-code flow **with PKCE** (X requires it; `code_challenge_method`
is always S256). Unlike LinkedIn, where only the access token is saved, X
access tokens expire after ~2 hours — the flow also stores the refresh token
(scope `offline.access` is always requested), and the adapter refreshes
automatically whenever the API answers 401. Refresh tokens are single-use:
each refresh rotates the pair, and the new values are persisted onto the
account after every verify/publish (even a failed one). A refresh token lives
~6 months (longer with regular use); when it expires, calls fail with a
"re-run the OAuth flow" hint — start the flow again.

Needs `X_CLIENT_ID` / `X_CLIENT_SECRET` (see README) and `smagent.base-url`
set to the public origin — it builds both the X-registered redirect URL and
the SPA landing page.

| Method | Path                              | Description                                          |
|--------|-----------------------------------|------------------------------------------------------|
| GET    | `/api/oauth/x/authorize/{id}`     | Start the flow for account `{id}` → 302 to X         |
| GET    | `/api/oauth/x/callback`           | X's redirect target; ends the flow → 302 to the SPA  |

Client flow (SPA POV) — same shape as LinkedIn:

1. When adding an account: create it first (`POST /api/social-accounts`)
   with a placeholder `accessToken`, keeping the returned account `id`.
   For an existing account, skip to step 2.
2. Send the browser to `/api/oauth/x/authorize/{id}` (top-level
   navigation — the session cookie must ride along).
3. The user approves on X and is bounced back through `/callback`; smagent
   exchanges the code (the app credentials go as HTTP Basic auth — X's
   token endpoint rejects them as form fields) and **merges** the fresh
   pair into the account (like `PUT ?mergeCredentials=true`: `status` →
   `UNVERIFIED`). The browser lands on the SPA accounts page —
   `{base-url}/accounts`, or `{base-url}/accounts?oauthError=...` when
   anything failed.

### GET /api/oauth/x/authorize/{id}

`302` to X's consent screen (`response_type=code`, the configured client id,
scope `tweet.read tweet.write users.read offline.access`, a random `state`,
and the S256 `code_challenge`). The state, the PKCE verifier and the target
account travel together in the server session — the callback path itself is
constant (`{base-url}/api/oauth/x/callback`; register exactly this URL in
the X app's User authentication settings). One pending OAuth per session:
starting a second flow overwrites the first.

The account is validated up front: unknown id → `404`, a non-X account or a
login option with no OAuth flow (e.g. `PROFILE_URL`) → `400` — as JSON, not
redirects, because unlike the callback these are SPA bugs. Requires
authentication → `401`.

### GET /api/oauth/x/callback

Browser-facing terminal leg — X calls it top-level with `code` + `state`
(or `error` + `error_description`). Validates the state (constant-time,
single-use: a replay finds nothing left), exchanges the code with the
session-held verifier, merges the token pair, and `302`s to the accounts
page. Every expected failure still ends in that redirect with a
human-readable `oauthError` query param (same failure set as LinkedIn's
callback). Unexpected server errors still `500` loudly. Requires
authentication → `401`.

**Concurrency note:** two simultaneous verify/publish calls on one X account
race the refresh — exactly one rotation wins; the loser fails with
`invalid_grant` and the next call succeeds with the persisted token. Harmless
for a single-owner app.

---

## Posts

| Method | Path                                              | Description                       |
|--------|---------------------------------------------------|-----------------------------------|
| GET    | `/api/posts?status=DRAFT\|PUBLISHED&showDeleted=` | List, newest first                |
| POST   | `/api/posts`                                      | Create DRAFT                      |
| GET    | `/api/posts/{id}`                                 | Detail **incl. publications**     |
| PUT    | `/api/posts/{id}`                                 | Edit (DRAFT only)                 |
| DELETE | `/api/posts/{id}?deletePublications=`             | Soft delete                       |
| POST   | `/api/posts/{id}/publish`                         | Publish to selected accounts      |
| POST   | `/api/posts/{id}/publish-manual`                  | Record a by-hand publication      |
| GET    | `/api/posts/{id}/share-url?accountId=`            | Composer URL for a manual account |

### POST /api/posts

```json
{
  "content": { "title": "Optional — Reddit needs it", "text": "generic body",
               "link": "https://example.com/my-post" },
  "overrides": { "FACEBOOK": { "title": null, "text": "facebook-specific variant" } }
}
```

→ `201` [PostDto](#postdto). `content.text` required (`400` if blank); `link` is
an optional http(s) URL the post shares (`400` if malformed) — used by the
share URLs of manual accounts. `overrides` maps a
platform to **full replacement content** (character limits, per-platform titles);
at publish time each field falls back individually —
`override.title ?: content.title`, `override.text ?: content.text`,
`override.link ?: content.link`.
Reddit validation of a missing title happens at publish time (per-target error),
since targets are chosen then.

### PostDto

```json
{
  "id": 1,
  "content": { "title": null, "text": "generic body", "link": null },
  "status": "DRAFT",
  "overrides": { "FACEBOOK": { "title": null, "text": "facebook variant" } },
  "createdAt": "...", "updatedAt": "...", "deletedAt": null,
  "publications": [ [PublicationDto](#publicationdto) ]
}
```

`content` is the post's own title+text unit (`PostContent`); media fields may be
added there later. `publications` populated on single-post GET and PUT; empty on
list responses.

### PUT /api/posts/{id}

`content` (replaces title+text+link together; a null title/link clears them) and/or
`overrides` (replaced wholesale; each entry must include text). Non-DRAFT post →
`400 "Only DRAFT posts can be edited"`.

### DELETE /api/posts/{id}?deletePublications=

status: ✅

Soft delete; the post vanishes from all endpoints (`404`, like unknown ids) but its
`Publication` rows stay queryable as history — publications are never removed.
`deletePublications` states what happens to the platform copies:

| Post state  | Flag          | Result                                              |
|-------------|---------------|-----------------------------------------------------|
| `DRAFT`     | absent or any | `204` — nothing exists on platforms, flag ignored   |
| `PUBLISHED` | absent        | `400` — explicit choice required                    |
| `PUBLISHED` | `false`       | `204` — local soft delete only, platform posts stay |
| `PUBLISHED` | `true`        | `501` — platform-side deletion not implemented yet  |

Deleted posts reappear only via `GET /api/posts?showDeleted=true` (combinable with
`status`). Editing or publishing a deleted post → `404`.

### POST /api/posts/{id}/publish

```json
{ "accountIds": [1, 2] }
```

→ `200` — per-target outcomes as [PublicationDto](#publicationdto) rows, one failing target never blocks others:

```json
{
  "postId": 1,
  "postStatus": "PUBLISHED",
  "results": [
    { "accountId": 1, "platform": "FACEBOOK", "accountName": "My Page",
      "status": "SUCCESS", "externalUrl": "https://www.facebook.com/123/posts/456",
      "errorMessage": null, "publishedManually": false },
    { "accountId": 2, "platform": "REDDIT", "accountName": "u/me -> r/test",
      "status": "FAILED", "externalUrl": null,
      "errorMessage": "Reddit posts require a non-empty title", "publishedManually": false }
  ]
}
```

Semantics:
- Every attempt writes a `Publication` row **before** the platform call (`PENDING` → `SUCCESS`/`FAILED`).
- Post becomes `PUBLISHED` when ≥1 target succeeds; stays `DRAFT` if all fail.
- Republishing an already-published post is allowed (adds rows — natural retry).
- Unknown `accountId` → `404` for the whole request.
- Accounts whose login option has no API access (`canPublish: false`) fail here —
  open a prefilled composer via `/share-url` below and record the result with
  `/publish-manual`.

### POST /api/posts/{id}/publish-manual

status: ✅

Records a publication that was made **by hand**: the user opened the platform
(ideally via the account's `profileUrl`) and posted there themselves. No
platform API is called; **any** account is eligible, including (but not limited
to) `PROFILE_URL` manual accounts.

```json
{ "accountId": 3, "externalUrl": "https://x.com/my_page/status/1234" }
```

→ `200`, same shape as `/publish` with a single [PublicationDto](#publicationdto) result row:

```json
{
  "postId": 1,
  "postStatus": "PUBLISHED",
  "results": [
    { "accountId": 3, "platform": "X", "accountName": "My X page",
      "status": "SUCCESS", "externalUrl": "https://x.com/my_page/status/1234",
      "errorMessage": null, "publishedManually": true }
  ]
}
```

Semantics:
- Writes a `Publication` row immediately: `SUCCESS`, `publishedManually: true`,
  `publishedAt` = now, `externalUrl` if provided (else `null`).
- Counts toward the post's `PUBLISHED` flip exactly like an API success
  (post stops being editable, delete needs `deletePublications`).
- Repeatable without dedup — log a second share, or fix a wrong URL by recording again.
- `externalUrl`, when present, must be a well-formed http(s) URL → else `400`.
- Unknown or soft-deleted post / account → `404`.

### GET /api/posts/{id}/share-url?accountId= ✅

Builds the platform's composer/share URL for a **manual** (`PROFILE_URL`)
account, prefilled with the post's effective content — per-platform overrides
apply field-by-field, exactly like `/publish`. The SPA opens it in a new tab;
once the user has posted by hand, the result is recorded with
`/publish-manual`.

```
GET /api/posts/1/share-url?accountId=3
→ 200 { "url": "https://x.com/intent/tweet?text=hello%20world&url=https%3A%2F%2Fexample.com" }
```

Per platform (missing pieces are simply omitted — every platform still opens
its composer):

| Platform   | URL template                                                            | Prefilled                         |
|------------|-------------------------------------------------------------------------|-----------------------------------|
| `X`        | `x.com/intent/tweet?text=&url=`                                         | text, link                        |
| `FACEBOOK` | `facebook.com/sharer/sharer.php?u=`                                     | link                              |
| `REDDIT`   | `reddit.com/r/<subreddit>/submit?url=&title=`                            | title, link (subreddit from creds)|
| `LINKEDIN` | `linkedin.com/sharing/share-offsite/?url=`                              | link                              |

Errors: unknown/soft-deleted post or account → `404`; account's login option
cannot compose a URL (API-backed options) → `400`.

`canShare` on the account / login option tells the SPA when to offer this.

---

## Publications

| Method | Path                        | Description                    |
|--------|-----------------------------|--------------------------------|
| GET    | `/api/publications?postId=` | Flat attempt log, newest first |

### PublicationDto

```json
{
  "id": 1, "postId": 1, "accountId": 1, "platform": "FACEBOOK",
  "accountName": "My Page", "status": "SUCCESS", "publishedManually": false,
  "externalUrl": "https://www.facebook.com/123/posts/456",
  "errorMessage": null, "publishedAt": "...", "createdAt": "..."
}
```

`platform` and `accountName` are **publish-time snapshots** — they survive account
renames (verify refreshes `displayName`) and account soft-deletes. `accountId` keeps
the FK link for future per-account queries. `publishedManually: true` marks rows
recorded via `publish-manual` (by-hand posts) instead of an adapter attempt.

---

## Demo feedback (demo-only)
status: ✅

A simple list of text messages a user can add/delete and check/uncheck. For demos
only — standalone items, no relations to other entities, no soft delete, hard
deleted for real.

| Method | Path                  | Description                        |
|--------|-----------------------|------------------------------------|
| GET    | `/api/feedback`       | List, newest first                 |
| POST   | `/api/feedback`       | Add `{text}`                       |
| PATCH  | `/api/feedback/{id}`  | Edit and/or check/uncheck          |
| DELETE | `/api/feedback/{id}`  | Delete (hard)                      |

```json
{ "id": 1, "text": "try the facebook adapter", "checked": false, "createdAt": "..." }
```

`POST` body: `{ "text": "..." }` (required, non-blank). `PATCH` body: any of
`{ "text": "...", "checked": true }`. Blank text → `400`, unknown id → `404`.

## Operational endpoints

Spring Boot Actuator endpoints, outside the `/api` prefix and **available without
authentication**. Only these two actuator endpoints are exposed.

| Method | Path               | Description                                                           |
|--------|--------------------|-----------------------------------------------------------------------|
| GET    | `/actuator/health` | Liveness probe: `{"status":"UP"}` (details hidden by default)         |
| GET    | `/actuator/info`   | Build info incl. version: `{"build":{"version":"…","artifact":…, …}}` |

---

# Planned: Engagement tracking

status: ❌

Receive updates about engagement (comments, reactions) on published posts.

**Approach — polling, not webhooks.** Facebook Page webhooks require a public
callback URL and app review; polling the Graph API on demand (and later via
`@Scheduled`) needs neither. The app's *published* status makes webhooks a viable
future upgrade once it's reachable on a public domain.

**Adapter extension** (per platform):

```kotlin
fun fetchEngagement(credentialsJson: String, externalId: String): EngagementSnapshot
// FB: GET /{post-id}?fields=comments.summary(true),reactions.summary(true)
//     + GET /{post-id}/comments (paged) for the items themselves
```

**New storage:** `EngagementEvent` rows (publication FK, type `COMMENT|REACTION`,
author, text, platform timestamp, seen flag) + denormalized counters on
`Publication` (`commentCount`, `reactionCount`, `lastSyncedAt`).

| Method | Path                                                | Description                                                             | Status |
|--------|-----------------------------------------------------|-------------------------------------------------------------------------|--------|
| POST   | `/api/publications/{id}/sync-engagement`            | Pull latest engagement from the platform, store events, update counters | ❌     |
| GET    | `/api/publications/{id}/engagement`                 | Stored events for one publication                                       | ❌     |
| GET    | `/api/engagement-events?since=&unread=`             | Flat feed across all publications (inbox)                               | ❌     |
| POST   | `/api/engagement-events/{id}/read`                  | Mark one event read                                                     | ❌     |
| POST   | `/api/engagement/sync-all`                          | Sync every SUCCESS publication of every ACTIVE account                  | ❌     |
| GET    | `/api/social-accounts/{id}/publications/engagement` | Engagement summary per account                                          | ❌     |

Automatic polling (config `smagent.engagement.poll-cron`, default off) — ❌ future.

---

# AI providers

Manage credentials for AI backends. Provider types:

| Type                | Provider                                                   | Status |
|---------------------|------------------------------------------------------------|--------|
| `ANTHROPIC`         | Anthropic native API (or any endpoint implementing it)     | ✅     |
| `OPENAI_COMPATIBLE` | OpenAI, OpenRouter, Groq, Mistral, DeepSeek, local Ollama… | ✅     |

API keys are stored encrypted (`apiKeyEnc`) and masked in responses
(`apiKeyMasked`: `••••` + last 4, or plain `••••` when shorter), exactly like
platform credentials. There is **no stored active/default provider** — the SPA
passes its choice (`providerId`/`model`) in every generation request and
remembers it client-side; `/generate` infers only when exactly one provider
exists.

| Method | Path                              | Description                                                        | Status |
|--------|-----------------------------------|--------------------------------------------------------------------|--------|
| GET    | `/api/ai-providers`               | List (masked keys), newest first                                   | ✅     |
| POST   | `/api/ai-providers`               | Create `{name, type, baseUrl?, apiKey, defaultModel}`              | ✅     |
| GET    | `/api/ai-providers/{id}`          | Get one                                                            | ✅     |
| PUT    | `/api/ai-providers/{id}`          | Update name/baseUrl/apiKey/defaultModel (all optional)             | ✅     |
| DELETE | `/api/ai-providers/{id}`          | Hard delete (nothing references a provider yet)                    | ✅     |
| POST   | `/api/ai-providers/{id}/verify`   | Cheap authenticated call (models listing)                          | ✅     |
| GET    | `/api/ai-providers/{id}/models`   | Model ids the key can use (live call) — SPA suggestion list        | ✅     |
| GET    | `/api/ai-providers/baseurls/{type}` | Well-known API roots per type — SPA baseUrl suggestions          | ✅     |
| GET    | `/api/ai-providers/{id}/usage`    | Token usage / remaining quota                                      | ❌     |

### AiProviderDto

```json
{
  "id": 1,
  "name": "Anthropic main",
  "type": "ANTHROPIC",
  "baseUrl": null,
  "apiKeyMasked": "••••1234",
  "defaultModel": "claude-sonnet-5",
  "createdAt": "..."
}
```

`baseUrl` optionally points the provider at a custom endpoint; must be a
well-formed http(s) URL (`400` otherwise). **The convention differs by type:**
`ANTHROPIC` takes the origin only (the `/v1/...` is appended automatically), e.g.
`http://localhost:5001`, while `OPENAI_COMPATIBLE` takes the full API root
**including** the version path — `https://openrouter.ai/api/v1`,
`https://api.groq.com/openai/v1`, `http://localhost:11434/v1` (Ollama); absent
defaults to `https://api.openai.com/v1`.

`defaultModel` is the model used by generation requests that don't name one.
**Required on every provider** (model names are provider-specific —
`claude-sonnet-5`, `gpt-5.2`, `llama3.1`, `deepseek/...` — so there is no
built-in fallback); `GET /api/ai-providers/{id}/models` lists real ids to pick
from. On update it can be replaced but never cleared.

### POST /api/ai-providers

```json
{ "name": "OpenRouter", "type": "OPENAI_COMPATIBLE",
  "baseUrl": "https://openrouter.ai/api/v1", "apiKey": "sk-or-...",
  "defaultModel": "deepseek/deepseek-chat" }
```

→ `201` [AiProviderDto](#aiproviderdto). `name`, `apiKey` and `defaultModel`
required (`400` when blank).

### PUT /api/ai-providers/{id}

Every field optional; absent or blank `apiKey` keeps the stored key. `null`
keeps `baseUrl`/`defaultModel`, an empty string clears `baseUrl` (back to the
type's default) — but `defaultModel` can only be replaced, never cleared
(`400`). Unknown id → `404`.

### POST /api/ai-providers/{id}/verify

Calls the provider (a models listing) without persisting anything — there is no
verify-status machine here; generation works regardless:

```json
{ "ok": true, "message": "Key valid — 12 models available" }
```

Failure → `ok: false` with the upstream error in `message` (e.g.
`"Anthropic API error (401): invalid x-api-key"`). Always `200`.

### GET /api/ai-providers/{id}/models

The same live models listing verify uses, returned as a sorted array of model
ids for the SPA's suggestion list (also handy when picking `defaultModel`):

```json
["claude-opus-5", "claude-sonnet-5"]
```

Unknown id → `404`; upstream failure → `502` with the provider's message. Note
that minimal custom endpoints may implement generation but not the models
listing — such a provider fails here (and on verify) while `/generate` works.

**SPA flow for picking the default model** — the same two-step trick as the
OAuth token flows: create the provider with the real key and a *placeholder*
`defaultModel` (any non-blank string; creation only requires one to exist),
then call this endpoint for the new provider and let the user pick the actual
default from the listing, saving the choice with `PUT /api/ai-providers/{id}`.

### GET /api/ai-providers/baseurls/{type}

Static catalog of well-known API roots (`{provider:url}`) for the SPA's
baseUrl suggestion list — also the machine-readable doc of the per-type
convention (see [AiProviderDto](#aiproviderdto)):

```json
// ANTHROPIC — origin only, /v1 is appended automatically
{ "Anthropic": "https://api.anthropic.com" }

// OPENAI_COMPATIBLE — full root including the version path
{ "OpenAI": "https://api.openai.com/v1",
  "OpenRouter": "https://openrouter.ai/api/v1",
  "Groq": "https://api.groq.com/openai/v1",
  "Mistral": "https://api.mistral.ai/v1",
  "DeepSeek": "https://api.deepseek.com/v1",
  "Ollama (local)": "http://localhost:11434/v1" }
```

Unknown type → `400`.

---

# AI generation

status: ✅

Draft post text with a chosen provider/model. One call produces the generic
content and any requested per-platform variants; the response mirrors the
`PostDto` `content`/`overrides` shape so the frontend can drop it straight into
the post editor.

| Method               | Path                     | Description                                       | Status |
|----------------------|--------------------------|---------------------------------------------------|--------|
| POST                 | `/api/ai/generate`       | One-shot generation for a post draft              | ✅     |
| GET                  | `/api/ai/effort-levels`  | Effort levels for the picker (stub)               | ✅     |
| GET/PUT              | `/api/ai/generation-settings` | Owner-editable per-platform style rules      | ✅     |
| POST                 | `/api/ai/chat`           | Multi-turn refinement conversation                | ❌     |

### POST /api/ai/generate

```json5
{
  "prompt": "Post about releasing v2 of our CLI tool",
  "platforms": ["GENERIC", "X", "REDDIT"],  // optional, default ["GENERIC"]; empty -> 400
  "constraints": { "maxLength": 500, "tone": "casual" },  // both optional, apply to every variant
  "editorState": {                            // optional — current editor state; the AI edits this
    "GENERIC": { "title": "…", "text": "…", "link": "…" },   // draft instead of starting from zero
    "X": { "text": "…" }
  },
  "providerId": 2,            // optional — required (400) when several providers exist and it's omitted
  "model": "claude-sonnet-5", // optional — falls back to provider.defaultModel (required on every
                              //   provider, so generation never needs a built-in fallback)
  "effort": "LOW"             // optional — LOW | MEDIUM | HIGH | XHIGH | MAX, defaults LOW.
                              //   ANTHROPIC only for now — not sent to OPENAI_COMPATIBLE endpoints
}
```

→

```json
{
  "content":  { "title": "v2 is out", "text": "…master version…" },
  "overrides": {
    "X":      { "title": null, "text": "…≤280 chars…" },
    "REDDIT": { "title": "v2 is out", "text": "…" }
  },
  "provider": "Anthropic main",
  "model": "claude-sonnet-5",
  "usage": { "inputTokens": 512, "outputTokens": 613 }
}
```

Semantics:

- `GENERIC` (a `GeneratePlatform`, alongside `FACEBOOK | REDDIT | X | LINKEDIN`)
  becomes `content`; the rest become `overrides` keyed by platform. `content`
  is `null` when `GENERIC` wasn't requested.
- A `title` appears only where it can be used: `REDDIT` entries always carry
  one (Reddit requires it at publish time), and `GENERIC` carries one only when
  `REDDIT` is **not** among the requested variants — then the generic text is
  the fallback for a possible later Reddit publish. The model is explicitly
  told that only Reddit displays a title, so every other text must stand on
  its own.
- The system prompt (platform rules, the JSON response contract, global
  constraints) lives entirely server-side and is identical for every provider
  type; the request carries intent (and, via `editorState`, the draft) only.
- `editorState` entries are the current `PostContent` per platform (`GENERIC` =
  the post's own content). Entries with blank text are ignored; keys outside
  `platforms` are context only. The model is instructed to return **full
  replacements** for the requested keys, never diffs — the response shape is
  unchanged. A `link` is passed as context, and the model is told not to
  repeat the URL in the texts (publishing appends it).
- The result is **not** auto-saved as a post — the frontend previews, the user
  edits, then `POST /api/posts` as usual. Unknown `providerId` → `404`; blank
  `prompt`/empty `platforms` → `400`. A provider without a `defaultModel`
  (only possible for rows created before it became required) and no request
  `model` → `400` naming the provider.
- Upstream AI failures surface as `502` with the provider's message, same as
  platform failures (`AiProviderException`); a model answer that isn't the
  requested JSON is also a `502`.

### GET /api/ai/effort-levels

Stub for the SPA's effort picker — the full enum, same list for every model:

```json
["LOW", "MEDIUM", "HIGH", "XHIGH", "MAX"]
```

Later this can map a model to the values it actually supports; query params
(`providerId`/`model`) would be additive to the stub shape.

### GET/PUT /api/ai/generation-settings

Each platform's rule in the generation system prompt has a fixed structural
half (the JSON entry shape, etc.) and an editable **style** half (the owner's taste — e.g. X's
"at most 280 characters total, punchy."). These endpoints expose and allow to override the style
half.

```json
// GET
{ "platformRules": { "GENERIC": "", "FACEBOOK": "conversational, may run a bit longer.",
                     "REDDIT": "community tone, honest, no corporate speak.",
                     "X": "at most 280 characters total, punchy.",
                     "LINKEDIN": "professional and clear, no hashtag spam." },
  "defaults": { "...same shape, the built-ins — for reset UIs..." } }
```

`PUT` replaces the whole override set — an absent, `null` or blank value for a
platform resets it to its default (`"defaults"` in GET). Each rule is capped
at 1000 characters (`400` beyond).

Note the platform limits themselves (like X's 280) are not enforced by the
app — a style that exceeds them simply produces posts the platform may reject
at publish time.

### Token usage — what's actually possible (planned endpoint)

Honest constraints (worth knowing before building the UI):

- **Every provider returns a `usage` block on each completion** (`input_tokens`,
  `output_tokens` — already echoed per generate response). Accumulating these
  per provider locally gives reliable "used" numbers, no extra API call.
- **"Remaining" is provider-specific and often unavailable.** Anthropic exposes no
  remaining-credit endpoint for standard keys; OpenAI's billing/usage endpoints vary
  by account type; OpenRouter has `/api/v1/credits`.

So the planned usage endpoint returns a best-effort shape with an explicit source label:

```json
{
  "usedTokens": { "input": 152340, "output": 48210, "total": 200550 },
  "usedRequests": 37,
  "remaining": { "credits": 8.42, "currency": "USD" },
  "source": "LOCAL | PROVIDER",
  "asOf": "..."
}
```

`source: LOCAL` = locally accumulated (always available); `PROVIDER` = live quota
call succeeded (OpenRouter-style providers only). The UI should label estimates.
