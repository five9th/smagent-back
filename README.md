# SMAgent

Version: <!-- gradle:version -->0.2.0<!-- /gradle:version -->

Single-owner backend for AI-assisted social media posting. Multi-account publishing
to platform APIs via per-platform adapters, encrypted credential storage, and a
publication log that records every publish attempt.

Current scope (MVP):

- **Platforms:** Facebook Pages, Reddit & LinkedIn (text posts), X (manual link
  accounts only)
- **Auth:** one owner account, session cookie (HttpOnly, SameSite=Lax)
- **Publishing:** synchronous "publish now", per-target success/failure;
  by-hand posts recorded via `publish-manual`
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
- Health on `http://localhost:8080/actuator/health`, version on `http://localhost:8080/actuator/info` — both public, no auth
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

**LinkedIn (personal profile)**
1. Create an app at developer.linkedin.com and add the products
   "Share on LinkedIn" (`w_member_social`) and "Sign In with LinkedIn using
   OpenID Connect" (`openid`, `profile`).
2. Run the OAuth2 authorization-code flow yourself (e.g. once from the app's
   auth URL + the token exchange) and copy the member access token. Request the
   scopes `openid profile w_member_social` — the adapter asks LinkedIn who the
   token belongs to via `/v2/userinfo` (`openid`), and falls back to posting
   through LinkedIn's `{accessTokenSubject}` author placeholder if the token
   can't answer that; `openid`+`profile` let verify fill the display name.
3. Store as `{ "accessToken": "..." }`. Member tokens expire after ~60 days —
   re-paste and re-verify when posts start failing with 401.

**Any platform — PROFILE_URL login option**

Every platform also supports the manual `PROFILE_URL` option:
`{ "profileUrl": "https://facebook.com/my_page" }` (Reddit adds a required
`subreddit` — its share URL opens that sub's submit form). The app gets no API
access; instead `GET /api/posts/{id}/share-url?accountId=` returns the
platform's composer with the post's content prefilled where the platform
allows (X: text+link · Reddit: title+link · Facebook/LinkedIn: link). Verify
only checks the stored fields' shape; publishing to such an account via the
API records a failed attempt whose message carries the composer link.

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

GET    /api/platforms                                          platforms + login options
GET    /api/social-accounts?showDeleted=                       IDs shown, tokens tail-masked, passwords hidden
POST   /api/social-accounts        {platform, authMethod, credentials, displayName?}
GET    /api/social-accounts/{id}
PUT    /api/social-accounts/{id}?mergeCredentials=   {displayName?, authMethod?, credentials?}
DELETE /api/social-accounts/{id}                              soft delete, creds dropped
POST   /api/social-accounts/{id}/verify                        cheap "who am I" call

GET    /api/posts?status=DRAFT|PUBLISHED&showDeleted=
POST   /api/posts                  {content: {title?, text}, overrides?}   title required for Reddit
GET    /api/posts/{id}                                          incl. publications
PUT    /api/posts/{id}             DRAFT posts only
DELETE /api/posts/{id}?deletePublications=                    soft delete; flag required for PUBLISHED posts (true -> 501 for now)
POST   /api/posts/{id}/publish     {accountIds: []}             per-target results
POST   /api/posts/{id}/publish-manual {accountId, externalUrl?}  record a by-hand post
GET    /api/posts/{id}/share-url?accountId=                     prefilled composer URL

GET    /api/publications?postId=                                flat attempt log
```

`content` is the post's text, optional title and optional share `link` as one
unit. `overrides` maps a
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

The app version lives in `build.gradle.kts` (`version = …`) and is the single
source of truth: `springBoot { buildInfo() }` bakes it into the jar as
`META-INF/build-info.properties` (served by `GET /actuator/info` as
`build.version`), and the `syncDocsVersion` task rewrites the version lines at
the top of this README and `docs/API.md` on every build — don't edit them by hand.

Adding a login option = one class implementing `SocialPlatformAdapter`
(`platform`, `authMethod`, `credentialFields`, `verify`, `publish`).
API-backed options live in `platform/adapter/<platform>/`; the manual
`PROFILE_URL` option is shared — add a one-line `ProfileUrlAdapter` subclass in
`platform/adapter/common/` so a new platform is usable before it has API
access. Dispatch is by (platform, authMethod); nothing else changes.
