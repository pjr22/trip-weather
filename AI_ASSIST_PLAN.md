# AI Trip Planning Assistant — design & phasing (AI_ASSIST_PLAN.md)

An authenticated-only feature that lets a user describe a trip in plain text and
have a preconfigured AI provider turn that description into an ordered list of
waypoints and a calculated route, displayed in the existing UI.

Two user-facing surfaces:

1. **AI Providers** — a profile-menu manager (alongside My Favorites / My Routes)
   for saving one-or-more AI provider configurations to the account.
2. **AI Assist** — a toolbar button (next to *New Route*) opening a dialog with a
   provider picker, a free-text area, and Submit / Cancel.

Backend lands first so the prompting and processing can be exercised and tuned
over curl before any UI exists; the UI phases follow once the backend is solid.

---

## Working agreement (process)

Per the established workflow: implement one phase / one item at a time; pause and
ask when a real decision surfaces rather than guessing; document resolutions as
we go; touch `CLAUDE.md` / `DEPLOYMENT_INSTRUCTIONS.md` / README last. Migration
scripts live under `dev_scripts/` and are **run by the user** — the agent edits
them only. PostgreSQL must be up for tests / bootRun / migrations; if it isn't,
ask the user to start it rather than working around it.

---

## Decisions locked (from kickoff Q&A)

| Topic | Decision |
|---|---|
| **API key storage** | **Encrypt at rest.** AES-256-GCM with a new server-side secret `TRIP_AI_ENC_KEY` (same blank-default + fail-fast pattern as the remember-me key). API keys are **write-only** over the API — never returned to the browser after save; editing means re-entering (or leaving blank to keep the stored value). |
| **Provider integration** | **Hand-rolled HTTP** via the existing `HttpClientConfig` client. One OpenAI-compatible client covers **OpenAI + Custom + Ollama** (shared `/v1/chat/completions` shape); a second small client speaks the **Anthropic Messages API**. No new framework dependency. |
| **AI output format** | **Prompt-instructed strict JSON + tolerant parsing.** Request provider JSON-mode where supported (OpenAI / Ollama `response_format`); extract the JSON block defensively; one-shot "repair" re-ask on parse failure. Portable across all four provider types. |
| **UI result handling** | **Load as the working route, unsaved.** The result populates the map + waypoint list as the current (already-calculated) route, exactly like *New Route* would, but not persisted. The user reviews/edits and uses the existing *Save Route* flow to keep it. |

## Defaults chosen by the agent (documented; change if you disagree)

- **Ollama enablement** — driven entirely by an operator env var
  `TRIP_AI_OLLAMA_URL` (the "Ollama service URL"; blank by default). When it is
  set to a valid http/https URL, Ollama is offered as a provider and that URL is
  the Ollama endpoint. When blank or unparseable, Ollama is disabled and never
  appears in the picker. No runtime "is a local Ollama running" probe. Because
  this URL is operator-supplied (typically an internal address such as a
  `forgotten_net` container name), it is **trusted** and exempt from the SSRF
  guard below. *(Kept the `TRIP_AI_` prefix for consistency with every other var
  in the app — say the word if you'd rather it be the bare `OLLAMA_SERVICE_URL`.)*
- **Model field** — a **dropdown populated from the provider's own model-list
  endpoint** (no free-text entry). The backend exposes a model-discovery call
  that queries the provider using the supplied/stored credentials and returns the
  available model IDs; the config form loads that list into a `<select>`. Endpoints
  used per provider:
  - OpenAI / Custom (OpenAI-compatible): `GET {baseUrl}/models` (Bearer key) →
    `data[].id`.
  - Anthropic: `GET {baseUrl}/v1/models` (`x-api-key` + `anthropic-version`) →
    `data[].id`.
  - Ollama: `GET {ollamaUrl}/api/tags` → `models[].name`.
  Chicken-and-egg note: discovery needs the API key, which on the *create* form
  isn't saved yet — so discovery accepts the in-progress credentials (see the
  model-discovery endpoints in Phase 1). If a provider's list call fails (bad key,
  unreachable), the UI surfaces a clear error rather than silently offering an
  empty dropdown.
