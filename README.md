# SFU Bus Tracker

How reliable are the buses up Burnaby Mountain? This project records every TransLink bus in Metro Vancouver and scores the routes that serve SFU: **R5, 143, 144, and 145**.

**Live demo:** [http://16.174.148.60/](http://16.174.148.60/)

The demo is on early data. Collection started on 9 November 2026, and the site labels itself early until 10 service days are stored. Figures update as more days arrive.

## What it measures

A scheduled gap of 10 minutes or less is scored by extra wait and by how even the gaps are. A wider gap is scored by on-time arrivals: 1 minute early through 3 minutes late. On-time percent is hidden for the frequent hours.

A cancellation is a trip TransLink marked cancelled. A trip that was scheduled and never appeared is counted separately, and only after a full service day has been recorded.

Schedule and arrival data come from TransLink’s GTFS feeds. TransLink is not responsible for these scores.

## How it is put together

The collector polls TransLink all day and writes into TimescaleDB. It is the only process that calls TransLink, and it stays inside a daily request budget of 960. The API only reads that database. The site is a React app served beside the API.

```
TransLink GTFS  →  collector  →  TimescaleDB  →  API  →  site
```

```
backend/
  core/        GTFS time parsing and database migrations
  collector/   always-on poller
  api/         read-only scores
web/           Home, route detail, and About
demo/          static mockup on fake data, not wired to the API
```

Service days use `America/Vancouver`. Instants are stored in UTC. GTFS times can pass midnight (`25:15:00`); parsing goes through `GtfsTime`.

## Run it locally

Java 21, Docker, and Node are required. Copy `.env.example` to `.env` and add a TransLink API key before polling. Leave the key blank to load the schedule only.

```bash
docker compose up -d db

mvn -B -f backend/pom.xml test

mvn -B -f backend/pom.xml -pl collector -am package -DskipTests
java -Xmx512m -jar backend/collector/target/collector-0.1.0.jar

mvn -B -f backend/pom.xml -pl api spring-boot:run

cd web && npm install && npm run dev
```

The collector listens on port 8080, the API on port 8081, and the site at [http://127.0.0.1:5173](http://127.0.0.1:5173). The dev server proxies `/api` to the API. Run `spring-boot:run` from the `api` module only; the parent project has no main class.

One collector may poll at a time. A second copy spends the same API key’s daily quota.
