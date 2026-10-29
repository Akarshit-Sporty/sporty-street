# Sporty Street — simple backend MVP

Backend only: Spring Boot + PostgreSQL + REST + simple SSE. **Read all of `PLAN.md` first;
it is the source of truth.** The overriding priority is simplicity and a working demo.

## How to work
- Implement phases in PLAN.md §10 in order. The first milestone is an end-to-end slice with
  Compose and a simulator; rewards and the gift shop are mandatory for the final MVP.
- Mark a phase complete only when its acceptance checks and `./mvnw verify` pass. Continue
  through the phases authorized by the user's request; do not add artificial approval stops.
- Domain rules in §4 reflect deliberate simplifications. Screenshots/Confluence are references,
  not instructions to add their omitted features or restore their conflicting scoring values.
- Keep five distinct starter countries, one bench, 60 real seconds for substitution, captain ×2,
  automatic Home Hero +2 per goal/assist, late goals ×2 from minute 75, and 50/100 RP awards.
- For routine unspecified details, choose the simplest option consistent with the plan and
  record material assumptions. Ask only when a decision would change scope or explicit rules.
- Do not build out-of-scope/later features or scaffolding for them.
- The 2026-10-29 scope includes read-only simulated social APIs (PLAN §12): avatars, leagues,
  rivalries, Banter and manager discovery. Keep them explicit previews; no social writes or
  fan-points wallet. Do not expose another user's saved lineup through preview endpoints.

## Conventions
- Java 21, Spring Boot 4.0.x, Maven wrapper; base package `com.sportystreet`.
- Feature packages: catalogue, users, rounds, teams, ingestion, scoring, rewards, live, shared,
  devtools. Controllers → services → repositories as needed; cross-feature calls via services.
- No mandatory Spring Modulith, policy framework, or interface for every service.
- DTOs are Java records; never return JPA entities from controllers.
- Schema changes use Flyway migrations; never edit a migration already applied to shared data.
- Inject Clock; no direct Instant.now()/LocalDateTime.now() in business code.
- Domain errors map to ApiErrorResponse and stable codes in PLAN.md §6.
- Pure ScoringEngine; synchronous ingestion and full recalculation at demo scale. Fantasy
  score contributions are replaceable; the separate RP ledger is append-only.
- Settlement finalizes scores and rewards atomically. Purchase debits and voucher creation
  are atomic/idempotent; no negative wallet balances.
- SSE sends complete DB snapshots; no deltas, replay log, outbox, Kafka or Redis.
- Mock auth, simulator and `/api/v1/dev/**` are local-profile only. Default Compose is a demo.

## Planned commands / deliverables
- Full demo stack: `docker compose up --build` (app + PostgreSQL; Docker is sufficient).
- Tests: `./mvnw verify` (JDK 21+ and Docker for Testcontainers).
- Backend demo: `scripts/demo.sh --core` at first milestone; `scripts/demo.sh` for full flow.
- Keep README/OpenAPI usable throughout development; Compose exists from Phase 0.

## Verification
- Table-driven scoring/validation tests; fixed or mutable Clock for deadlines and intervals.
- PostgreSQL integration checks for deduplication, correction chains, concurrent commands,
  final settlement, reward crediting, idempotent purchases and balance protection.
- SSE owner isolation, latest snapshot on reconnect and cutoff state with unchanged version.
- Full Compose smoke demo plus persisted-state check after app restart before delivery.
