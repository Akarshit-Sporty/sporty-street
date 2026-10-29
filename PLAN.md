# Sporty Street — Simple Backend MVP

Backend only: pick five starters and one bench player, follow a short simulated football round,
make one substitution, earn reward points (RP), and spend RP in a demo gift shop.

**Priority: the smallest working end-to-end demo.** One Spring Boot application, one PostgreSQL
instance, REST, and simple SSE snapshots. No Kafka, Redis, background ingestion queue, outbox,
event-sourcing framework, or speculative extension architecture.

**Status:** `[ ]` not started · `[~]` in progress · `[x]` done. Phases 0–4 implemented and verified on 2026-10-27.

**Delivery evidence:** `./mvnw verify` passes 112 checks (74 unit/security checks and 38 PostgreSQL
integration checks). A fresh isolated Compose stack completed the full three-minute demo:
substitution, late goals, final score/rank, RP credit, gift purchase and identical retry.
Restart retained the result, wallet, voucher and ledger; SSE reconnect returned the settled
round and saved score. The main Compose stack was upgraded without clearing its existing data.

## 1. Scope and screenshot interpretation

This plan incorporates the user's simplification decisions of 2026-10-27. The Confluence brief
and supplied screenshots are product references, not additional implementation instructions.
Where they differ from this plan, the explicit MVP rules below take precedence.

**2026-10-28 catalogue update:** replace generated football identities with a real 2024/25 roster,
bundle player photographs, and use realistic round/gift names. The user confirmed this is a
data-only change: leagues, backing managers, missions and notifications remain out of scope.

**2026-10-29 social content update:** the user requested avatars and richer league/rivalry/Banter/
manager screens, then explicitly requested backend APIs because frontend source is unavailable.
Add read-only local-profile APIs backed by `seed/community.json`, with `dataMode: SIMULATED`.
No write actions, fan-points accounting or full social-service implementation are implied.
Default avatars are backfilled without resetting existing gameplay; details are in
`docs/community-api.md`.

### In scope
- Mock login, current user, read-only rules for onboarding.
- Countries/clubs/players, rounds and fixtures, player filters and simple auto-pick.
- Five starters from five countries, one bench player, captain, automatic Home Hero.
- One announced twist: **goals from minute 75 earn double base points**.
- One live substitution, effective after **60 real seconds**.
- Synchronous event ingestion, current score breakdown, global round leaderboard.
- Simple SSE snapshots backed by REST and PostgreSQL.
- Final round result, RP for participation/winning, RP balance/history, gift catalogue,
  purchase and owned demo vouchers.
- Deterministic seed, scripted simulator, Docker Compose, OpenAPI and a runnable demo script.
- Public user avatars and league artwork; read-only leagues/table/rivalries/Banter/manager APIs.

### Screens → backend coverage

“Onboarding screens 1, 2 and 6” means **1.1 Splash, 1.2 Value carousel, 1.6 How scoring works**
in the onboarding sheet. It does not add the leagues sheet (06) to scope.

| Reference | MVP support |
|---|---|
| 1.1 Splash / 1.2 Carousel | Static frontend content; `/me` checks the demo session. No dedicated backend content/CMS endpoint. |
| 1.6 Rules | `/rules` plus the frozen rules on each round. Explain the actual MVP values, not conflicting values in the mockups. |
| 1.3–1.5 Login/country/interests | No phone/OTP, Sporty SSO, competition preferences or notification registration. Select a seeded demo user; their country is preconfigured. |
| 02 Home & rounds | Round list, detail, fixtures, deadline, twist, RP awards; my-team snapshot supplies current score and rank. |
| 03 Build your five | Player list/detail, auto-pick, lineup, captain, automatic Home Hero, confirmation. No credits, fixed formation or club quota. |
| 04 Live move | Own lineup/points, substitution state, global leaderboard and SSE. No push notifications or friends/national standings. |
| 05 Result & recap | Final score/rank, per-player/component breakdown, event timeline and RP awarded. No generated narrative, badges or hypothetical substitution comparisons. |
| 06 Leagues / 07 Back a manager | Read-only simulated league membership/table, rivalries, Banter and manager discovery. No join/rematch/reaction/backing writes or fan-points wallet. |
| 08 Rewards | Balance/history, gift shop, purchase, owned demo voucher/code. No missions, streaks, claim flow or partner integration. |

### Explicit MVP assumptions / departures from the references
- Five distinct starter countries, one bench player and a 60-second substitution delay are
  accepted assumptions. Formation is free; no player prices, salary cap or max players per club.
- Captain multiplier stays **×2**, not the screenshot's ×1.5. Flat scoring stays in place.
- Home Hero is automatic and repeatable (§4.4), not nominated/once per round. No wildcard.
- One twist, one global leaderboard, one RP currency separate from fantasy score.
- Gifts are persisted demo entitlements/codes; real fulfilment is outside this backend MVP.

