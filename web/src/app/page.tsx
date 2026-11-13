import Link from "next/link";
import { REPO_URL } from "@/lib/config";

const guarantees = [
  {
    claim: "Zero lost pings",
    how: "The ingest API answers only after Kafka acknowledges (acks=all, idempotent producer). The writer commits offsets only after Postgres commits.",
    proof: "Chaos run: kill -9 writer, Postgres down 40 s, Kafka down 40 s → 21,311 acknowledged = 21,311 stored.",
  },
  {
    claim: "Zero duplicate rows",
    how: "COPY into a staging table, then INSERT … ON CONFLICT DO NOTHING on (vehicle_id, event_ts, event_id). Redelivery is harmless.",
    proof: "Integration test crashes the writer between DB commit and offset commit, restarts it: rows = distinct events.",
  },
  {
    claim: "Bad rows never block good ones",
    how: "SQLState classes split data errors (bisect, park in DLQ) from outages (pause, rewind with seek(), retry whole batch).",
    proof: "500-row batch with 3 bad rows → 497 stored, exactly 3 in the DLQ; a DB outage parks nothing.",
  },
  {
    claim: "Trucks never move backwards",
    how: "One Redis Lua script compares event time, writes, updates the GEO index and publishes — atomically. The browser guards again.",
    proof: "Replaying hour-old pings leaves positions unchanged (test); the console counts every rejected late ping.",
  },
  {
    claim: "A slow browser can't hurt others",
    how: "Netty write-buffer watermarks + a per-client buffer that keeps only the newest position per truck, flushed every 250 ms.",
    proof: "Unit test: a client that never reads stays at a fixed number of buffered positions.",
  },
  {
    claim: "Tenants are isolated",
    how: "Devices sign requests with derived per-device HMAC keys; dispatchers' JWTs carry fleets, checked on every query and subscription.",
    proof: "Tests: tampered body, wrong fleet, replayed nonce, revoked key, alg=none — all rejected.",
  },
];

const stack = [
  ["Ingest & query", "Java 25, Spring Boot 4 (HTTP edges only)"],
  ["Data path", "Plain kafka-clients, JDBC + CopyManager, HikariCP"],
  ["Streaming", "Apache Kafka 4.4 (KRaft), 12 partitions, key = vehicleId"],
  ["Storage", "TimescaleDB 2.32: hypertable, compression, retention, continuous aggregate"],
  ["Live state", "Redis 8: GEO index, Lua guard, Pub/Sub"],
  ["Fan-out", "Netty 4.2 WebSocket gateway"],
  ["Console", "Next.js 16, React 19, Tailwind v4, MapLibre GL 6, Web Workers"],
  ["Ops", "Docker Compose, Prometheus alerts + runbooks, Grafana, GitHub Actions, Testcontainers"],
];