- **Geocoding failures** — resolve each AI-returned location best-effort and
  return the successful waypoints plus the unresolvable ones as **structured,
  editable entries** (Phase 4a `unresolved[]`) that the Phase 4 resolution modal
  lets the user edit, re-geocode, or drop. A single unresolvable stop does not
  fail the whole request; fewer than two resolved waypoints returns the waypoints
  with a (route-level) warning and no route. Each lookup is
  retried on a transient failure (error *or* empty result) per
  `TRIP_AI_GEOCODE_RETRIES` (default 1) with a `TRIP_AI_GEOCODE_RETRY_DELAY_SECONDS`
  (default 3) pause — implemented in Phase 2 after a transient empty result dropped
  a waypoint during testing.
- **Safety caps** — `TRIP_AI_MAX_WAYPOINTS` (default 25) caps how many locations
  we will geocode/route from one response; `TRIP_AI_REQUEST_TIMEOUT_MS`
  (default 30000) bounds the model call.

---

## Reused existing capabilities (no changes expected)

| Capability | Entry point | Use |
|---|---|---|
| Forward geocoding | `LocationService.searchLocations(String)` → `JsonNode` (Geoapify features) | Resolve each `"name, city, state"` to lat/lon + formatted name. |
| Route calculation | `RouteService.calculateRoute(List<RouteRequest.Waypoint>, ZonedDateTime, List<Integer>)` → `RouteData` | Build the route from resolved waypoints. |
| Current user | `CurrentUserService.currentUser()` → `Optional<User>` | Scope provider configs + assist to the authenticated user. |
| HTTP client | `HttpClientConfig` | Outbound calls to the AI providers. |
| Auth gating | `SecurityConfig` user chain; `AuthService.onChange` (JS) | Restrict endpoints + show/hide UI. |

Feature templates to mirror (structure, not behavior): the **favorites** slice
(`FavoriteWaypoint` entity → repository → service → controller → request/response
records; `FavoritesService.js` + `FavoritesManagerModal.js`; profile-menu wiring
in `UIManager.renderProfileMenu`).

---

## Data model