### Out of scope
Frontend implementation; real identity/provider integration; OTP; payments; real vouchers;
league/invitation/rematch/backing write actions, share-card generation, missions, notifications;
chemistry, contracts, suspensions; position-specific scoring; multi-instance deployment.
Do not build supporting tables or frameworks for these features now.

## 2. Technical decisions and deliverable

| Topic | Decision |
|---|---|
| Runtime | Java 21, Spring Boot 4.0.x, Spring MVC, Maven wrapper |
| Storage | PostgreSQL 16, Spring Data JPA for users/round locking, explicit JdbcTemplate SQL for small gameplay projections and writes, Flyway migrations |
| Structure | One deployable application, packages by feature; no Spring Modulith dependency required |
| API | `/api/v1`, JSON camelCase, UUID IDs, UTC `Instant`, enums UPPER_SNAKE |
| DTOs | Java records; never return JPA entities |
| Time | Inject `java.time.Clock`; use provider `occurredAt` for scoring intervals |
| Tests | JUnit, AssertJ, Testcontainers PostgreSQL; select compatible dependency versions during scaffold |
| Docs | springdoc-openapi; `/swagger-ui.html` and `/v3/api-docs` |
| Live | Single-instance `SseEmitter`; periodic complete snapshots, no delivery history |
| Scoring | Synchronous transaction; full round recalculation on changed events at demo scale |
| Deployment | `docker compose up --build`: `app` + `postgres`, API on port 8080 |

Compose is part of the **first milestone**, not a final packaging task:
- Multi-stage Dockerfile builds with the Maven wrapper; host only needs Docker/Compose.
- App binds `0.0.0.0:8080`; DB uses a named volume and is private to the Compose network.
  Optional local-Java development override exposes DB on `127.0.0.1` only.
- DB healthcheck, app dependency on healthy DB, Flyway on boot, application health endpoint.
- Default Compose runs the documented `local` demo profile with seed/mock auth/simulator.
  It is a deployable demo, not a production authentication setup.
- `.env.example` documents DB configuration and demo provider token; no external service needed.
- Restart retains rounds, scores, RP and purchases. Reset is explicit and local-only.

## 3. Package layout

```
com.sportystreet
├── catalogue/   countries, clubs, players
├── users/       demo users, current user
├── rounds/      lifecycle, frozen rules, results orchestration
├── teams/       validation, auto-pick, lineup and substitution intervals
├── ingestion/   synchronous event validation and storage
├── scoring/     pure ScoringEngine, current contributions, totals, ranking
├── rewards/     RP wallet/ledger, gifts, purchases
├── live/        periodic SSE snapshots
├── community/   read-only simulated social screen data
├── shared/      errors, auth, clock, idempotency
└── devtools/    local seed, reset, simulator, login and round controls
```

Use controller → service → repository where needed, plus DTOs/entities and a pure scoring
function. Do not create interface/implementation pairs or policy/plugin frameworks by default.
Cross-feature work uses services/DTOs, not another feature's repositories/entities. Keep
transaction orchestration explicit; lifecycle orchestration calls scoring/rewards services,
which must not call back into lifecycle orchestration.

## 4. MVP domain rules

### 4.1 Round lifecycle and finality
- `DRAFT → OPEN → LIVE → SETTLED`. One fixture belongs to only one round in this MVP;
  a player appears in at most one fixture per round.
- On opening, copy rules v1 and RP award amounts into `round.rules` JSON. They become immutable
  for that round; no rules-version table or administration API.
- `OPEN`: create/edit teams while `now < entryDeadline`.
- `LIVE`: lock lineup and accept events; start automatically at the deadline. Commands enforce
  the deadline even if the scheduled transition is late.
- Local `/start` may bring the deadline forward to now while still OPEN; update the single
  substitution window and starter interval start consistently in one transaction.
- One substitution window `[subWindowOpensAt, subWindowClosesAt)`, default covering live play.
- `SETTLED` is final: freeze scores/ranks and award RP atomically. No reopening, rescoring,
  reward reversals or negative reward adjustments in the MVP.
- Settlement is an explicit local operation after the simulator has finished (or the manual
  demo operator has finished submitting events). Reject it while the simulator is running or
  a substitution is still pending. No heuristic “all matches probably ended” scheduler.
- Serialize settlement, ingestion and substitutions with a PostgreSQL round-row lock. An event
  committed before settlement counts; a new event/correction after settlement is rejected with
  `ROUND_SETTLED`. An exact retry of an already accepted event still returns its original result.
- Repeated settlement returns the saved result and never grants RP again. A round with no teams
  can settle but issues no awards.

