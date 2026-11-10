import { useState } from 'react';
import { api, money, when } from '../api.js';
import { Empty, Icon, Notice, PageHead, Skeleton, Stat, useLoad } from '../ui.jsx';

const KINDS = ['deposit', 'withdrawal', 'transfer', 'reversal'];
const KIND_ICON = { deposit: 'down', withdrawal: 'up', transfer: 'swap', reversal: 'undo' };

/** Axis-friendly dollars: $950, $12.4k, $3.1M. Display only; amounts in tables always use exact money(). */
function compact(cents) {
  const d = Number(cents) / 100;
  if (Math.abs(d) >= 1e6) return `$${(d / 1e6).toFixed(1)}M`;
  if (Math.abs(d) >= 1e3) return `$${(d / 1e3).toFixed(1)}k`;
  return `$${Math.round(d)}`;
}

/** "Mon 3" style label for an ISO date (YYYY-MM-DD), read as UTC to match the server's day buckets. */
function dayLabel(iso, long) {
  const d = new Date(`${iso}T00:00:00Z`);
  return d.toLocaleDateString(undefined, long ? { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' }
    : { month: 'short', day: 'numeric', timeZone: 'UTC' });
}

/** Greeting by local hour. */
function greeting() {
  const h = new Date().getHours();
  return h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
}

/** Overview: KPIs, 30-day cash flow, activity mix, account health and recent journals. All numbers come pre-aggregated from SQL. */
export default function Dashboard({ user, can, go }) {
  const summary = useLoad(() => api('/api/dashboard'), []);
  const d = summary.data;
  const daily = d?.daily || [];
  // 30 pre-aggregated day rows from the server; summing them is display arithmetic, not data aggregation.
  const inflow = daily.reduce((s, x) => s + x.inflowCents, 0);
  const outflow = daily.reduce((s, x) => s + x.outflowCents, 0);
  const t = d?.totals;
  const staff = can('accounts:read_all');

  return (
    <section className="view">
      <PageHead eyebrow={greeting()} title={`Welcome back, ${String(user.displayName).split(' ')[0]}`}
                lead={staff ? 'Money held for customers, movement over the last 30 days and the state of every account.'
                  : 'Your balances and how money has moved through your accounts over the last 30 days.'}>
        <button type="button" className="btn" onClick={summary.reload}><Icon name="refresh" size={15} /> Refresh</button>
        {can('accounts:read_all', 'accounts:read_own') ? (
          <button type="button" className="btn" onClick={() => go('accounts')}><Icon name="wallet" size={15} /> Accounts</button>
        ) : null}
      </PageHead>
      <Notice error={summary.error} />

      <div className="grid grid-4">
        <Stat feature icon="wallet" label={staff ? 'Held for customers' : 'Total balance'}
              value={t ? money(t.balanceCents) : '—'} foot="Derived from ledger entries" />
        <Stat icon="down" label="Money in · 30 days" value={d ? money(inflow) : '—'} foot="Credits to visible accounts" />
        <Stat icon="up" label="Money out · 30 days" value={d ? money(outflow) : '—'} foot="Debits from visible accounts" />
        <Stat icon="card" label="Active accounts" value={t ? t.active : '—'}
              foot={t ? `${t.frozen} frozen · ${t.closed} closed` : ''} />
      </div>

      <div className="grid grid-main">
        <div className="card">
          <div className="card-head">
            <div>
              <h2>Cash flow</h2>
              <p>Daily money in and out, last {d?.windowDays || 30} days (UTC)</p>
            </div>
            <div className="chart-legend">
              <span><i className="swatch in" /> In</span>
              <span><i className="swatch out" /> Out</span>
            </div>
          </div>
          <div className="card-body">
            {summary.loading && !d ? <div className="skeleton block" /> : <FlowChart days={daily} />}
          </div>
        </div>

        <div className="card">
          <div className="card-head">
            <div>
              <h2>Activity mix</h2>
              <p>Journals by kind, last 30 days</p>
            </div>
          </div>
          <div className="card-body">
            {summary.loading && !d ? <div className="skeleton block" /> : <Donut items={d?.byKind || []} />}
          </div>
        </div>
      </div>

      <div className="grid grid-main">
        {can('transactions:read_all', 'transactions:read_own') ? <Recent go={go} /> : null}
        <div className="card">
          <div className="card-head">
            <div>
              <h2>Account health</h2>
              <p>Status of {staff ? 'customer' : 'your'} accounts</p>
            </div>
          </div>
          <div className="card-body">
            {t ? <StatusMeter totals={t} /> : <Skeleton lines={3} />}
          </div>
        </div>
      </div>
    </section>
  );
}

// Chart geometry in SVG user units; the SVG scales to its card width via viewBox.
const W = 640;
const H = 230;
const PAD = { l: 52, r: 12, t: 12, b: 26 };

/** Area (in) + dashed line (out) chart with a hover read-out per day. Pure SVG, so no chart library ships. */
function FlowChart({ days }) {
  const [hover, setHover] = useState(null);
  if (days.length === 0) return <Empty icon="trend" title="No data yet" />;

  const max = Math.max(1, ...days.map((x) => Math.max(x.inflowCents, x.outflowCents)));
  const step = (W - PAD.l - PAD.r) / Math.max(1, days.length - 1);
  const x = (i) => PAD.l + i * step;
  const y = (v) => PAD.t + (1 - v / max) * (H - PAD.t - PAD.b);
  const line = (key) => days.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(p[key]).toFixed(1)}`).join(' ');
  const inLine = line('inflowCents');
  const area = `${inLine} L${x(days.length - 1).toFixed(1)},${H - PAD.b} L${PAD.l},${H - PAD.b} Z`;
  const ticks = [0, 0.25, 0.5, 0.75, 1];
  const shown = hover === null ? days.length - 1 : hover;
  const p = days[shown];

  return (
    <>
      <div className="chart-tip" aria-live="polite">
        <span><strong>{dayLabel(p.day, true)}</strong></span>
        <span>In <strong className="pos">{money(p.inflowCents)}</strong></span>
        <span>Out <strong className="neg">{money(p.outflowCents)}</strong></span>
        <span>{p.journals} journal{p.journals === 1 ? '' : 's'}</span>
      </div>
      <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img"
           aria-label={`Cash flow chart: ${money(days.reduce((s, q) => s + q.inflowCents, 0))} in over ${days.length} days`}
           onMouseLeave={() => setHover(null)}>
        <defs>
          <linearGradient id="gradIn" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" className="stop-in-a" />
            <stop offset="100%" className="stop-in-b" />
          </linearGradient>
        </defs>
        {ticks.map((f) => (
          <g key={f}>
            <line className="grid-line" x1={PAD.l} x2={W - PAD.r} y1={y(max * f)} y2={y(max * f)} />
            <text className="axis" x={PAD.l - 8} y={y(max * f) + 3} textAnchor="end">{compact(max * f)}</text>
          </g>
        ))}
        {days.map((q, i) => (i % 7 === 0 || i === days.length - 1 ? (
          <text key={q.day} className="axis" x={x(i)} y={H - 6} textAnchor={i === 0 ? 'start' : i === days.length - 1 ? 'end' : 'middle'}>
            {dayLabel(q.day)}
          </text>
        ) : null))}
        {days.map((q, i) => (
          <rect key={`h${q.day}`} className="hover-col" x={x(i) - step / 2} y={PAD.t} width={step} height={H - PAD.t - PAD.b}
                onMouseEnter={() => setHover(i)} />
        ))}
        <path className="area-in" d={area} />
        <path className="line-in" d={inLine} />
        <path className="line-out" d={line('outflowCents')} />
        <circle className="dot-in" cx={x(shown)} cy={y(p.inflowCents)} r="4" />
        <circle className="dot-out" cx={x(shown)} cy={y(p.outflowCents)} r="4" />
      </svg>
    </>
  );
}

/** Donut of journal counts by kind, with a legend. */
function Donut({ items }) {
  const counts = Object.fromEntries(items.map((k) => [k.kind, k.count]));
  const total = items.reduce((s, k) => s + k.count, 0);
  if (total === 0) return <Empty icon="list" title="No journals in this window">Movements will appear here once posted.</Empty>;

  const r = 54;
  const c = 2 * Math.PI * r;
  let offset = 0;
  const segs = KINDS.filter((k) => counts[k]).map((k) => {
    const len = (counts[k] / total) * c;
    const seg = { k, len, offset };
    offset += len;
    return seg;
  });

  return (
    <div className="donut">
      <svg viewBox="0 0 150 150" role="img" aria-label={`${total} journals`}>
        <circle className="track" cx="75" cy="75" r={r} />
        <g transform="rotate(-90 75 75)">
          {segs.map((s) => (
            <circle key={s.k} className={`seg k-${s.k}`} cx="75" cy="75" r={r}
                    strokeDasharray={`${Math.max(0, s.len - 1.5)} ${c}`} strokeDashoffset={-s.offset} />
          ))}
        </g>
        <text className="center-num" x="75" y="80">{total}</text>
        <text className="center-label" x="75" y="98">journals</text>
      </svg>
      <ul>
        {KINDS.map((k) => (
          <li key={k}>
            <i className={`swatch k-${k}`} />
            {k}
            <span className="num">{counts[k] || 0}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Horizontal meters for active / frozen / closed account counts. */
function StatusMeter({ totals }) {
  const rows = [['active', totals.active], ['frozen', totals.frozen], ['closed', totals.closed]];
  const all = Math.max(1, totals.active + totals.frozen + totals.closed);
  return (
    <div className="meter">
      {rows.map(([label, n]) => (
        <div key={label} className="meter-row">
          <span>{label}</span>
          <svg viewBox="0 0 100 8" preserveAspectRatio="none" aria-hidden="true">
            <rect className="bar-track" x="0" y="0" width="100" height="8" rx="4" />
            <rect className="bar" x="0" y="0" width={(n / all) * 100} height="8" rx="4" />
          </svg>
          <span className="num">{n}</span>
        </div>
      ))}
      <p className="muted small mt">Closing requires a zero balance; every status change carries an audited reason.</p>
    </div>
  );
}

/** The six most recent journals the user may see. */
function Recent({ go }) {
  const list = useLoad(() => api('/api/transactions', { query: { limit: 6 } }), []);
  const rows = list.data?.transactions || [];
  return (
    <div className="card card-flush">
      <div className="card-head">
        <div>
          <h2>Recent activity</h2>
          <p>Latest journals posted to the ledger</p>
        </div>
        <button type="button" className="btn btn-sm btn-ghost" onClick={() => go('transactions')}>
          View all <Icon name="chevron" size={14} />
        </button>
      </div>
      <Notice error={list.error} />
      {list.loading && !list.data ? <Skeleton lines={5} /> : null}
      {!list.loading && rows.length === 0 ? <Empty icon="list" title="No transactions yet" /> : null}
      {rows.length > 0 ? (
        <table className="table">
          <tbody>
            {rows.map((j) => {
              // Gross size of a journal = sum of its credit legs (it always balances to zero).
              const gross = j.legs.filter((l) => l.amountCents > 0).reduce((s, l) => s + l.amountCents, 0);
              return (
                <tr key={j.id}>
                  <td>
                    <div className="cell-main">
                      <span className={`cell-icon ${j.kind}`}><Icon name={KIND_ICON[j.kind] || 'list'} size={16} /></span>
                      <div>
                        <div className="strong">{j.memo || j.kind}</div>
                        <div className="cell-sub">#{j.id} · {when(j.createdAt)}</div>
                      </div>
                    </div>
                  </td>
                  <td className="num strong">{money(gross)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      ) : null}
    </div>
  );
}
