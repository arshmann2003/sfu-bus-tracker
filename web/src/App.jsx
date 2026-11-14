import { useEffect, useState } from "react";
import { Link, NavLink, Route, Routes, useParams, useSearchParams } from "react-router-dom";

function minutes(seconds) {
  if (seconds == null || Number.isNaN(seconds)) return null;
  const sign = seconds > 0 ? "+" : "";
  return `${sign}${(seconds / 60).toFixed(1)} min`;
}

function pct(value) {
  if (value == null) return null;
  return `${Math.round(value)}%`;
}

function age(seconds) {
  if (seconds == null || seconds < 0) return "no polls yet";
  if (seconds < 90) return `${seconds}s ago`;
  const minutesAgo = Math.round(seconds / 60);
  return `${minutesAgo} min ago`;
}

function Spinner({ label }) {
  return (
    <span className="spinner" role="status">
      <span className="spin" aria-hidden="true" />
      {label}
    </span>
  );
}

function Shell({ children }) {
  return (
    <>
      <header>
        <div className="topbar">
          <Link to="/"><strong>SFU Bus Tracker</strong></Link>
          <nav>
            <NavLink to="/" end>Home</NavLink>
            <NavLink to="/about">About</NavLink>
          </nav>
        </div>
      </header>
      <main><div className="wrap">{children}</div></main>
    </>
  );
}

function useJson(url) {
  const [state, setState] = useState({ data: null, error: null });
  useEffect(() => {
    let cancelled = false;
    setState({ data: null, error: null });
    fetch(url)
      .then((response) => {
        if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
        return response.json();
      })
      .then((data) => { if (!cancelled) setState({ data, error: null }); })
      .catch((error) => { if (!cancelled) setState({ data: null, error: error.message }); });
    return () => { cancelled = true; };
  }, [url]);
  return state;
}

function Home() {
  const routes = useJson("/api/routes?focus=sfu");
  const status = useJson("/api/status");
  const cards = routes.data ?? [];
  const observed = status.data?.observedArrivals ?? 0;
  const days = status.data?.daysCollected ?? 0;
  return (
    <Shell>
      <h1>
        How reliable is your bus up Burnaby Mountain?
        {status.data?.earlyData && <span className="badge">early data</span>}
      </h1>
      <p className="sub">
        {status.data == null && !status.error
          ? <Spinner label="Counting completed stops…" />
          : <>Based on {observed.toLocaleString()} completed stop arrivals over {days} day{days === 1 ? "" : "s"}. </>}
        A scheduled gap of 10 minutes or less is scored by extra wait. A wider gap is scored by on-time arrivals.
      </p>
      {status.error && <p className="error">Could not load the arrival count: {status.error}</p>}
      {routes.error && <p className="error">Could not load routes: {routes.error}</p>}
      {routes.data == null && !routes.error && (
        <p className="loading"><Spinner label="Loading routes…" /></p>
      )}
      <div className="cards">
        {cards.map((route) => <RouteCard key={route.id} route={route} />)}
      </div>
      {status.data && (
        <p className="status">
          <i className={status.data.feedAgeSeconds >= 0 && status.data.feedAgeSeconds < 300 ? "dot" : "dot stale"} />
          Data last updated {age(status.data.feedAgeSeconds)}
          {status.data.coveragePct != null && ` · ${status.data.coveragePct}% of polls succeeded in the last hour`}
        </p>
      )}
    </Shell>
  );
}

function RouteCard({ route }) {
  const headline = route.frequent
    ? minutes(route.excessWaitSec) ?? "Not enough gaps yet"
    : pct(route.onTimePct) ?? "No completed arrivals yet";
  const label = route.frequent ? "extra wait" : "on time";
  return (
    <Link className="card" to={`/routes/${route.id}`}>
      <span className={route.frequent ? "chip frequent" : "chip"}>{route.id}</span>
      <div className="big">{headline}</div>
      <div className="muted">{label}</div>
      {route.frequent && route.regularityPct != null && (
        <div className="kv"><b>{pct(route.regularityPct)}</b> evenly spaced</div>
      )}
      {!route.frequent && route.avgDelaySec != null && (
        <div className="kv">avg delay <b>{minutes(route.avgDelaySec)}</b></div>
      )}
      <div className="kv"><b>{route.cancelled}</b> cancelled · <b>{route.bunching}</b> bunched</div>
    </Link>
  );
}