### 4.2 Team and auto-pick
- Exactly **5 starters + 1 bench**, six distinct eligible players.
- Five active starters must represent five distinct national-team countries. Bench may share
  a starter's country; revalidate the five countries when substituting.
- Any positions, captain must be a starter, one team per user per round.
- Team owner comes only from authentication. Team reads/edits/breakdown/results are owner-only;
  the public-to-authenticated leaderboard exposes display names and scores, not private data.
- Pre-deadline lineup edits replace selections; require `expectedVersion`.
- Auto-pick is deterministic, selects one player per country, then a distinct bench player and
  a captain. `preferHomeHero=true` prioritizes a home-country starter when feasible.
  No preferred-player constraint solver in the MVP. Impossible pool → `AUTO_PICK_IMPOSSIBLE`.
- Copy the user's country to `team.home_country_id` at creation. No profile editing in the MVP.
- At LIVE, starters score from the entry deadline. Score starts at zero, including before any
  events exist; all entered teams appear on the leaderboard.

### 4.3 Flat points and late-goal twist

| Event | Base points |
|---|---|
| Appearance (once per player per fixture) | +1 |
| Goal | +5 |
| Assist | +3 |
| Yellow card | −1 |
| Red card | −3 |
| Own goal | 0 |

- Reject unknown event types. No clean sheets, saves or position-based scoring.
- Event counts only if its player has a STARTER interval at `occurredAt`:
  `effectiveFrom <= occurredAt < effectiveTo` (null end means infinity). Bench never scores.
- Twist `LATE_GOALS_X2`: GOAL with integer `matchMinute >= 75` doubles base goal points.
  Minute 74 is normal; minute 75 and stoppage time qualify. No wall-clock twist scheduler.
- `matchMinute` is elapsed match minute (e.g. 90+3 → 93), not a display string. Both it and
  `occurredAt` are provider inputs; minute chooses the twist, occurrence time chooses the lineup.
- Captain multiplies base points after the twist by **2**, including negative points.
  Once the captain leaves the active lineup, captain scoring stops; no captain reassignment.
- Formula: `base × lateGoalMultiplier × captainMultiplier + homeHeroBonus`.
- Examples: ordinary goal 5; late goal 10; captain late goal 20; home captain late goal **22**.
  A home captain normal goal is **12**. An assist never receives the late-goal multiplier.

### 4.4 Simplified Home Hero
- An active starter whose country matches the team's snapshotted home country automatically
  receives **+2 for each GOAL or ASSIST**. No appearance bonus.
- Neither captain nor late-goal multiplier multiplies this bonus.
- No nomination, first-event bookkeeping, once-per-round cap, wildcard or separate claim.
  A user with no country, or no active home-country starter, gets no Home Hero bonus.
- The five-country rule allows at most one active Home Hero. The bonus follows the same
  substitution intervals as base points.
- This deliberately replaces both the original plan's appearance/goal/assist bonuses and the
  brief's nominated one-off bonus in favor of a stateless rule.

Frozen round rules v1 (the `/rules` endpoint returns the same defaults):

```json
{
  "version": 1,
  "starterCount": 5,
  "benchCount": 1,
  "distinctStarterCountries": true,
  "base": { "APPEARANCE": 1, "GOAL": 5, "ASSIST": 3, "YELLOW_CARD": -1, "RED_CARD": -3, "OWN_GOAL": 0 },
  "captainMultiplier": 2,
  "homeHero": { "GOAL": 2, "ASSIST": 2, "repeatable": true, "multiplied": false },
  "twist": { "type": "LATE_GOALS_X2", "fromMatchMinute": 75, "multiplier": 2 },
  "subActivationDelaySeconds": 60,
  "rewards": { "participationRp": 50, "winnerBonusRp": 100 }
}
```

### 4.5 Scoring implementation and corrections
- One pure `ScoringEngine` computes contributions from latest non-voided events, selection
  intervals and frozen rules. Simple functions are sufficient; no `ScoringPolicy` hierarchy.
- Components: `BASE`, `LATE_GOAL_BONUS`, `CAPTAIN_BONUS`, `HOME_HERO_BONUS`, with explanation.
  For a late home captain goal: 5 + 5 + 10 + 2 = 22. Components sum to the total.
- Store original event revisions for deduplication, but **rebuild current score contributions
  and team totals for the round** after each effective event change. Replace derived rows within
  the transaction. This is not an append-only fantasy-score ledger and needs no reversal rows.
- Latest revision per `(provider, providerEventId)` wins. A voided latest revision contributes
  nothing. Older revisions are stored but do not trigger scoring or a version bump.
- Corrections can change player/type/minute/time or void the event, but cannot move it to another
  fixture. Rebuilding all teams also removes points from the old player/team correctly.
