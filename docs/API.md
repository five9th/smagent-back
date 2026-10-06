# SMAgent API Reference

Version: `<!-- gradle:version -->0.1.0<!-- /gradle:version -->`

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
| Manual publish tracking             | ✅     | `POST /api/posts/{id}/publish-manual` — record posts made by hand                     |
| Publication history                 | ✅     | One row per post × account attempt                                                    |
| Post management (drafts, overrides) | ✅     |                                                                                       |
| Soft delete (accounts & posts)      | ✅     | History preserved; `showDeleted` reveals; creds dropped on account delete             |
| Demo feedback list                  | ✅     | Demo-only; standalone items, no relations, no soft delete                             |
| Health & version endpoints          | ✅     | `/actuator/health`, `/actuator/info` — no auth, only exposed actuator endpoints       |
| Delete published post on platform   | ❌     | `deletePublications=true` returns `501` until implemented                             |
| Engagement tracking (comments etc.) | ❌     | Design below                                                                          |
| AI provider management + usage      | ❌     | Design below                                                                          |
| AI text generation / chat           | ❌     | Design below                                                                          |
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
| 502  | upstream platform/AI API failure (`PlatformException`)                                                                       |
| 500  | unexpected server error (logged)                                                                                             |

**Query flags** use camelCase + `true`/`false` (`showDeleted=true`, `deletePublications=false`).
(Spring also accepts `yes/no`, `on/off`, `1/0` — tolerated but undocumented.)

**Enums**

```
Platform:            FACEBOOK | REDDIT | X | LINKEDIN
AuthMethod:          PAGE_ACCESS_TOKEN | PASSWORD_GRANT | PROFILE_URL
CredentialFieldKind: ID | TOKEN | PASSWORD
AccountStatus:       UNVERIFIED | ACTIVE | INVALID
PostStatus:          DRAFT | PUBLISHED
PublicationStatus:   PENDING | SUCCESS | FAILED
```

`AuthMethod` availability is per platform (see `GET /api/platforms`); only
`PROFILE_URL` exists on every platform. `CredentialFieldKind` says how a
credential field is rendered in `credentialsMasked`: `ID` in full, `TOKEN` as
`••••` + last 4 characters, `PASSWORD` fully masked.

---

## Authentication

| Method | Path               | Description                      | Status |
|--------|--------------------|----------------------------------|--------|
| POST   | `/api/auth/login`  | Authenticate, set session cookie | ✅     |
| POST   | `/api/auth/logout` | Invalidate session               | ✅     |
| GET    | `/api/auth/me`     | Current user                     | ✅     |

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

| Method | Path             | Description                                         | Status |
|--------|------------------|-----------------------------------------------------|--------|
| GET    | `/api/platforms` | Platforms + their login options (account form menu) | ✅     |

### GET /api/platforms

Read-only catalog built from the adapter beans — one entry per login option.
`credentialFields` maps each **required** field (the keys) to its
`CredentialFieldKind`: `ID` (shown in full in `credentialsMasked`), `TOKEN`
(`••••` + last 4), `PASSWORD` (fully masked). The SPA gets its input types
from the same map — plain text / tail display / password field.

```json
[
  { "platform": "FACEBOOK",
    "loginOptions": [
      { "authMethod": "PAGE_ACCESS_TOKEN", "canPublish": true,
        "credentialFields": { "pageId": "ID", "pageAccessToken": "TOKEN" } },
      { "authMethod": "PROFILE_URL", "canPublish": false,
        "credentialFields": { "profileUrl": "ID" } }
    ] },
  { "platform": "REDDIT", "loginOptions": [ "... PASSWORD_GRANT ...", "... PROFILE_URL ..." ] },
  { "platform": "X", "loginOptions": [ { "authMethod": "PROFILE_URL", "canPublish": false, "..." : "..." } ] },
  { "platform": "LINKEDIN", "loginOptions": [ "..." ] }
]
```

`canPublish: false` marks login options with no platform API access — accounts
using them exist as manual links only and cannot be publish targets.

---

## Social accounts

