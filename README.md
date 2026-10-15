# SMAgent

Version: <!-- gradle:version -->0.4.0<!-- /gradle:version -->

Single-owner backend for AI-assisted social media posting. Multi-account publishing
to platform APIs via per-platform adapters, encrypted credential storage, and a
publication log that records every publish attempt.

Current scope (MVP):

- **Platforms:** Facebook Pages, LinkedIn, X (text posts) and Reddit (adapter
  exists, API closed); every platform also supports manual link accounts
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
| `APP_BASE_URL` (`smagent.base-url`)                     | Public origin — builds the OAuth redirect/landing URLs   | `http://localhost:8080`             |
| `LINKEDIN_CLIENT_ID` (`smagent.linkedin.client-id`)     | LinkedIn app client id (OAuth token flow)                | placeholder                          |
| `LINKEDIN_CLIENT_SECRET` (`smagent.linkedin.client-secret`) | LinkedIn app client secret (OAuth token flow)         | placeholder                          |
| `X_CLIENT_ID` (`smagent.x.client-id`)                   | X app OAuth2 client id                                   | placeholder                          |
| `X_CLIENT_SECRET` (`smagent.x.client-secret`)           | X app OAuth2 client secret                               | placeholder                          |

### Deploying on a VPS

[//]: # (TODO: mention the yaml configs in '/opt/smagent/')
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
2. **Preferred — let the app fetch the token**: set `LINKEDIN_CLIENT_ID` /
   `LINKEDIN_CLIENT_SECRET`, register `{APP_BASE_URL}/api/oauth/linkedin/callback`
   as a redirect URL in the app console, then hit
   `GET /api/oauth/linkedin/authorize/{accountId}` from the browser (the SPA's
   "get token with OAuth" button; see docs/API.md → OAuth). You land back on
   the accounts page with the token already merged in. Works for company
   pages too — the scopes come from the account's login option.
   **Manual alternative**: run the OAuth2 authorization-code flow yourself and
   paste the token — request the scopes `openid profile w_member_social`; the
   adapter asks LinkedIn who the token belongs to via `/v2/userinfo`
   (`openid`), and falls back to posting through LinkedIn's
   `{accessTokenSubject}` author placeholder if the token can't answer that.
3. Store as `{ "accessToken": "..." }` — optionally add `"userId"`: the member
   id (`sub`) that `/v2/userinfo` reports (a full `urn:li:person:` URN pastes
   too). When set it pins the post author and skips the userinfo lookup —
   handy for tokens without the `openid` scope. Member tokens expire after
   ~60 days — when posts start failing with 401, re-run the OAuth flow (or
   re-paste) and re-verify.

**LinkedIn (company page)**

Same OAuth2 flow, but posting as the page instead of yourself:
1. On the same app, add the **Community Management API** product — it grants
   the `w_organization_social` (posting) and `rw_organization_admin`
   (page lookups) scopes.
2. Your LinkedIn account needs a page role of **Administrator** or
   **Content Admin** on the target company page.
3. **Preferred — let the app fetch the token**: the OAuth flow is the same as
   for personal profiles — `GET /api/oauth/linkedin/authorize/{accountId}`
   picks the company scopes automatically from the account's login option;
   same registered callback URL, same landing page. **Manual alternative**:
   run the flow yourself and paste the token.
4. Store as `{ "organizationId": "...", "accessToken": "..." }` — the
   numeric page id (a full `urn:li:organization:...` URN pastes too; find the
   id in the page's admin view or via the lookup API).

Verify reads the page name; if the token is live but verify stays nameless
(403 on the lookup), posting may still work — the first publish is the real
test.

**X (X.com account)**
1. Create a project + app at developer.x.com (app type "Web App"); in the
   app's **User authentication settings** set up OAuth 2.0, register
   `{APP_BASE_URL}/api/oauth/x/callback` as the callback/redirect URI, and
   make the app read-write. Posting needs a plan with post-creation access —
   the free tier allows a small number of posts per month (~500 at the time
   of writing).
2. Set `X_CLIENT_ID` / `X_CLIENT_SECRET` (OAuth 2.0 Client ID and Client
   Secret from the app's keys page), then create an X account with the
   `ACCESS_TOKEN` login option and a placeholder token, and hit
   `GET /api/oauth/x/authorize/{accountId}` from the browser (the SPA's
   "get token with OAuth" button). X requires PKCE — running the flow
   yourself by hand is impractical, so the in-app flow is the way.
3. The flow stores `{ "accessToken", "refreshToken" }`. X access tokens last
   ~2 hours; whenever one expires the adapter refreshes it automatically via
   the stored refresh token and persists the new pair (refresh tokens are
   single-use — rotated on every refresh). A refresh token lives ~6 months
   (longer with regular use); when it dies, posts start failing with a
   "re-run the OAuth flow" hint — click the button again.

Verify → `name (@handle)`; the title is ignored and a `link` is appended to
the text.

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

GET    /api/oauth/linkedin/authorize/{id}                      302 to LinkedIn consent (token flow)
GET    /api/oauth/linkedin/callback                            LinkedIn's redirect target -> 302 to the SPA

GET    /api/oauth/x/authorize/{id}                             302 to X consent (OAuth2 + PKCE token flow)
GET    /api/oauth/x/callback                                   X's redirect target -> 302 to the SPA

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