- APPEARANCE contributes at most once per player/fixture even if supplied under different event
  IDs: choose the earliest current non-voided appearance, breaking ties by provider/event ID,
  before evaluating team intervals. Other distinct event IDs represent distinct events.
- Global rank is `RANK()` by score descending; ties share rank. Order tied entries by team ID for
  stable paging. At settlement persist final ranks in results.
- Scope is tens of demo teams and hundreds of events, not production-scale ingestion.

### 4.6 Substitution and versions
- One substitution per team per round, only LIVE and inside the single window.
- Outgoing must be an active starter; incoming must be the bench player and remain eligible.
- Check `expectedVersion` under the round lock, then validate the resulting five countries.
- `cutoff = acceptedAt + 60 seconds`; consume the substitution immediately on acceptance.
  No cancellation or second request with a new idempotency key.
- Close outgoing STARTER/incoming BENCH intervals at cutoff; open outgoing BENCH/incoming
  STARTER intervals at cutoff. Do not overwrite player IDs in existing intervals.
- Before cutoff API shows the old active five and `pendingSubstitution { outgoingPlayerId,
  incomingPlayerId, effectiveAt }`. At/after cutoff it shows the new five and null pending state.
  Reads derive active roles from Clock; no activation job is needed.
- Late events before cutoff still score the outgoing player; at cutoff only incoming scores.
- Team `version` changes for lineup/substitution commands, not score updates. Round
  `stateVersion` changes for committed round/team/scoring mutations and settlement. Time-derived
  pending state may change without a version bump; SSE clients use `asOf` too (§8).

### 4.7 Demo auth and idempotency
- Local-only seeded usernames via `X-Dev-User` or HttpOnly `dev_user` cookie set by
  `POST /dev/login?username=alice`. Unknown/missing user → 401.
- Mock auth and all `/api/v1/dev/**` endpoints exist only under `local`.
  Outside `local`, deny application API access until real auth is implemented.
- `/internal/**` requires configured `X-Provider-Token`; provider in the body must match that
  token's provider. Health is public; local login is public; business APIs need authentication.
- Prefer same-origin frontend `/api` proxy, including SSE/cookies. Document an explicit optional
  frontend origin allowlist if direct cross-origin access is needed.
- `Idempotency-Key` required for create-team, substitution and gift purchase. Bind to user,
  method, canonical path including IDs, and canonical request body. Same request/key returns
  the saved successful status/body; changed body → `IDEMPOTENCY_KEY_REUSED`.
- Claim key, business writes and saved response commit atomically. Concurrent same-key requests
  serialize and return one result. Rollback does not leave a successful idempotency record.
- Provider deduplication and settlement use their own unique event/result keys.

### 4.8 Rewards and shop
- Fantasy score determines rank; **RP is a separate integer balance**, not a score conversion.
- On settlement each entered team earns **50 RP for participation**. Every team ranked 1 earns
  an additional **100 RP** (150 total). Tied winners all get the full bonus, including a solo
  entrant; zero/negative fantasy score does not disqualify participation. No percentile tiers.
- Values are MVP defaults frozen in round rules and shown before entry. No live RP awards,
  manual claims, missions or third fan-points currency.
- Settlement transaction stores each team's result, credits its owner's wallet, and inserts
  reward-ledger entries once. Unique `(user_id, round_id, reason)` prevents duplicate awards.
- Shop has fixed positive integer RP prices. Purchase one gift at a time; no cart, stock,
  delivery address or fulfilment service. Inactive gift → `GIFT_UNAVAILABLE`.
- Lock the buyer's wallet; check balance; deduct RP, create purchase and debit-ledger entry,
  save idempotent response in **one transaction**. Insufficient balance → `INSUFFICIENT_RP`.
- Store price/name snapshots on purchase and a unique `SST-...` voucher code. Codes represent
  demo gifts only; no real partner validation, expiry, refunds or cash redemption.
- Balance must stay nonnegative and equal the sum of the RP ledger. Wallet starts at zero.
  Ledger is append-only; result and purchase history survive restarts.
- To prevent deadlocks: lock round first where applicable, then wallets sorted by user ID.
  Purchase locks only its wallet and never tries to lock a round.

## 5. Minimal data model (Flyway)