| Method | Path                                | Description                                             | Status |
|--------|-------------------------------------|---------------------------------------------------------|--------|
| GET    | `/api/social-accounts?showDeleted=` | List (credentials masked)                               | ✅     |
| POST   | `/api/social-accounts`              | Create                                                  | ✅     |
| GET    | `/api/social-accounts/{id}`         | Get one                                                 | ✅     |
| PUT    | `/api/social-accounts/{id}`         | Replace displayName and/or credentials (+ `authMethod`) | ✅     |
| DELETE | `/api/social-accounts/{id}`         | Soft delete (credentials dropped)                       | ✅     |
| POST   | `/api/social-accounts/{id}/verify`  | Cheap "who am I" platform call                          | ✅     |

### AccountDto (all responses)

```json
{
  "id": 1,
  "platform": "FACEBOOK",
  "authMethod": "PAGE_ACCESS_TOKEN",
  "displayName": "My Page",
  "status": "ACTIVE",
  "canPublish": true,
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
Missing required credential fields → `400` listing them.

**Credential shapes per login option**

| Platform   | `authMethod`        | Required fields                                                 | Notes                                                                              |
|------------|---------------------|-----------------------------------------------------------------|------------------------------------------------------------------------------------|
| `FACEBOOK` | `PAGE_ACCESS_TOKEN` | `pageId`, `pageAccessToken`                                     | Long-lived Page token (README has the walkthrough). Verify → page name.            |
| `REDDIT`   | `PASSWORD_GRANT`    | `clientId`, `clientSecret`, `username`, `password`, `subreddit` | ⛔ Reddit closed API access; adapter works but credentials currently unobtainable. |
| any        | `PROFILE_URL`       | `profileUrl`                                                    | Manual only: no API access; account is a link to open and post by hand.            |

Field kinds: `pageId`, Reddit's `clientId`/`username`/`subreddit`, `profileUrl`
→ `ID` (shown in full); `pageAccessToken`, `clientSecret` → `TOKEN` (`••••` +
last 4); `password` → `PASSWORD` (fully masked). Undeclared fields default to
`PASSWORD` — secrets fail closed.

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

---

## Posts

| Method | Path                                                   | Description                    | Status |
|--------|--------------------------------------------------------|--------------------------------|--------|
| GET    | `/api/posts?status=DRAFT\|PUBLISHED&showDeleted=`      | List, newest first             | ✅     |
| POST   | `/api/posts`                                           | Create DRAFT                   | ✅     |
| GET    | `/api/posts/{id}`                                      | Detail **incl. publications**  | ✅     |
| PUT    | `/api/posts/{id}`                                      | Edit (DRAFT only)              | ✅     |
| DELETE | `/api/posts/{id}?deletePublications=`                  | Soft delete                    | ✅     |
| POST   | `/api/posts/{id}/publish`                              | Publish to selected accounts   | ✅     |
| POST   | `/api/posts/{id}/publish-manual`                       | Record a by-hand publication   | ✅     |

### POST /api/posts

```json
{
  "content": { "title": "Optional — Reddit needs it", "text": "generic body" },
  "overrides": { "FACEBOOK": { "title": null, "text": "facebook-specific variant" } }
}
```

→ `201` [PostDto](#postdto). `content.text` required (`400` if blank). `overrides` maps a
platform to **full replacement content** (character limits, per-platform titles);
at publish time each field falls back individually —
`override.title ?: content.title`, `override.text ?: content.text`.
Reddit validation of a missing title happens at publish time (per-target error),
since targets are chosen then.

### PostDto

```json
{
  "id": 1,
  "content": { "title": null, "text": "generic body" },
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

`content` (replaces title+text together; a null title clears it) and/or
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
  use `/publish-manual` below to record posts made by hand.

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

---

## Publications

| Method | Path                        | Description                    | Status |
|--------|-----------------------------|--------------------------------|--------|
| GET    | `/api/publications?postId=` | Flat attempt log, newest first | ✅     |

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

| Method | Path                  | Description                        | Status |
|--------|-----------------------|------------------------------------|--------|
| GET    | `/api/feedback`       | List, newest first                 | ✅     |
| POST   | `/api/feedback`       | Add `{text}`                       | ✅     |
| PATCH  | `/api/feedback/{id}`  | Edit and/or check/uncheck          | ✅     |
| DELETE | `/api/feedback/{id}`  | Delete (hard)                      | ✅     |

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

# Planned: AI providers

status: ❌

Manage credentials for AI backends. Two provider types cover the whole market:

| Type                | Who                                                        | How                                         |
|---------------------|------------------------------------------------------------|---------------------------------------------|
| `ANTHROPIC`         | Anthropic native API                                       | `api.anthropic.com`, `x-api-key`            |
| `OPENAI_COMPATIBLE` | OpenAI, OpenRouter, Groq, Mistral, DeepSeek, local Ollama… | chat-completions via configurable `baseUrl` |

API keys are stored encrypted (`apiKeyEnc`) and masked in responses, exactly like
platform credentials. Exactly one provider is **active** at a time; any generation
call can still target a specific `providerId` for A/B.

| Method | Path                              | Description                                               | Status |
|--------|-----------------------------------|-----------------------------------------------------------|--------|
| GET    | `/api/ai-providers`               | List (masked keys, active flag, usage summary)            | ❌     |
| POST   | `/api/ai-providers`               | Create `{name, type, baseUrl?, apiKey, defaultModel?}`    | ❌     |
| GET    | `/api/ai-providers/{id}`          | Get one                                                   | ❌     |
| PUT    | `/api/ai-providers/{id}`          | Update name/baseUrl/apiKey/defaultModel                   | ❌     |
| DELETE | `/api/ai-providers/{id}`          | Delete (refuse if active? or auto-fallback)               | ❌     |
| POST   | `/api/ai-providers/{id}/verify`   | Cheap authenticated call (`GET /v1/models` on both types) | ❌     |
| POST   | `/api/ai-providers/{id}/activate` | Make active                                               | ❌     |
| GET    | `/api/ai-providers/{id}/models`   | Models the key can use                                    | ❌     |
| GET    | `/api/ai-providers/{id}/usage`    | Token usage / remaining quota                             | ❌     |

### Token usage — what's actually possible

Honest constraints (worth knowing before building the UI):

- **Every provider returns a `usage` block on each completion** (`input_tokens`,
  `output_tokens`). We accumulate these per provider locally → reliable
  "used" numbers, no extra API call.
- **"Remaining" is provider-specific and often unavailable.** Anthropic exposes no
  remaining-credit endpoint for standard keys; OpenAI's billing/usage endpoints vary
  by account type; OpenRouter has `/api/v1/credits`.

So the usage endpoint returns a best-effort shape with an explicit source label:

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

---

# Planned: AI generation / chat

status: ❌

Generate publication text with a chosen provider/model.

| Method | Path               | Description                          | Status |
|--------|--------------------|--------------------------------------|--------|
| POST   | `/api/ai/generate` | One-shot generation for a post draft | ❌     |
| POST   | `/api/ai/chat`     | Multi-turn refinement conversation   | ❌     |

### POST /api/ai/generate

```json5
{
  "prompt": "Post about releasing v2 of our CLI tool",
  "platform": "FACEBOOK",
  "constraints": { "maxLength": 500, "needsTitle": false, "tone": "casual" },
  "providerId": 2,          // optional — defaults to the active provider
  "model": "claude-sonnet-5" // optional — defaults to provider's defaultModel
}
```

→

```json
{
  "text": "…generated…",
  "title": null,
  "provider": "Anthropic main",
  "model": "claude-sonnet-5",
  "usage": { "inputTokens": 512, "outputTokens": 184 }
}
```

The result is **not** auto-saved as a post — the frontend previews, the user edits,
then `POST /api/posts` as usual. `platform`/`constraints` shape the system prompt
(e.g. Reddit → also produce a title, X → ≤280 chars when X support lands).

### POST /api/ai/chat

Stateless pass-through of the conversation (frontend owns history):

```json
{
  "messages": [
    { "role": "user", "content": "Draft a post about X" },
    { "role": "assistant", "content": "…" },
    { "role": "user", "content": "Shorter, add an emoji" }
  ],
  "providerId": 2, "model": "claude-sonnet-5"
}
```

→ `{ "reply": "…", "provider": "…", "model": "…", "usage": { … } }`

Generation failures surface as `502` with the upstream message (same
`PlatformException`-style handling as social platforms).
