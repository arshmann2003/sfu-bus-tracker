# AGENTS.md

Guidance for AI agents (and humans) working on the SFU Bus Reliability Tracker.

## What this project is

Measures how reliable TransLink buses actually are. A collector service polls TransLink's
GTFS-Realtime feeds 24/7 and stores every bus in Metro Vancouver in TimescaleDB. Analysis and UI
focus on SFU routes first: **R5, 143, 144, 145** (route 135 no longer exists in the GTFS feed).

- `MINIPLAN.md` is the source of truth for scope, schema decisions, and metrics. Read it before
  designing anything new. `PLAN.md` is the older, larger plan; don't build from it.
- `demo/index.html` is a static mockup on fake data. It is not wired to the backend.

## Layout

```
backend/
  pom.xml          Maven parent (Java 21, Spring Boot 3.5)
  core/            Shared library: GtfsTime, Flyway migrations (db/migration/V*.sql)
  collector/       Always-on Spring Boot service: ScheduleLoader, FeedPoller, RealtimeStore
  api/             (planned) REST API + analysis; depends on core, read-only on collector tables
docker-compose.yml TimescaleDB (db) + collector
.env               Local secrets (gitignored). Template: .env.example
```

Java package root: `ca.sfu.bustracker` (collector code in `ca.sfu.bustracker.collector`).

## Commands

```bash
# Database only (Docker runs via Colima, not Docker Desktop)
colima start
docker compose up -d db
# "Cannot connect to the Docker daemon at .../.colima/...": colima stop && colima start

# Build and test everything (same as CI)
mvn -B -f backend/pom.xml test

# Test one module / one class
mvn -B -f backend/pom.xml -pl collector -am test
mvn -B -f backend/pom.xml -pl core test -Dtest=GtfsTimeTest

# Run the collector locally against the dockerized db
mvn -B -f backend/pom.xml -pl collector -am package -DskipTests
java -Xmx512m -jar backend/collector/target/collector-0.1.0.jar
curl localhost:8080/actuator/health

# Run everything in Docker
docker compose up -d --build
docker compose logs -f collector

# Inspect data
docker compose exec db psql -U bus -d bus
#   select count(*) from vehicle_position;
#   select * from api_request order by 1 desc limit 5;   -- daily request usage
```

Use `-pl <module> -am` when running a single module; without `-am` Maven can't find `core`.
`spring-boot:run` from the parent POM fails (no mainClass); run the packaged jar instead.

## Hard rules

- **TransLink quota: 1,000 requests/day per key.** The budget is 960 (`collector.daily-request-budget`),
  enforced atomically in the `api_request` table. Never add polling, retries, or new endpoints
  that bypass `RealtimeStore.tryConsumeRequest`. If you change intervals, recompute the daily total.
- **Migrations are append-only.** Never edit an existing `V*__*.sql`; add `V<n+1>__name.sql` in
  `backend/core/src/main/resources/db/migration/`. Only the collector runs Flyway.
- **TimescaleDB:** unique indexes/PKs on hypertables (`feed_fetch`, `vehicle_position`) must include
  the partition time column.
- **GTFS times** can exceed 24h (`25:15:00` = 1:15 AM on the next calendar day, same service day).
  Always go through `GtfsTime`; never parse them with `LocalTime`.
- **Time zone** is `America/Vancouver` for service days and schedules; store instants as UTC `timestamptz`.
- **Keep the collector small and boring.** It must stay up 24/7; lost hours are lost data forever.
  Analysis, endpoints, and anything experimental go in `api`, not `collector`.
- Distinguish **ghost** trips (scheduled, never appeared) from **cancelled** trips (feed said so).
- Frequent routes (headway ≤ 10 min) are scored by excess wait time and gap regularity;
  scheduled routes by on-time %. Don't report on-time % for frequent routes.

## Git

- Never commit `.env`, `data/`, or anything under `target/`.
- Commit messages: Conventional Commits (`feat(collector): ...`, `fix(db): ...`, `docs: ...`, `chore: ...`).
  One feature per commit.
- **Commits must be authored by Arshdeep Mann only.** Do not add `Co-authored-by` trailers.
  If one is added automatically, strip it before pushing.
- Ask before pushing, force-pushing, or rewriting history.

## Before you say "done"

1. `mvn -B -f backend/pom.xml test` passes.
2. New logic has a unit test (decoder and time parsing tests are the pattern to follow).
3. Schema changes come with a new migration, and the collector starts cleanly against a fresh db.
4. No change raises daily TransLink requests above 960.

---

## Using Cursor effectively on this project (for the human)

- **Plan mode first** for anything bigger than one file (new `api` module, metrics, deployment).
  Review the plan, then let the agent implement.
- **Give context with `@`:** `@MINIPLAN.md` for scope questions, `@V1__collect.sql` for any query
  or schema work, `@RealtimeStore.java` when touching ingestion.
- **Ask for one milestone at a time** (e.g. "add the api module skeleton with a /health endpoint
  and one test"), not "build week 2".
- **Make the agent verify:** end requests with "run the tests and show me the output".
- **Before pushing:** ask for a Bugbot review of your branch changes, and a security review when
  touching secrets, Docker, or deployment.
- **Good prompts for this repo:**
  - "Using `@V1__collect.sql`, write a query for median delay by hour for route 145 over the last 7 days."
  - "Check today's TransLink request usage and confirm we're on track to stay under 960."
  - "Add migration V2 for <change>; don't modify V1."
  - "Explain what `FeedPoller` does when TransLink returns 5xx three times in a row."