| Table | Important fields / constraints |
|---|---|
| `country`, `club`, `player` | UUIDs; unique country code; player country/club, position, image URL, optional seeded display stats |
| `app_user` | username unique, display_name, country_id nullable, avatar_url nullable (V5) |
| `round` | name, status, entry_deadline, sub_window_opens_at/closes_at, rules jsonb, state_version, settled_at |
| `fixture` | round_id, provider, provider_fixture_id unique per provider, home/away club, kickoff_at |
| `round_player` | round_id, player_id, fixture_id, eligible; unique(round_id, player_id) |
| `team` | user_id, round_id, captain_player_id, home_country_id, subs_used, version; unique(user_id, round_id) |
| `team_selection` | team_id, player_id, role, effective_from/to |
| `team_substitution` | team_id unique, outgoing_player_id, incoming_player_id, accepted_at, effective_at |
| `match_event` | provider, provider_event_id, revision, fixture_id, player_id, event_type, match_minute, occurred_at, voided, request_hash, received_at, acceptance_response; unique(provider, provider_event_id, revision) |
| `score_contribution` | round_id, team_id, player_id, match_event_id, component, points, explanation; unique(team_id, match_event_id, component) |
| `team_score` | team_id PK, round_id, total; initialized to zero on team creation |
| `round_result` | team_id PK, round_id, final_score, final_rank, participation_rp, winner_rp, settled_at |
| `reward_wallet` | user_id PK, balance; check(balance >= 0) |
| `reward_ledger` | user_id, delta, reason, round_id nullable, purchase_id nullable, created_at; unique(user_id, round_id, reason) for awards; unique(purchase_id) for debits |
| `gift` | name, description, category, image_url, price_rp > 0, active |
| `gift_purchase` | user_id, gift_id, gift_name, price_rp, voucher_code unique, created_at |
| `idempotency_record` | user_id, key, method_path, request_hash, response_status/body; unique(user_id, key, method_path) |

Use foreign keys and indexes for fixture/round events, team selections, round totals,
user reward history and purchases. Validate fixture/player/round membership at ingestion.
No club-membership history, scoring-rules table, ingestion jobs, outbox or SSE event tables.

## 6. API contract (`/api/v1`)

All business endpoints are authenticated. Paginated responses use `items, page, size, total`;
cap page size at 100. OpenAPI includes concrete request/response examples from the first milestone.

| Method | Path | Contract |
|---|---|---|
| GET | `/me` | id, username, displayName, countryId, avatarUrl |
| GET | `/rules` | Current MVP default rules for onboarding; round rules remain authoritative |
| GET | `/rounds?status=` | OPEN/LIVE by default, optionally SETTLED; includes twist and reward amounts |
| GET | `/rounds/{roundId}` | Fixtures, status, deadline, window, serverTime, frozen rules, stateVersion |
| GET | `/rounds/{roundId}/players` | Paged; filters position/countryId/name; player/club/country, seeded stats, eligible, isHomeCountry |
| GET | `/rounds/{roundId}/players/{playerId}` | Player detail and eligible fixture |
| POST | `/rounds/{roundId}/auto-pick` | `{ preferHomeHero: true }` → starters, bench, captainPlayerId, explanation; never saves |
| POST | `/rounds/{roundId}/teams` | `{ starterPlayerIds[5], benchPlayerId, captainPlayerId }`; Idempotency-Key; 201 |
| GET | `/rounds/{roundId}/my-team` | TeamSnapshot; 404 until created |
| PUT | `/teams/{teamId}/lineup` | Same lineup fields + expectedVersion; before deadline |
| POST | `/teams/{teamId}/substitutions` | `{ outgoingPlayerId, incomingPlayerId, expectedVersion }`; Idempotency-Key; TeamSnapshot |
| GET | `/teams/{teamId}/score-breakdown` | Total, players/components and current scoring-event timeline with occurredAt/matchMinute/fixtureId |
| GET | `/rounds/{roundId}/leaderboard` | Paged global ranks; stateVersion, entries with teamId/displayName/rank/score |
| GET | `/rounds/{roundId}/result` | Own final score/rank, breakdown, participationRp, winnerRp, totalAwardedRp; `ROUND_NOT_SETTLED` until final |
| GET | `/rounds/{roundId}/stream` | SSE round snapshot, own team and top 100 standings |
| GET | `/rewards/wallet` | Own balanceRp |
| GET | `/rewards/history` | Own paged RP entries: delta, reason, roundId/purchaseId, createdAt |
| GET | `/gifts?category=` | Paged active gifts with priceRp and demo description |
| POST | `/gifts/{giftId}/purchases` | Empty body; Idempotency-Key; 201 with purchase, voucher code and balance after purchase |
| GET | `/me/purchases` | Own paged gifts/voucher codes/paid RP |

`TeamSnapshot`: id, roundId, active starters (player details + current points), bench,
captainPlayerId, homeCountryId, homeHeroPlayerId, pendingSubstitution, subsUsed/subsRemaining,
score, rank, version, stateVersion, asOf. Outgoing players' earned points remain in the total
and breakdown after they move to bench. Before LIVE, return the configured initial lineup.

Internal/local endpoints:

| Method | Path | Contract |
|---|---|---|
| POST | `/internal/match-events` | Provider token; synchronous 200 `{ eventId, applied, stateVersion }`; exact duplicate returns saved acceptance |
| POST | `/dev/login?username=` | Local demo cookie + current user |
| POST | `/dev/rounds/{roundId}/open` / `/start` / `/settle` | Local lifecycle controls; settle returns saved settlement summary |
| POST | `/dev/simulator/start` | `{ roundId }`; start/resume scripted demo; repeated start while running returns existing status |
| POST | `/dev/simulator/stop` | Stop delivery without settling; already stopped is a successful no-op |
| GET | `/dev/simulator` | roundId, running, completed, currentMatchMinute, lastError |
| POST | `/dev/reset` | Stop simulator, close SSE emitters, clear demo state and reseed |

`MatchEventMessage`: provider, providerEventId, revision (positive integer), fixtureId,
playerId, eventType, matchMinute (nonnegative integer), occurredAt, voided.
`ApiErrorResponse`: code, message, fieldErrors[{field, message}], traceId.

| HTTP | Stable codes |
|---|---|
| 400 | VALIDATION_FAILED (includes missing Idempotency-Key) |
| 500 | INTERNAL_ERROR (opaque message; traceId links to server log) |
| 401 / 403 / 404 | UNAUTHENTICATED / FORBIDDEN / NOT_FOUND |
| 409 | TEAM_ALREADY_EXISTS, VERSION_CONFLICT, EVENT_REVISION_CONFLICT, ROUND_BUSY |
| 422 | ROUND_NOT_OPEN, ROUND_NOT_LIVE, ROUND_LOCKED, ROUND_SETTLED, ROUND_NOT_SETTLED |
| 422 | WRONG_SQUAD_SIZE, DUPLICATE_PLAYER, DUPLICATE_COUNTRY, CAPTAIN_NOT_STARTER, PLAYER_INELIGIBLE |
| 422 | SUBSTITUTION_USED, SUBSTITUTION_WINDOW_CLOSED, INVALID_SUBSTITUTION, AUTO_PICK_IMPOSSIBLE |
| 422 | IDEMPOTENCY_KEY_REUSED, UNKNOWN_FIXTURE, UNKNOWN_PLAYER, INVALID_EVENT |
| 422 | INSUFFICIENT_RP, GIFT_UNAVAILABLE |

## 7. Synchronous processing and recovery

```
Provider / simulator → authenticate and validate → lock fixture's round
  one DB transaction:
    existing event revision? return original result, or reject conflicting payload
    require LIVE; validate membership and occurredAt within [entryDeadline, now]
    insert event revision
    if latest effective revision changed:
      rebuild round's current score contributions from latest revisions + intervals
      recompute all team totals; increment stateVersion
    save acceptance response and commit
  → HTTP 200 only after commit
```

- Same provider/event/revision with a different payload → `EVENT_REVISION_CONFLICT`.
- Event identity is provider-scoped; do not mix two providers' matching event IDs. All revisions
  of an identity stay with its original fixture. Reject fixture changes before taking locks
  that could span multiple rounds.
- A transaction failure rolls back all writes and returns an error. Sender retries the same ID
  and revision. No 202, worker states, retry queue, replay endpoint or stale-job recovery.
- Correction chains and out-of-order deliveries use the same full recalculation, not incremental
  reversals. No score changes after settlement.
- Lineup edits, team creation, round transitions and ingestion share the round lock. Enforce
  unique constraints as the last line of defense against concurrent duplicate operations.
- Server restart needs no score replay: committed events, projections and rewards are durable;
  rolled-back work can be retried. SSE reloads the current state.

## 8. Simple SSE

- One endpoint per round; authenticate subscriber and authorize own-team fields.
- Immediately send `ROUND_SNAPSHOT`; then every **2 seconds** send a complete current snapshot:
  round state/rules/window, own TeamSnapshot or null, global top-100 standings. Read these in
  one consistent DB snapshot so scores, rank and stateVersion agree.
- Envelope: `{ eventType, roundId, stateVersion, asOf, payload }`. Send full snapshots even if
  version is unchanged: the 60-second pending substitution can expire without a database write.
- No SSE `id`, Last-Event-ID replay, outbox or persisted stream events. On reconnect immediately
  send latest snapshot. Intermediate notifications may be skipped; current state is sufficient.
- Clients replace state, never add score deltas. For equal stateVersion use newer asOf; discard
  older versions/timestamps. Serialize writes per emitter so packets do not race.
- On SETTLED the snapshot includes own result/RP award; frontend refreshes wallet through REST.
  Shop purchases use REST responses; no separate rewards stream.
- Heartbeat comment every 20 seconds; emitter timeout 30 minutes; clean up disconnected clients.
- REST provides the same current truth and may be polled every 2 seconds as a fallback.
- SSE runs on the same instance; restart disconnects clients and browser reconnect restores state.

## 9. Seed, simulator and demo script