function RoutePage() {
  const { id } = useParams();
  const [params, setParams] = useSearchParams();
  const direction = params.get("direction");
  const query = direction == null ? "" : `?direction=${direction}`;
  const report = useJson(`/api/routes/${id}${query}`);
  const data = report.data;
  if (report.error) {
    return <Shell><p className="error">Could not load {id}: {report.error}</p></Shell>;
  }
  if (!data) {
    return <Shell><p className="loading"><Spinner label={`Loading ${id}…`} /></p></Shell>;
  }
  const summary = data.summary;
  const bars = data.hourly.filter((hour) => hour.observations > 0 || hour.excessWaitSec != null);
  const values = bars.map((hour) => Math.abs(summary.frequent ? (hour.excessWaitSec ?? 0) : (hour.avgDelaySec ?? 0)));
  const max = Math.max(60, ...values);
  return (
    <Shell>
      <p><Link className="muted" to="/">All routes</Link></p>
      <h1>
        <span className={summary.frequent ? "chip frequent" : "chip"}>{summary.id}</span>
        {" "}{summary.longName}
        {summary.earlyData && <span className="badge">early data</span>}
      </h1>
      <div className="toolbar">
        <label>
          Direction{" "}
          <select
            value={direction ?? ""}
            onChange={(event) => {
              const next = new URLSearchParams(params);
              if (event.target.value === "") next.delete("direction");
              else next.set("direction", event.target.value);
              setParams(next);
            }}
          >
            <option value="">Both</option>
            {data.directions.map((choice) => (
              <option key={choice.directionId} value={choice.directionId}>
                {choice.headsign || `Direction ${choice.directionId}`}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="cards">
        <div className="card">
          <div className="muted">{summary.frequent ? "Extra wait" : "On time"}</div>
          <div className="big">
            {summary.frequent ? (minutes(summary.excessWaitSec) ?? "—") : (pct(summary.onTimePct) ?? "—")}
          </div>
        </div>
        <div className="card">
          <div className="muted">{summary.frequent ? "Even spacing" : "Average delay"}</div>
          <div className="big">
            {summary.frequent ? (pct(summary.regularityPct) ?? "—") : (minutes(summary.avgDelaySec) ?? "—")}
          </div>
        </div>
        <div className="card">
          <div className="muted">Completed arrivals</div>
          <div className="big">{summary.observations.toLocaleString()}</div>
          <div className="kv">{summary.days} day{summary.days === 1 ? "" : "s"} · {summary.cancelled} cancelled</div>
        </div>
      </div>
      <section className="panel" style={{ marginTop: 16 }}>
        <h2>{summary.frequent ? "Extra wait by hour" : "Average delay by hour"}</h2>
        <div className="legend">
          <span><i className="swatch" style={{ background: summary.frequent ? "var(--freq)" : "var(--sched)" }} />
            {summary.frequent ? "Frequent hour, scored by wait" : "Scheduled hour, scored by the timetable"}
          </span>
        </div>
        {bars.length === 0 && <p className="muted">No completed arrivals in this range yet.</p>}
        <div className="bars">
          {bars.map((hour) => {
            const value = Math.abs(summary.frequent ? (hour.excessWaitSec ?? 0) : (hour.avgDelaySec ?? 0));
            return (
              <div key={hour.hour} className={hour.type === "frequent" ? "bar frequent" : "bar"} style={{ height: `${(value / max) * 100}%` }}>
                <span>{hour.hour}</span>
              </div>
            );
          })}
        </div>
      </section>
      <section className="panel" style={{ marginTop: 16 }}>
        <h2>Delay along the route</h2>
        <table>
          <thead><tr><th>Stop</th><th>Avg delay</th><th>Arrivals</th></tr></thead>
          <tbody>
            {data.stops.map((stop) => (
              <tr key={`${stop.stopId}-${stop.sequence}`}>
                <td>{stop.name}</td>
                <td>{minutes(stop.avgDelaySec)}</td>
                <td>{stop.observations}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </Shell>
  );
}

function About() {
  return (
    <Shell>
      <div className="about">
        <h1>How the numbers are made</h1>
        <p>
          The collector stores every TransLink bus, all day. This site scores the routes that serve SFU:
          R5, 143, 144, and 145. Schedule and arrival data come from TransLink’s GTFS feeds.
        </p>
        <p>
          A completed arrival is the last prediction recorded before that stop dropped out of the live feed.
          Comparing those times with bus locations is still in progress, so every figure here is marked early
          until there are 10 service days.
        </p>
        <ul>
          <li>On time means between 1 minute early and 3 minutes late. That score is for scheduled routes.</li>
          <li>The R5 and any hour with a scheduled gap of 10 minutes or less is scored by extra wait and by how even the gaps are. On-time percent is not shown for those hours.</li>
          <li>A cancellation is a trip TransLink marked cancelled. A bus that was scheduled and never appeared is a separate case, and it is not counted until a full service day has been recorded.</li>
          <li>Bunching is two buses at the same stop less than a quarter of the scheduled gap apart.</li>
        </ul>
        <p>TransLink data is used for research. TransLink is not responsible for these scores.</p>
      </div>
    </Shell>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/routes/:id" element={<RoutePage />} />
      <Route path="/about" element={<About />} />
    </Routes>
  );
}
