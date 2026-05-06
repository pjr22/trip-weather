# Trip Weather — agent notes

## Before running the application

The app needs API keys for OpenRouteService, GeoApify, and NREL, plus a database password. The user-accounts feature also adds Mailtrap (for verification / password-reset email) and a remember-me signing key. Without these, the app won't start cleanly or will fail at first request.

```bash
source setEnvVariables.source       # exports OPENROUTESERVICE_API_KEY, GEOAPIFY_API_KEY, NREL_API_KEY
export TRIP_DB_PASSWORD='<password>' # required separately; app fails fast at startup if unset (see CODE_REVIEW.md P0 #1)
```

`setEnvVariables.source` reads each key from a sibling `*_api_key.txt` file (`openRouteService_api_key.txt`, `geoApify_api_key.txt`, `developer.nrel.gov_api_key.txt`). All three text files are gitignored — each developer keeps their own copy. If any are missing, sourcing the file will fail loudly.

### User-accounts environment

`StartupConfigValidator` enforces fail-fast at boot. With production defaults, you also need:

```bash
# Mailtrap REST sending — sandbox URL during dev, live URL in production.
export TRIP_EMAIL_URL='https://sandbox.api.mailtrap.io/api/send/<inbox-id>'
export TRIP_EMAIL_APIKEY='<mailtrap-api-token>'

# Server-side secret that signs persistent-login cookies. Anything random; the
# app rejects existing remember-me cookies if you change this.
export TRIP_REMEMBER_ME_KEY="$(openssl rand -base64 48)"
```

To skip Mailtrap and remember-me on a fresh checkout — useful when you're iterating on something unrelated and don't want to provision either:

```bash
export TRIP_EMAIL_ENABLED=false        # EmailService logs the would-be email body at INFO instead of POSTing
export TRIP_REMEMBER_ME_ENABLED=false  # SecurityConfig skips the rememberMe filter; the login checkbox is a no-op
export TRIP_APP_BASE_URL=http://localhost:8090
export TRIP_COOKIE_SECURE=false        # default true assumes HTTPS terminator in front; flip false for plain-HTTP dev
```

When `TRIP_EMAIL_ENABLED=false`, `EmailService.send()` logs the full text body of the would-be email (including the verification or reset URL) at INFO between `--- text body ---` separators — copy the URL from the log to drive the verify / reset flow without an inbox.

Other knobs (sensible defaults, override only when needed):

| Var | Default | What it does |
|---|---|---|
| `TRIP_AUTH_EMAIL_TOKEN_LIFETIME_MINUTES` | `5` | Lifetime of signup-verification + forgot-password tokens. Bump up if Mailtrap delivery latency makes 5 min too tight. |
| `ROUTE_CLEANUP_ENABLED` | `true` | Gates only the daily guest-route sweep. Token sweeps (email + remember-me) always run on the same cron. |
| `ROUTE_CLEANUP_RETENTION_DAYS` | `30` | Guest routes older than this are deleted. |
| `ROUTE_CLEANUP_CRON` | `0 0 3 * * *` | When the whole cleanup job wakes up (all three sweeps share it). |

## Build / run

- `./gradlew bootRun` — start the app on `localhost:8090` (same port serves the SPA at `/` and the API at `/api/*`).
- `./gradlew test` — run the test suite (77 tests as of Phase 5: user-account service flows, signup-collision branches, password-reset flow, cleanup scheduler).
- `./gradlew compileJava` — compile only.

JDK 21 toolchain (`build.gradle`); the Gradle wrapper auto-provisions if needed.

## Schema migrations

The user-accounts feature ships one idempotent psql migration script that applies all schema changes (Phase 1 + Phase 4 from `USER_ACCOUNTS_PLAN.md`) in a single transaction:

```bash
export TRIP_DB_PASSWORD='<password>'
./user-accounts-db-migration.sh
```

What it sets up: `users.{email,password_hash,enabled}` columns + UNIQUE on email + guest-row backfill; `email_verifications` and `password_resets` tables; `ON DELETE CASCADE` on `waypoints.route_id` and `routes.user_id`; `persistent_logins` table (Spring Security remember-me shape) + `username` index.

Defaults to `localhost` / db `postgres` / user `postgres` — override with `TRIP_DB_HOST`, `TRIP_DB_PORT`, `TRIP_DB_NAME`, `TRIP_DB_USERNAME` if your env differs. Safe to re-run; everything inside one BEGIN/COMMIT, so a failure rolls back to the original schema.

## Changing the database password

```bash
export TRIP_DB_PASSWORD='<current-password>'
./change-db-password.sh '<new-password>'
```

Issues `ALTER USER <role> WITH PASSWORD ...` (using psql's `:'newpw'` variable interpolation, which handles single-quote / backslash escapes correctly), then reconnects with the new password to verify the change took. After success, update `TRIP_DB_PASSWORD` wherever it's set (shell rc, systemd unit, docker env) and restart the app.

## Project layout pointers

- `examples/` — reference API responses, curl scripts, and large WMS XML dumps. Not part of the app.
- `CODE_REVIEW.md` — durable log of code-review issues and resolutions (P0 / P1 / P2 / P3). Read this before touching anything that looks like it might already be tracked.
- `USER_ACCOUNTS_PLAN.md` — full design and phasing for the user-accounts feature. Phases 1–6 implemented. Consult before changing `User` / `Route` / `RoutePersistenceService` / `UserAccountService` / `SecurityConfig` / `RememberMeConfig` / the auth modals or profile menu.