### Seed
- 21 countries, 6 real clubs and 60 real players from a fixed 2024/25 roster in
  `seed/football-2024-25.json`. Real national teams and positions; club memberships are historical,
  not a live transfer feed. Three simulated fixtures in European Superstars (OPEN), with
  Champions Night DRAFT. Eligible pool permits five starter countries plus a distinct bench.
- Player portraits are bundled in the app image and served publicly at `/media/players/**`.
  `imageUrl` is relative to the API origin; Commons authors/licences are in `credits.json`.
  No runtime image download. Existing gameplay is never silently rewritten; reset is explicit.
- Eight mock users: alice–frank with countries in the pool; grace/henry without a country.
- Deadline now + 10 minutes on reset; `/start` makes the round LIVE immediately for demo.
- Zero RP wallets. Seed valid rival teams for bob/carol; alice creates her own via the demo API.
- Three active demo gifts priced **50, 100, 150 RP**. Participation alone buys the cheapest gift;
  winning is not required for the purchase demo to succeed.
- Display names: Matchday Coffee, Football Store Discount, Club Scarf; categories FOOD,
  SHOPPING, MERCH. Purchase fulfilment remains simulated and is explained in README.
- Seed only when empty under local profile. IDs and catalogue content deterministic; reset
  clears purchases, RP, events, teams and simulator state explicitly.

### Simulator
- A scripted fixture timeline, not random gameplay; default 1 match minute per 2 real seconds
  (about 3 minutes for 90 minutes). `occurredAt = liveStart + matchMinute × 2 seconds` for the
  scripted timeline; wait until that wall time to deliver. Match minute stays football time.
- Substitution delay remains **60 real seconds**. The demo script submits it early (around
  minute 10) so it activates before late goals. Include events just before/at the actual cutoff
  to demonstrate correct player scoring; these event timestamps must not be in the future.
- Emit appearances, normal goal/assist/cards, goals at minutes **74 and 75**, a goal for the
  incoming player after cutoff, one duplicate, one delayed event and a void/correction chain.
- Select event players from the deterministic demo squads so the demo visibly exercises Home
  Hero, captain and substitution. Include a tied-winner case as a separate test fixture.
- Submit through the same ingestion service/validation as the internal endpoint. Reuse stable
  event IDs/revisions on retry. Status exposes errors; never silently skip failed events.
- Persist the live start on the round (entryDeadline). On process restart simulator is stopped;
  explicit start resumes against that timeline, sending overdue scripted events with their
  original occurrence times. Derive any cutoff-specific events from saved substitution state.
  Exact duplicates are harmless. Extend the demo substitution window enough for a restart.
- Stop after all deliveries/corrections succeed; `completed=true` only then. Settlement is an
  explicit final step in the demo script. Do not start another simulation for a settled round.

### Runnable backend-only demonstration (`scripts/demo.sh`)
1. `docker compose up --build`, wait for health; optionally reset explicitly.
2. Log in as alice; call `/me` and `/rules` (onboarding screens 1.1/1.2/1.6).
3. List rounds, inspect Round 1, auto-pick and save a team with an idempotency key.
4. Open SSE with `curl -N`, start round and simulator; observe points and standings.
5. Submit one valid substitution early; show pending state then activation after 60 seconds.
6. Observe late-goal ×2, captain/Home Hero components and a corrected event.
7. Wait for simulator completion; settle; fetch final rank, breakdown and 50/150 awarded RP.
8. Read wallet/catalogue; buy the 50-RP gift; retry the same purchase key and verify one debit,
   one purchase and the same code; list owned vouchers and reward history.
9. Restart app and verify saved result, RP balance and purchase remain available.

The script uses real REST operations, seeded IDs discovered through APIs and clear assertions;
no frontend, external sports feed, partner service or host Java installation is required.

## 10. Implementation phases, priorities and estimates

Estimates are rough hands-on development hours for one backend developer, not a deadline or
promise. They include focused tests and assume Docker works and there are no real integrations.
Allow another **20–30% buffer** for dependency/environment and frontend-contract integration.
Build in order; complete each phase's checks with `./mvnw verify`.

### [x] Phase 0 — Runnable foundation (P0, 3–5 hours)
- Maven/Spring scaffold, Clock, Flyway, error format, local mock/provider auth.
- Dockerfile, Compose app + Postgres healthchecks, initial seed, OpenAPI and README startup.
- Idempotency transaction helper with test; no generic infrastructure beyond what endpoints need.

**Done when:** clean `docker compose up --build` starts without host JDK; `/me`, `/rules`, health
and OpenAPI work; missing auth fails; duplicate/idempotency-conflict behavior is tested; app
restart keeps seed data without duplication.

### [x] Phase 1 — First playable vertical slice (P0, 7–11 hours)
- Catalogue, rounds, team creation/read/auto-pick and initial score rows.
- LIVE transition, synchronous ingestion, simple scoring including Home Hero/late goals,
  current contributions, leaderboard and REST breakdown.
