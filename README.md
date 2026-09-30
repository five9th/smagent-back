# SMAgent

Single-owner backend for AI-assisted social media posting. Multi-account publishing
to platform APIs via per-platform adapters, encrypted credential storage, and a
publication log that records every publish attempt.

Current scope (MVP):

- **Platforms:** Facebook Pages, Reddit (text posts)
- **Auth:** one owner account, session cookie (HttpOnly, SameSite=Lax)
- **Publishing:** synchronous "publish now", per-target success/failure
- **Not yet:** AI generation, media upload, scheduling

## Stack

Spring Boot 4.1 / Kotlin / Java 25 · H2 (file mode) · Gradle.

## Running

```bash
export SMAGENT_ADMIN_PASSWORD='choose-a-password'   # required on first start
# optional: SMAGENT_ADMIN_USERNAME=admin
# optional: SMAGENT_ENCRYPTION_KEY=$(head -c 32 /dev/urandom | base64)

./gradlew bootRun
```

- API on `http://localhost:8080/api`
- H2 console on `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/smagent`) — disable in production with `spring.h2.console.enabled=false`

State lives in `./data/` (H2 database + auto-generated encryption key file). Back
it up together; losing the key makes stored credentials unrecoverable.

### Configuration

| Env var                                                 | Purpose                                                   | Default                             |
|---------------------------------------------------------|-----------------------------------------------------------|-------------------------------------|
| `SMAGENT_ADMIN_PASSWORD`                                | Owner password, seeded on first start; fail-fast if unset | none                                |
| `SMAGENT_ADMIN_USERNAME`                                | Owner username                                            | `admin`                             |
| `SMAGENT_ENCRYPTION_KEY`                                | base64 of 32 bytes; overrides the generated key file      | generated into `./data/smagent.key` |
| `SMAGENT_CORS_ORIGINS` (`smagent.cors.allowed-origins`) | Allowed SPA origins                                       | `http://localhost:5173`             |

### Deploying on a VPS

Run behind a reverse proxy that terminates TLS (Caddy/nginx). Cookies are
`SameSite=Lax`; the API is JSON-only and does not mutate on GET, which is the
CSRF posture while token protection is disabled. Keep the H2 console off and
the port unexposed except through the proxy.

## Getting platform credentials

**Facebook Page**
1. Create an app at developers.facebook.com (type: Business).
2. Get a User access token with `pages_show_list`, `pages_manage_posts`,
   `pages_read_engagement` (Graph API Explorer helps).
3. Exchange it for a long-lived User token, then fetch the Page token —
   long-lived Page tokens effectively do not expire.
4. Store as `{ "pageId": "...", "pageAccessToken": "..." }`.

**Reddit**

⚠Reddit API was closed⚠ \
Maybe I will figure something out later.
1. ~~Create a **script**-type app at reddit.com/prefs/apps
   (redirect URI can be `http://localhost:8080`).~~
2. ~~Store as `{ "clientId", "clientSecret", "username", "password", "subreddit" }`.
   Password grant works for your own account; tokens are fetched hourly and cached.
   2FA on the account breaks password grant.~~

Always run the account **verify** endpoint after pasting credentials — it does a
cheap "who am I" call and tells you immediately if something is wrong.

## API

Full reference with request/response examples and implementation status:
**[docs/API.md](docs/API.md)** (also covers the planned engagement and AI endpoints).

Short version — all under `/api`, session-authenticated except login. Errors are
`{ "timestamp", "status", "message" }`.

```
POST /api/auth/login            {username, password}       -> sets session cookie
POST /api/auth/logout
GET  /api/auth/me

GET    /api/social-accounts?showDeleted=                       credentials masked
POST   /api/social-accounts        {platform, credentials, displayName?}
GET    /api/social-accounts/{id}
PUT    /api/social-accounts/{id}   {displayName?, credentials?}
DELETE /api/social-accounts/{id}                              soft delete, creds dropped
POST   /api/social-accounts/{id}/verify                        cheap "who am I" call

GET    /api/posts?status=DRAFT|PUBLISHED&showDeleted=
POST   /api/posts                  {content: {title?, text}, overrides?}   title required for Reddit
GET    /api/posts/{id}                                          incl. publications
PUT    /api/posts/{id}             DRAFT posts only
DELETE /api/posts/{id}?deletePublications=                    soft delete; flag required for PUBLISHED posts (true -> 501 for now)
POST   /api/posts/{id}/publish     {accountIds: []}             per-target results

GET    /api/publications?postId=                                flat attempt log
```

`content` is the post's text (and optional title) as one unit. `overrides` maps a
platform to replacement content, e.g. `{"FACEBOOK": {"text": "shorter text"}}` —
missing fields fall back to the post's own content at publish time; Reddit needs a
`title`. Publishing is idempotent-ish per attempt: every
attempt (even failed) is recorded as a `Publication` row; a post becomes
`PUBLISHED` when at least one target succeeds and can be republished (retry).

## Development

```bash
./gradlew test        # unit + integration (in-memory H2, mocked adapters)
./gradlew bootRun
```

Adding a platform = one class implementing `SocialPlatformAdapter`
(`platform`, `requiredCredentialFields`, `verify`, `publish`). Dispatch is by
enum; nothing else changes.