New table **`ai_provider_configs`** (migration: `dev_scripts/ai-assist-db-migration.sh`,
idempotent, single `BEGIN/COMMIT`):

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | `@PrePersist` generates, as in `FavoriteWaypoint`. |
| `user_id` | `uuid` NOT NULL | FK → `users(id)` **ON DELETE CASCADE**. |
| `provider` | `varchar` NOT NULL | Enum string: `OPENAI` \| `ANTHROPIC` \| `CUSTOM` \| `OLLAMA`. |
| `nickname` | `varchar(255)` NOT NULL | User label for the config. |
| `model` | `varchar(255)` NOT NULL | e.g. `gpt-4o-mini`. |
| `api_key_encrypted` | `text` NULL | AES-GCM ciphertext (base64 `iv‖ciphertext‖tag`). NULL allowed for `CUSTOM`/`OLLAMA`. |
| `base_url` | `varchar(1023)` NULL | **User-supplied only for `CUSTOM`** (OpenAI-compatible root, incl. `/v1`; subject to the SSRF guard). NULL for `OPENAI`/`ANTHROPIC` (server defaults) and `OLLAMA` (uses the operator's `TRIP_AI_OLLAMA_URL`). |
| `created` | `timestamptz` NOT NULL | |
| `deleted_at` | `timestamptz` NULL | Soft-delete, mirroring the favorites/routes convention (`@SQLRestriction("deleted_at IS NULL")`). |

Indexes / constraints:

- Per-user index on live rows: `(user_id) WHERE deleted_at IS NULL`.
- Case-insensitive uniqueness of nickname per user on live rows:
  partial unique on `(user_id, LOWER(nickname)) WHERE deleted_at IS NULL`
  (frees the label again after soft-delete — same trick as favorites labels).

`Provider` is a Java enum. Entity carries no decrypted key; encryption/decryption
happens in the service layer only.

---

## Encryption design

- New `AiKeyCipher` component: AES-256-GCM, random 12-byte IV per value, output
  `base64(iv ‖ ciphertext ‖ gcmTag)`. Key material from `TRIP_AI_ENC_KEY`
  (base64-encoded 32 bytes).
- `StartupConfigValidator` requires `TRIP_AI_ENC_KEY` when
  `trip.ai.assist.enabled=true` (fail-fast at boot, matching the remember-me /
  admin credential checks). Generate with `openssl rand -base64 32`.
- The key is **write-only** across the API: response DTOs never include it; they
  expose a boolean `apiKeySet` instead. On update, a blank/absent key field keeps
  the stored ciphertext; a non-blank value re-encrypts.
- Rotating `TRIP_AI_ENC_KEY` invalidates all stored keys (documented; users
  re-enter). Acceptable for a first cut; key-versioning is a future option.

---

## Configuration / env vars

All under the `trip.ai.*` namespace in `application.properties`, `TRIP_AI_*`
env vars, blank default only for the secret (per config-defaults convention):

| Var | Default | Purpose |
|---|---|---|
| `TRIP_AI_ASSIST_ENABLED` | `true` | Master switch. When `false`: `/api/ai/**` endpoints are gated off, the toolbar button and profile-menu entry are hidden, and `TRIP_AI_ENC_KEY` is not required. |
| `TRIP_AI_ENC_KEY` | `` (empty) | base64 32-byte AES key for at-rest API-key encryption. **Required when assist is enabled** (fail-fast). |
| `TRIP_AI_OLLAMA_URL` | `` (empty) | Operator-set Ollama service URL. Set to a valid http/https URL to **enable** the Ollama provider and use that endpoint; blank/invalid disables Ollama. Operator-trusted, exempt from the SSRF guard. |
| `TRIP_AI_OPENAI_BASE_URL` | `https://api.openai.com/v1` | Default base for the `OPENAI` provider (override rarely needed). |
| `TRIP_AI_ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | Default base for the `ANTHROPIC` provider. |
| `TRIP_AI_ANTHROPIC_VERSION` | `2023-06-01` | `anthropic-version` header value. |
| `TRIP_AI_REQUEST_TIMEOUT_MS` | `30000` | Connect+read timeout for model calls. |
| `TRIP_AI_MAX_WAYPOINTS` | `25` | Cap on locations geocoded/routed from one response. |
| `TRIP_AI_ASSIST_DEBUG` | `false` | When `true`, the assist response includes the generated prompt and raw model text — for prompt tuning during the backend phase. Keep `false` in production. |
| `TRIP_AI_GEOCODE_RETRIES` | `1` | Retries for a per-location forward geocode that errors or returns no result (attempts = retries + 1). |
| `TRIP_AI_GEOCODE_RETRY_DELAY_SECONDS` | `3` | Delay between geocode retry attempts. Worst-case added latency ≈ max-waypoints × retries × delay, only on failing lookups. |

`setEnvVariables.source` should learn `TRIP_AI_ENC_KEY` (and optionally
`TRIP_AI_ASSIST_ENABLED`) so a fresh dev shell is ready; documented in CLAUDE.md.

---

# Phase 1 — Backend: provider-config CRUD

Mirrors the favorites slice end-to-end.

**New classes (package layout follows existing `model` / `repository` / `service`
/ `controller` / `dto` split):**

- `model/AiProviderConfig.java` — entity (above). `@SQLRestriction("deleted_at IS NULL")`.
- `model/AiProvider.java` — enum `OPENAI, ANTHROPIC, CUSTOM, OLLAMA`.
- `repository/AiProviderConfigRepository.java`
  - `findAllByUserId(UUID)` (live, ordered by nickname)
  - `findByIdAndUserId(UUID, UUID)` (ownership; missing → 404, not 403)
  - `existsByUserIdAndNicknameIgnoreCase(UUID, String)` (duplicate pre-check)
- `service/AiProviderConfigService.java`
  - `list()`, `get(id)`, `create(req)`, `update(id, req)`, `delete(id)` (soft).
  - Resolves user via `CurrentUserService.currentUser()` (auth-only, no guest).
  - Validation → nested `@ResponseStatus` exceptions:
    `ProviderConfigNotFoundException` (404),
    `DuplicateNicknameException` (409),
    `InvalidProviderConfigException` (400).
  - Validation rules: `nickname`, `model`, `provider` required;
    `api_key` required for `OPENAI`/`ANTHROPIC`, optional for `CUSTOM`/`OLLAMA`;
    `base_url` required for `CUSTOM` only (and must pass the shared SSRF URL guard);
    `base_url` rejected/ignored for the other providers; `OLLAMA` is only accepted
    when `TRIP_AI_OLLAMA_URL` is configured.
  - Encrypts via `AiKeyCipher` on create/update; never returns plaintext.
- `service/ai/AiModelDiscoveryService.java` — `listModels(provider, apiKey, baseUrl)`
  hitting the provider's model-list endpoint (OpenAI/Custom `GET /models`,
  Anthropic `GET /v1/models`, Ollama `GET /api/tags`) and normalizing to
  `List<String>`. Reused by both discovery endpoints (the `{id}` variant decrypts
  the stored key first). Custom base URLs pass through `OutboundUrlGuard` first.
  Optional short-lived cache keyed by
  provider+baseUrl+key-hash to avoid re-hitting the provider on every form tweak.
- `controller/AiProviderConfigController.java` — `/api/ai/providers`
  - `GET /api/ai/providers` → list of summaries
  - `GET /api/ai/providers/{id}` → one summary
  - `POST /api/ai/providers` → 201 (or 409/400)
  - `PUT /api/ai/providers/{id}` → 200 (or 404/409/400)
  - `DELETE /api/ai/providers/{id}` → 204 (or 404)
  - `GET /api/ai/providers/available` → `{ providers: [...] }` (Ollama included only when `TRIP_AI_OLLAMA_URL` is a valid URL)
  - **Model discovery** (for the config form's model dropdown):
    - `POST /api/ai/providers/models` — body
      `{ provider, apiKey?, baseUrl? }` (the in-progress create-form credentials);
      returns `{ models: [...] }`. Used when adding a config.
    - `GET /api/ai/providers/{id}/models` — uses the **stored** decrypted key;
      used when editing an existing config without re-entering the key.
    - Both surface provider failures as a clear status (e.g. 401/502) with a
      message the UI can show ("couldn't load models — check the API key").
- DTOs (records): `CreateProviderConfigRequest`, `UpdateProviderConfigRequest`
  (carry write-only `apiKey`), `ProviderConfigSummary`
  (`id, provider, nickname, model, baseUrl, apiKeySet, created` — **no key**),
  `ModelDiscoveryRequest` (`provider, apiKey?, baseUrl?`),
  `ModelListResponse` (`List<String> models`).

**Security** (`SecurityConfig` user chain): `/api/ai/**` requires authentication.
When `trip.ai.assist.enabled=false`, the chain (or a guard) makes `/api/ai/**`
return 404/403 so a disabled feature isn't reachable.

**Phase 1 tests** — config CRUD + ownership-returns-404 + duplicate-nickname-409
+ validation branches (custom-needs-base-url, key-required-for-openai/anthropic);
`AiKeyCipher` encrypt/decrypt round-trip + tamper-detection; unauth → 401;
model discovery against `MockRestServiceServer` for each provider's list endpoint
(normalization + provider-error → mapped status) and the `{id}` variant using the
stored key.

---

# Phase 2 — Backend: AI Assist pipeline

The heart of the feature: text in → waypoints + route out.

**Provider client layer (hand-rolled):**

- `service/ai/AiChatClient.java` — interface
  `String complete(AiChatCall call)` where `AiChatCall` carries
  `model, systemPrompt, userPrompt, jsonMode, apiKey, baseUrl, timeoutMs`.
- `service/ai/OpenAiCompatibleChatClient.java` — POST `{baseUrl}/chat/completions`;
  `Authorization: Bearer {apiKey}` when present; body `{model, messages:[system,user],
  response_format:{type:"json_object"} when jsonMode}`; reads
  `choices[0].message.content`. Serves `OPENAI`, `CUSTOM`, and `OLLAMA`
  (Ollama base = `{TRIP_AI_OLLAMA_URL}/v1`).
- `service/ai/AnthropicChatClient.java` — POST `{baseUrl}/v1/messages`;
  headers `x-api-key`, `anthropic-version`; body `{model, max_tokens, system,
  messages:[{role:user,...}]}`; reads `content[0].text`. JSON is requested via the
  prompt (no `thinking`/tool params needed for a single structured extraction).
- `service/ai/AiChatService.java` — dispatches to the right client by
  `AiProvider`, supplying defaults (`TRIP_AI_OPENAI_BASE_URL`,
  `TRIP_AI_ANTHROPIC_BASE_URL`, the operator's Ollama URL) and the config's
  `base_url` for `CUSTOM`.
- `service/ai/OutboundUrlGuard.java` — the shared SSRF guard (scheme check,
  single-label-host rejection, DNS-resolve-and-require-public-IP across all
  records). Applied to the `CUSTOM` `base_url` by **both** the chat clients and
  `AiModelDiscoveryService`; operator-set URLs bypass it. See the security
  section for the full rule set and the DNS-rebinding follow-up.

**Prompt + parsing:**

- `service/ai/AssistPromptBuilder.java` — builds the system prompt (role: trip
  planner; output **strict JSON** `{"locations":[{"name","city","state"}, ...]}`
  in travel order; honor `TRIP_AI_MAX_WAYPOINTS`; bias toward the regions the app
  serves — kept easily editable since this is the main tuning surface) and the
  user message (the raw free text).
- `service/ai/LocationListParser.java` — tolerant JSON extraction
  (find first `{`…matching `}`), maps to `List<AiLocation>`. On parse failure, one
  "repair" re-ask ("return only valid JSON matching this schema …"); a second
  failure → `AiAssistException` (502/422) with a clear message.

**Orchestration:**

- `service/ai/AiAssistService.java`
  1. Load the user's provider config (`findByIdAndUserId` → 404 if not owned).
  2. Decrypt key; call `AiChatService` with JSON-mode where supported.
  3. Parse → ordered `AiLocation` list (capped).
  4. For each: `LocationService.searchLocations("name, city, state")`; take the
     best/first feature; collect lat/lon + formatted name; record misses in
     `warnings[]`.
  5. Build `List<RouteRequest.Waypoint>`; if ≥2, call
     `RouteService.calculateRoute(waypoints, now, null)`; else skip route.
  6. Return `AiAssistResponse`.
- `controller/AiAssistController.java` — `POST /api/ai/assist`
  - Request `AiAssistRequest { UUID providerConfigId; String prompt; }`
  - Response `AiAssistResponse { List<ResolvedWaypoint> waypoints; RouteData route;
    List<String> warnings; String debugPrompt; String debugRawResponse; }`
    (the two `debug*` fields populated only when `TRIP_AI_ASSIST_DEBUG=true`).
  - `ResolvedWaypoint` shape aligns with what the frontend needs to load a
    working route (lat, lon, name/locationName, city, state, elevation if known).
  - **Phase 4 enriches this** with `sequence` on `ResolvedWaypoint` and a
    structured `List<UnresolvedLocation> unresolved`, moving per-location "couldn't
    find" out of `warnings` — see Phase 4a.

**Provider availability** (`GET /api/ai/providers/available`): returns the
offerable provider list — always OpenAI/Anthropic/Custom; Ollama only when
`TRIP_AI_OLLAMA_URL` is set to a valid http/https URL. This is a config check, not
a runtime reachability probe.

**Phase 2 tests** (mock the seams; no live model calls):

- `OpenAiCompatibleChatClient` + `AnthropicChatClient` against
  `MockRestServiceServer` (matches the existing routing-test style) — request
  shape, headers, JSON-mode flag, content extraction.
- `LocationListParser` — clean JSON, fenced/`json` block, trailing prose,
  repair-then-succeed, repair-then-fail.
- `AiAssistService` with mocked `AiChatService` + `LocationService` +
  `RouteService` — happy path, partial geocode failure → warnings, <2 resolved →
  no route, max-waypoints cap, model error → mapped exception.
- Availability endpoint with `TRIP_AI_OLLAMA_URL` set/valid vs blank/invalid.
- `OutboundUrlGuard` — accepts a public host; rejects http(s)-violations,
  single-label hosts (`localhost`, `trip-ors`), and hosts resolving to
  loopback/link-local/private/ULA addresses (IPv4 + IPv6), including a host with
  mixed public+private records.

**Backend tuning loop (no UI):** with `TRIP_AI_ASSIST_DEBUG=true`, drive the flow
over curl using the dev test account (`dev@test.org`): log in, `POST
/api/ai/providers` to save a config, `POST /api/ai/assist` with sample prompts,
inspect `debugPrompt` / `debugRawResponse` / `warnings`, and iterate on
`AssistPromptBuilder`. This satisfies the "test and fine-tune before UI" goal.

---

# Phase 3 — Frontend: AI Providers manager

Mirrors `FavoritesManagerModal` + `FavoritesService`.

- `static/js/services/AiProviderService.js` — `list()`, `get(id)`, `create()`,
  `update()`, `remove(id)`, `available()`, `discoverModels({provider, apiKey, baseUrl})`
  (POST) and `discoverModelsForConfig(id)` (GET, stored key).
- `static/js/managers/AiProvidersModal.js` — list saved configs (provider,
  nickname, model); add/edit form with:
  - provider `<select>` filtered by `available()`,
  - API key — write-only; shows `•••• stored` placeholder when `apiKeySet`,
    blank-on-edit keeps the existing value; required for OpenAI/Anthropic,
  - base URL — shown only for Custom (required); not shown for Ollama
    (the endpoint comes from the operator's `TRIP_AI_OLLAMA_URL`),
  - **model `<select>` populated by model discovery** — a "Load models" action
    (or auto on key/base-URL blur) calls the discovery endpoint and fills the
    dropdown; on the edit form with the key left blank it uses the `{id}` variant.
    A discovery failure shows a clear inline error (e.g. bad key) and leaves the
    dropdown empty/disabled rather than allowing a bad save,
  - nickname,
  - delete per row. Uses the shared `Toast` utility for feedback.
- `static/js/managers/UIManager.js` — add an **"AI Providers"** entry to
  `renderProfileMenu` for authenticated users (near My Favorites / My Routes),
  dispatched in `handleProfileMenuAction`. Hidden when assist is disabled.

---

# Phase 4 — Frontend: AI Assist button + dialog + resolution modal

Result handling uses an interactive **resolution modal** (not a toast) whenever a
location can't be geocoded or a route-level warning is raised. The user edits or
drops each unfound stop, re-runs the geocode search, and — once enough stops
resolve — applies the route (a single recalculation). They can also ignore the
unresolved stops and route with what's left.

## 4a. Backend support — structured unresolved locations

The modal needs the unfound stops in editable form, so `/api/ai/assist` gets a
small enrichment (lands first, since Phase 2 is already committed):

- `ResolvedWaypoint` gains **`sequence`** (0-based index in the AI's ordering) so
  the frontend can reconstruct travel order when mixing resolved + re-resolved
  stops.
- New DTO **`UnresolvedLocation { int sequence; String query; }`** — `query` is
  the exact geocode string that failed, prefilled into that row's edit field. No
  separate name/city/state fields: the user just edits the one query string (or
  deletes the row).
- `AiAssistResponse` gains **`List<UnresolvedLocation> unresolved`**. `warnings`
  now carries only route-level messages ("too many locations, used the first N";
  "could not calculate a route"), not the per-location "couldn't find" lines —
  those become structured `unresolved` entries.
- `AiAssistService.assist`: track each location's index; a resolved stop →
  `ResolvedWaypoint(sequence, …)` (its `locationName` is the matched address the
  modal prefills for that row); a geocode miss (after the configured retries) →
  `UnresolvedLocation(sequence, query)`. The preview `route` is still computed
  from the resolved set.
- Tests: the partial-geocode-failure test asserts on `unresolved` (with sequence)
  rather than a "couldn't find" warning.

## 4b. AI Assist button + submit dialog

- `static/index.html` — `#ai-assist-btn` in `.header-buttons` next to *New Route*,
  with a sparkles / magic-wand SVG icon. Hidden when unauthenticated or assist
  disabled (same `assistEnabled` gating the profile-menu entry uses).
- `static/js/services/AiAssistService.js` — `submit({providerConfigId, prompt})`
  → `POST /api/ai/assist`.
- `static/js/managers/AiAssistModal.js` — submit dialog: provider-config
  `<select>` (from `AiProviderService.list()`; if the user has no configs, a CTA
  that opens the AI Providers manager), a free-text `<textarea>`, **Submit**
  (spinner / disabled while in flight) and **Cancel**.

## 4c. Result handling

On the assist response:

- **No `unresolved` and a route present** → load the resolved waypoints into the
  **working route** directly (clear current route → add waypoints in `sequence`
  order → run the existing calculate-route flow). Unsaved; kept via *Save Route*.
- **Any `unresolved`, or a route-level warning** → open the **resolution modal**
  (below) instead of loading directly. No toast.

## 4d. Resolution modal

**Trigger** is unchanged: the modal only opens when there are warnings — i.e. at
least one `unresolved` stop (or a route-level warning). But when it does, it lists
**every** stop, good and bad, in `sequence` order, so the user sees the whole
route and can adjust any of it before committing.

- **One row per stop**, in `sequence` order, laid out left→right:
  1. a **status icon** — green ✓ (resolved) or red ✗ (unresolved);
  2. the **sequence number**;
  3. an **editable text field** — prefilled with the matched address for a
     resolved stop, or the failed `query` for an unresolved one;
  4. a **Delete** action (drop this stop).
  Every row — good or bad — is editable and deletable.
- A read-only **warnings** section for any route-level `warnings`.
- **Re-search** — re-geocode every row that is unresolved **or** whose text was
  edited since it last resolved (untouched resolved rows keep their coordinates —
  no wasted lookups). Uses the SPA's existing forward-geocode (the same
  `/api/location/search` the search box uses; first feature — the same first-match
  rule the backend applies). Each row's icon updates from the result: a match
  flips/keeps ✓ (carrying lat/lon + matched address), a miss shows ✗. No new
  endpoint.
- **Use this route** (apply) — build the final waypoint list from all currently
  resolved (✓) rows in `sequence` order, load it into the working route, and run
  calculate-route once. Enabled when ≥2 stops are resolved; any still-unresolved
  rows are left out (the "ignore the warnings" path). Disabled with a hint when
  fewer than 2 resolve.
- **Cancel** — discard; nothing is loaded onto the map.

Row state the modal tracks per entry: `{ sequence, text, status, lat, lon,
matchedAddress, resolvedText }`. Editing the field that differs from
`resolvedText` is what marks a row for re-geocoding on the next Re-search.

Reuses existing machinery end to end: forward-geocode (`/api/location/search`),
waypoint population (`WaypointManager`), and the existing calculate-route flow.
The backend's preview `route` is informational — the authoritative render comes
from the frontend calculate once the final waypoint set is known. Nothing loads
onto the map until **Use this route**, so the modal is a clean gate.

- `static/js/app.js` — wire `#ai-assist-btn` → open modal; gate button visibility
  via `AuthService.onChange` + `assistEnabled`.
- `static/css/ai-assist.css` (or extend `ai-providers.css`) — dialog +
  resolution-row styling; reuse the shared `.modal-content.modal-wide` viewport
  cap so a long stop list scrolls within the dialog.

---

## Security review considerations (track in CODE_REVIEW.md as implemented)

- **At-rest secrets** — API keys encrypted (AES-GCM), write-only over the API,
  excluded from logs and from all response DTOs.
- **Ownership** — every provider-config and assist lookup is scoped to the
  current user; cross-user access returns **404** (not 403), matching favorites.
- **Outbound SSRF guard** — the only user-supplied outbound URL is the `CUSTOM`
  provider's `base_url`. Every outbound AI request that uses it (both
  model-discovery and chat) goes through one shared `OutboundUrlGuard` that:
  1. requires an `http`/`https` scheme;
  2. **rejects single-label hosts** (no dot — e.g. `localhost`, or a bare name
     that could be a Docker container / k8s service such as `trip-ors`,
     `tripdb`, `ollama`);
  3. **resolves the host via DNS and requires every returned address to be a
     public IP** — rejecting loopback (`127.0.0.0/8`, `::1`), link-local
     (`169.254.0.0/16` incl. the `169.254.169.254` metadata IP, `fe80::/10`),
     private (`10/8`, `172.16/12`, `192.168/16`), unique-local (`fc00::/7`), and
     other non-public ranges. All A/AAAA records are checked, not just the first.
  Operator-set URLs (`OPENAI`/`ANTHROPIC` defaults, the `TRIP_AI_OLLAMA_URL`
  Ollama endpoint) are trusted and bypass the guard — that's what lets Ollama sit
  at an internal/container address.
  - **Known residual risk (DNS rebinding / TOCTOU):** resolve-then-connect leaves
    a gap where a hostname could resolve public at check time and private at
    connect time. Full hardening = pin the validated IP for the actual connection
    (custom DNS resolver / connecting to the resolved IP with the `Host` header
    preserved) or re-validate at connect. Plan to pin; if the first cut only
    resolves-and-checks, note the gap as a tracked follow-up.
- **Prompt-injection / cost** — the model output is parsed structurally and only
  used to drive geocoding + routing (no tool execution); waypoint count is capped;
  the model call is timeout-bounded.
- **Disabled-feature reachability** — when `TRIP_AI_ASSIST_ENABLED=false`,
  `/api/ai/**` is not reachable and the UI affordances are hidden.

---

## Docs to update (last)

- `CLAUDE.md` — env-var table additions; `dev_scripts/ai-assist-db-migration.sh`
  in Schema migrations; test-count bump; a "Consult before changing …" list for
  the new classes/files; `setEnvVariables.source` coverage note.
- `DEPLOYMENT_INSTRUCTIONS.md` — new env vars (esp. `TRIP_AI_ENC_KEY` generation)
  and the migration step.
- Memory — note the branch + status once shipped (mirrors the other plan entries).

---

## Phasing checklist

- [x] **Phase 1** (committed d3d3016) — schema + migration;
      `AiProviderConfig`/`AiProvider`; repository/service/controller + DTOs;
      `AiKeyCipher`; `StartupConfigValidator` + config keys; `SecurityConfig`
      `/api/ai/**`; model discovery + `OutboundUrlGuard`; tests.
- [x] **Phase 2** (committed ae497d5) — provider clients (OpenAI-compatible +
      Anthropic) + dispatcher; prompt builder + tolerant parser/repair; assist
      orchestration; geocode retry; `/api/ai/assist` +
      `/api/ai/providers/available`; tests; curl tuning loop.
- [x] **Phase 3** (committed 93c12e5) — `AiProviderService.js` +
      `AiProvidersModal.js` + profile-menu entry; shared wide-modal viewport cap.
- [x] **Phase 4** — 4a backend enrichment (`unresolved[]` + `sequence`); 4b AI
      Assist button + submit dialog (`AiAssistModal.js` + `AiAssistService.js`);
      4c direct-load when clean; 4d resolution modal (`AiResolutionModal.js` —
      edit / delete / re-search / apply / ignore); app wiring. Suite green (435).
- [x] Docs + memory — README.md (user-facing "Plan with AI" + operator
      assistant section + quick-start key), DEPLOYMENT_INSTRUCTIONS.md
      (`TRIP_AI_*` env + secret file), CLAUDE.md (env table, migration, test
      count, project-layout pointer), memory entry updated.

---

## Open questions / deferred

- **Region bias of the prompt** — the app's weather is US-centric (NWS) while
  geocoding (Geoapify) is global and local routing is Western US. The prompt
  builder will start US-biased; revisit once we see real output.
- **Per-stop dates/durations** — the assist returns ordered stops only; arrival
  times come from `calculateRoute` with a default departure. Letting the user
  describe timing ("2 nights in Moab") and parsing it into durations is a future
  enhancement.
- **Provider "Test connection"** — largely covered for free, since loading the
  model dropdown round-trips the credentials; a dedicated button is deferred.
- **DNS-rebinding hardening** — pin the validated IP for the actual outbound
  connection (vs. resolve-then-connect). Tracked as the SSRF follow-up if the
  first cut only resolves-and-checks.
- **Encryption-key versioning** — to allow `TRIP_AI_ENC_KEY` rotation without
  invalidating stored keys; deferred.

### Tuning backlog (observed during Phase 2 curl testing)

Deferred per decision — note now, fix later. Observed with `dev@test.org` configs
`local-ollama` (gemma4:latest) and `gpt-4o-mini`:

- **Geocoding leaks internationally on a bad model location.** A Denver→Cody
  prompt where the model returned `{"name":"Limon","city":"","state":"Wyoming"}`
  (Limon is in CO, blank city) geocoded to *Limon, France*, producing a
  Denver+France pair that ORS couldn't route. Fix candidate: constrain assist
  geocoding to the US (Geoapify `filter=countrycode:us` / `bias`) via a path
  separate from the shared search-box `searchLocations`, and/or a plausibility
  check that warns when a resolved waypoint is implausibly far from its
  neighbours. The failure degraded gracefully (warning + no route, HTTP 200).
- **"Avoid interstates" / preference instructions aren't honoured by routing.**
  The directions call uses default driving-car; the model can only act on such
  hints when *choosing* waypoints. Adding ORS `avoid_features` (e.g.
  `["highways"]`) to the directions request is a separate feature.
- **Output quality is model-dependent.** gpt-4o-mini handled the multi-stop
  "~100 mile intervals" prompt well (6 sensible waypoints); the small local
  gemma4 ignored the interval count (2 stops) and got a state wrong. Prompt
  tweaks (US-only, non-blank city/state, restate the requested stop count) help
  marginally; small local models will still struggle.
- **First-match geocode selection is acceptable** — confirmed with the
  "Hilton Colorado Springs Airport" case (no exact property exists; nearest
  Hilton-brand result is fine). Not a defect; documented so it isn't re-litigated.