- Basic scripted simulator and periodic SSE, runnable `scripts/demo.sh --core`.
- Implement duplicate delivery now; correction support is completed in Phase 2.

**First milestone verification (2026-10-27):** 85 automated checks passed; Compose core demo
passed; team/score/version persisted across app restart; SSE reconnected with the saved score.
The completed MVP expands this coverage to 112 checks, including substitutions, final settlement,
the real catalogue's public image URLs and read-only social content.

**First milestone:** Compose → mock login/rules → pick squad → start round/simulator → changing
scores and leaderboard visible through REST/SSE. It is deliberately not the full final demo.
**Done when:** this sequence works from a clean DB; unit checks cover scoring components and
74/75 boundary; integration tests cover valid/invalid squads, event deduplication and SSE own-user
isolation. The simulator can be run without substitution until Phase 2.

### [x] Phase 2 — Complete round gameplay (P0, 4–7 hours)
- Pre-lock lineup edits, one delayed substitution and explicit pending response.
- Full-rebuild corrections/voids/older revisions; deterministic timeline and score breakdown.
- Deadline/window enforcement, immutable settlement and saved result (award hook in Phase 3).

**Done when:** fixed-Clock tests cover before/at cutoff, no captain transfer and Home Hero movement;
concurrent stale-version updates fail; revisions 1→2→3/void and delayed older revisions produce
correct totals without duplicates; corrections changing player remove old points; repeated
appearance does not double-score; settlement serializes with ingestion and rejects later changes.

### [x] Phase 3 — Rewards and gift shop (P0, 4–7 hours)
- Wallet/ledger, frozen 50 participation + 100 winner awards, atomic settlement with results.
- Gift catalogue, atomic/idempotent purchase, owned vouchers and reward history.
- Finish result API and full demo script through purchase and restart.

**Done when:** completion/solo win/tied winners award correct RP; retry/concurrent settlement
cannot double-credit; injected settlement failure rolls back both results and RP; concurrent
purchases cannot overspend; identical purchase retry returns one voucher/debit; insufficient RP
fails cleanly; another user cannot read purchases; wallet equals ledger sum.

### [x] Phase 4 — Demo delivery and integration (P0, 2–4 hours)
- Complete OpenAPI examples, frontend REST/SSE contract, same-origin proxy instructions.
- README with startup, mock login, simulator, reset, saved-volume behavior, LAN sharing and
  optional local-Java workflow. Keep Postgres private in default Compose.
- Run full clean-start demo and restart/reconnect check. A Postman collection is optional P1;
  OpenAPI + curl script are required and sufficient.

**Final acceptance:** fresh clone + Docker → `docker compose up --build` → complete §9 scenario
including pending substitution, late goal, final result, RP credit and a gift purchase. All data
survives app restart; SSE reconnect returns current state. `./mvnw verify` passes.

**Estimate:** first milestone (Phases 0–1) **10–16 hours**; complete backend MVP **20–34 hours**,
plus buffer. If time is constrained, reduce optional packaging polish first; rewards/shop,
correct scoring, and Compose remain required for the final outcome.

## 11. Later — not part of this build

Real auth/sports providers and partner fulfilment; nominated one-off Home Hero/wildcard;
additional twists, formation/budget rules; leagues/sharing/rematches; missions/streaks/alerts;
manager backing transactions; production rewards economics/limits; scaling ingestion/SSE across instances.
Add queues, durable delivery, incremental scoring or frameworks only when actual scale or
reliability requirements demand them. No preparatory tables/policies in the MVP.

## 12. Read-only social content API (2026-10-29)

- `/leagues?scope=MINE|DISCOVER|ALL`, `/leagues/{id}`, `/leagues/{id}/table`.
- `/leagues/{id}/rivalries`: viewer-relative wins/losses/form and incoming/outgoing challenges.
- `/leagues/{id}/banter`: paginated messages, avatars, reaction counts and quick-message presets.
- `/backing/managers?roundId=...&filter=ALL|FRIENDS|TOP_FORM|CREATORS`: candidate cards and
  generated squad previews. Never expose another user's saved lineup.
- Six leagues; Alice has four Lagos rivalries, three challenges, eight Lagos messages, twelve
  quick-message presets and fourteen manager candidates across the four filters.
- Eight real demo-user avatars plus four creator avatars and six league images, bundled with
  Commons attribution. User/login/leaderboard/SSE responses carry avatarUrl; static GET/HEAD is public.
- These reads do not write game teams/results/wallets. Community-only profile IDs are not login
  accounts; no extra users or wallets are created. Stats/memberships/friends are fixed fixtures.
- The backend APIs and assets are delivered here; frontend wiring must be done in its own repository.
