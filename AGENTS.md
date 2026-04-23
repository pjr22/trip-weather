# Trip Weather — agent notes

## Before running the application

The app needs API keys for OpenRouteService, GeoApify, and NREL, plus a database password. Without these the app won't start cleanly or will fail at first request.

```bash
source setEnvVariables.source       # exports OPENROUTESERVICE_API_KEY, GEOAPIFY_API_KEY, NREL_API_KEY
export TRIP_DB_PASSWORD='<password>' # required separately; app fails fast at startup if unset (see CODE_REVIEW.md P0 #1)
```

`setEnvVariables.source` reads each key from a sibling `*_api_key.txt` file (`openRouteService_api_key.txt`, `geoApify_api_key.txt`, `developer.nrel.gov_api_key.txt`). All three text files are gitignored — each developer keeps their own copy. If any are missing, sourcing the file will fail loudly.

## Build / run

- `./gradlew bootRun` — start the app on `localhost:8090` (same port serves the SPA at `/` and the API at `/api/*`).
- `./gradlew test` — run the test suite (currently a single context-load test).
- `./gradlew compileJava` — compile only.

JDK 21 toolchain (`build.gradle`); the Gradle wrapper auto-provisions if needed.

## Project layout pointers

- `examples/` — reference API responses, curl scripts, and large WMS XML dumps. Not part of the app.
- `CODE_REVIEW.md` — durable log of code-review issues and resolutions (P0 / P1 / P2 / P3). Read this before touching anything that looks like it might already be tracked.