export default function Home() {
  return (
    <div className="min-h-dvh bg-canvas text-ink">
      <header className="mx-auto flex max-w-6xl items-center justify-between px-4 py-5 sm:px-6">
        <span className="flex items-center gap-2 font-semibold tracking-tight">
          <span aria-hidden className="grid size-8 place-items-center rounded-lg bg-accent font-bold text-on-accent">N</span>
          NEXUS
        </span>
        <nav className="flex items-center gap-4 text-sm">
          <a href={REPO_URL} className="text-muted hover:text-ink">Source</a>
          <Link href="/console/" className="rounded-md bg-accent px-3 py-1.5 font-medium text-on-accent hover:opacity-90">Open console</Link>
        </nav>
      </header>

      <main className="mx-auto max-w-6xl px-4 pb-20 sm:px-6">
        <section className="py-10 sm:py-16">
          <p className="text-sm font-medium text-accent">Real-time fleet tracking platform</p>
          <h1 className="mt-3 max-w-3xl text-4xl font-semibold leading-tight tracking-tight sm:text-5xl">
            Every GPS ping stored exactly once, and on the map within seconds.
          </h1>
          <p className="mt-5 max-w-2xl text-lg text-muted">
            NEXUS ingests signed telemetry from thousands of trucks, streams it through Kafka into TimescaleDB, and pushes
            live positions to a WebGL dispatcher console — staying correct through crashes, redeliveries, bad data and
            outages.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Link href="/console/" className="rounded-lg bg-accent px-5 py-2.5 font-medium text-on-accent hover:opacity-90">
              Open the live console →
            </Link>
            <a href={REPO_URL} className="rounded-lg border border-line bg-surface px-5 py-2.5 font-medium hover:bg-surface-2">
              Read the code
            </a>
          </div>
          <p className="mt-3 text-sm text-muted">
            The hosted console runs a simulated fleet in your browser. Point it at the Docker Compose stack for live data.
          </p>
        </section>

        <section aria-labelledby="numbers" className="grid gap-3 sm:grid-cols-4">
          <h2 id="numbers" className="sr-only">Measured results</h2>
          {[
            ["21,311 / 21,311", "pings acknowledged vs stored through three outages"],
            ["0", "duplicate rows after crash and replay tests"],
            ["67", "automated tests, incl. Kafka/Postgres/Redis containers"],
            ["250 ms", "per-client batching instead of one frame per ping"],
          ].map(([n, label]) => (
            <div key={label} className="rounded-xl border border-line bg-surface p-4">
              <p className="text-2xl font-semibold tabular-nums">{n}</p>
              <p className="mt-1 text-sm text-muted">{label}</p>
            </div>
          ))}
        </section>

        <section aria-labelledby="arch" className="mt-16">
          <h2 id="arch" className="text-2xl font-semibold tracking-tight">How a ping flows</h2>
          <div className="mt-6 overflow-x-auto rounded-xl border border-line bg-surface p-4">
            <Architecture />
          </div>
          <ol className="mt-6 grid gap-3 text-sm text-muted sm:grid-cols-2 lg:grid-cols-3">
            <li><b className="text-ink">1. Device</b> signs a batch (HMAC-SHA256 over fleet, device, key version, timestamp, nonce, body hash).</li>
            <li><b className="text-ink">2. Ingest API</b> verifies signature, window and nonce, validates each ping, publishes keyed by vehicleId and waits for the broker ack.</li>
            <li><b className="text-ink">3. Writer</b> batches, COPYs into staging, inserts with ON CONFLICT DO NOTHING, bisects bad rows to the DLQ, then commits offsets.</li>
            <li><b className="text-ink">4. Redis</b> keeps last positions in a GEO set per fleet, updated by a Lua script only when newer.</li>
            <li><b className="text-ink">5. Gateway</b> fans positions out over WebSocket, coalesced per client, batched every 250 ms.</li>
            <li><b className="text-ink">6. Console</b> loads a snapshot, applies live updates in a Web Worker and animates thousands of trucks with WebGL.</li>
          </ol>
        </section>

        <section aria-labelledby="guarantees" className="mt-16">
          <h2 id="guarantees" className="text-2xl font-semibold tracking-tight">Guarantees, and how each is proven</h2>
          <div className="mt-6 grid gap-4 md:grid-cols-2">
            {guarantees.map((g) => (
              <article key={g.claim} className="rounded-xl border border-line bg-surface p-5">
                <h3 className="font-semibold">{g.claim}</h3>
                <p className="mt-2 text-sm text-muted">{g.how}</p>
                <p className="mt-3 border-t border-line pt-3 text-sm"><span className="font-medium text-moving">Evidence · </span>{g.proof}</p>
              </article>
            ))}
          </div>
        </section>

        <section aria-labelledby="stack" className="mt-16 grid gap-8 lg:grid-cols-2">
          <div>
            <h2 id="stack" className="text-2xl font-semibold tracking-tight">Stack</h2>
            <dl className="mt-6 divide-y divide-line rounded-xl border border-line bg-surface">
              {stack.map(([k, v]) => (
                <div key={k} className="grid grid-cols-3 gap-4 px-4 py-3 text-sm">
                  <dt className="text-muted">{k}</dt>
                  <dd className="col-span-2">{v}</dd>
                </div>
              ))}
            </dl>
          </div>
          <div>
            <h2 className="text-2xl font-semibold tracking-tight">Run it yourself</h2>
            <pre className="mt-6 overflow-x-auto rounded-xl border border-line bg-surface p-4 text-sm leading-relaxed"><code>{`git clone ${REPO_URL}
cd nexus
docker compose up -d --build     # Kafka, TimescaleDB, Redis, 4 services, 200 trucks
cd web && npm ci
NEXT_PUBLIC_QUERY_URL=http://localhost:8081 \\
NEXT_PUBLIC_GATEWAY_URL=ws://localhost:8090/ws \\
npm run dev                      # open /console?mode=live`}</code></pre>
            <p className="mt-3 text-sm text-muted">
              Design doc, decision records, runbooks and chaos-run notes are in <code className="font-mono">docs/</code>.
            </p>
          </div>
        </section>
      </main>

      <footer className="border-t border-line py-6 text-center text-sm text-muted">
        NEXUS · personal project · <a href={REPO_URL} className="underline hover:text-ink">source on GitHub</a>
      </footer>
    </div>
  );
}

function Architecture() {
  const box = "fill-[var(--color-surface-2)] stroke-[var(--color-line)]";
  const text = "fill-[var(--color-ink)] text-[13px] font-medium";
  const sub = "fill-[var(--color-muted)] text-[11px]";
  const nodes: [number, number, string, string][] = [
    [10, 60, "Devices", "signed HTTPS batches"],
    [170, 60, "Ingest API", "auth · validate · ack"],
    [330, 60, "Kafka", "key = vehicleId"],
    [490, 60, "Writer", "COPY · dedup · bisect"],
    [650, 10, "TimescaleDB", "history"],
    [650, 110, "Redis", "GEO · Lua · Pub/Sub"],
    [810, 110, "Gateway", "Netty WebSocket"],
    [810, 10, "Query API", "snapshot · track"],
    [970, 60, "Console", "Next.js · MapLibre"],
  ];
  const arrows: [number, number, number, number][] = [
    [140, 85, 170, 85], [300, 85, 330, 85], [460, 85, 490, 85],
    [620, 75, 650, 40], [620, 95, 650, 135], [780, 135, 810, 135],
    [780, 35, 810, 35], [715, 110, 840, 60], [940, 35, 970, 75], [940, 135, 970, 95],
  ];
  return (
    <svg viewBox="0 0 1110 175" className="min-w-[860px]" role="img" aria-label="Architecture: devices to ingest API to Kafka to writer, which stores in TimescaleDB and updates Redis; the gateway streams from Redis and the query API reads both, feeding the console.">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" className="fill-[var(--color-muted)]" />
        </marker>
      </defs>
      {arrows.map(([x1, y1, x2, y2], i) => (
        <line key={i} x1={x1} y1={y1} x2={x2} y2={y2} className="stroke-[var(--color-muted)]" strokeWidth={1.5} markerEnd="url(#arrow)" />
      ))}
      {nodes.map(([x, y, title, s]) => (
        <g key={title}>
          <rect x={x} y={y} width={130} height={52} rx={10} className={box} />
          <text x={x + 65} y={y + 23} textAnchor="middle" className={text}>{title}</text>
          <text x={x + 65} y={y + 40} textAnchor="middle" className={sub}>{s}</text>
        </g>
      ))}
    </svg>
  );
}
