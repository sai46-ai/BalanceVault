import { useState } from 'react';
import { api, money, when } from '../api.js';
import { Button, Empty, Icon, Notice, PageHead, Pill, Skeleton, shortHash, useAction, useLoad, useToast } from '../ui.jsx';

const PAGE = 50;
const OUTCOME_ICON = { success: 'check', failure: 'alert', denied: 'lock' };

/** Independent verification of both hash chains, plus the append-only audit trail. */
export default function Proof({ can }) {
  return (
    <section className="view">
      <PageHead eyebrow="Assurance" title="Proof & audit"
                lead="Every journal and audit event is sealed into a SHA-256 chain. Recompute it any time; a single edited byte breaks the chain." />
      {can('ledger:verify') ? <Verify /> : null}
      {can('audit:read') ? <AuditTrail /> : null}
    </section>
  );
}

/** Runs the server-side recomputation and shows the head hashes worth recording. */
function Verify() {
  const [result, setResult] = useState(null);
  const action = useAction();

  /** Requests a full verification of both chains. */
  async function run() {
    const r = await action.run(() => api('/api/ledger/verify'));
    if (r) setResult(r);
  }

  const tone = !result ? 'idle' : (result.ok ? 'ok' : 'bad');
  return (
    <>
      <div className={`verdict ${tone}`}>
        <span className="verdict-icon"><Icon name={tone === 'bad' ? 'alert' : 'shield'} size={26} /></span>
        <div>
          <h3>{!result ? 'Verify the ledger' : (result.ok ? 'History is intact' : 'Verification failed')}</h3>
          <p>
            {!result
              ? 'Recomputes every hash and chain link, checks each journal sums to zero and the whole ledger sums to zero.'
              : `Checked ${result.ledger.rows} journals and ${result.audit.rows} audit events.`}
          </p>
        </div>
        <Button className="btn btn-primary" busy={action.busy} onClick={run}>
          <Icon name="refresh" size={15} /> {result ? 'Verify again' : 'Verify now'}
        </Button>
      </div>
      <Notice error={action.error} />
      {result ? (
        <div className="grid grid-2">
          <ChainCard title="Ledger chain" icon="hash" r={result.ledger}>
            <dt>Entries</dt><dd>{result.ledger.entries}</dd>
            <dt>Net total</dt><dd className={result.ledger.totalCents === 0 ? '' : 'neg'}>{money(result.ledger.totalCents)}</dd>
          </ChainCard>
          <ChainCard title="Audit chain" icon="list" r={result.audit} />
        </div>
      ) : null}
    </>
  );
}

/** One chain's summary, its head hash and the first reported problems. */
function ChainCard({ title, icon, r, children }) {
  const toast = useToast();

  /** Copies the head hash; the Clipboard API exists only in secure contexts. */
  async function copy() {
    if (!(navigator.clipboard && window.isSecureContext)) {
      toast('Clipboard needs HTTPS. Select the hash and press Ctrl+C.', 'error');
      return;
    }
    try {
      await navigator.clipboard.writeText(r.headHash);
      toast('Head hash copied');
    } catch (err) {
      toast(`Copy failed: ${err.message}`, 'error');
    }
  }

  return (
    <div className="card">
      <div className="card-head">
        <div className="row"><Icon name={icon} /><h3>{title}</h3></div>
        <Pill value={r.ok ? 'ok' : 'failed'} />
      </div>
      <div className="card-body stack">
        <dl className="kv">
          <dt>Rows</dt><dd>{r.rows}</dd>
          {children}
          <dt>Problems</dt><dd>{r.problemCount}</dd>
          <dt>Head hash</dt><dd className="hash">{r.headHash}</dd>
        </dl>
        <div><button type="button" className="btn btn-sm btn-ghost" onClick={copy}><Icon name="copy" size={14} /> Copy head hash</button></div>
        {r.problems.length > 0 ? <ul className="problems">{r.problems.map((p, i) => <li key={i}>{p}</li>)}</ul> : null}
      </div>
    </div>
  );
}

/** Newest-first audit events as a hash-linked timeline, keyset paged by id; filters apply to loaded rows. */
function AuditTrail() {
  const first = useLoad(() => api('/api/audit', { query: { limit: PAGE } }), []);
  const [pages, setPages] = useState([]);
  const [outcome, setOutcome] = useState('all');
  const [search, setSearch] = useState('');
  const more = useAction();
  const rows = [...(first.data?.events || []), ...pages];
  const lastPageFull = (pages.length === 0 ? (first.data?.events || []).length : pages.length % PAGE || PAGE) === PAGE;
  const needle = search.trim().toLowerCase();
  const shown = rows.filter((e) => (outcome === 'all' || e.outcome === outcome)
    && (!needle || `${e.action} ${e.actorUsername || ''} ${e.targetType || ''} ${e.targetId || ''}`.toLowerCase().includes(needle)));

  /** Loads the next older page using the oldest loaded id as the key. */
  async function older() {
    const r = await more.run(() => api('/api/audit', { query: { before: rows[rows.length - 1].id, limit: PAGE } }));
    if (r) setPages((p) => [...p, ...r.events]);
  }

  return (
    <div className="card card-flush">
      <div className="card-head card-body">
        <div>
          <h3>Audit trail</h3>
          <p>Append-only. Database triggers reject every UPDATE, DELETE and TRUNCATE.</p>
        </div>
        <button type="button" className="btn btn-sm" onClick={() => { setPages([]); first.reload(); }}><Icon name="refresh" size={14} /> Refresh</button>
      </div>
      <div className="toolbar">
        <label className="search">
          <Icon name="search" size={16} />
          <input className="input" type="search" placeholder="Filter by action, actor or target" maxLength={80}
                 value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Filter audit events" />
        </label>
        <span className="spacer" />
        <div className="tabs" role="tablist" aria-label="Outcome">
          {['all', 'success', 'failure', 'denied'].map((o) => (
            <button key={o} type="button" role="tab" aria-selected={outcome === o} className={outcome === o ? 'tab on' : 'tab'} onClick={() => setOutcome(o)}>{o}</button>
          ))}
        </div>
      </div>
      <div className="card-body">
        <Notice error={first.error || more.error} />
        {first.loading && !first.data ? <Skeleton lines={6} /> : null}
        {first.data && shown.length === 0 ? <Empty icon="list" title="No matching events">Try another filter or load older events.</Empty> : null}
        <ol className="chain">
          {shown.map((e) => (
            <li key={e.id} className="chain-node">
              <span className="chain-dot"><Icon name={OUTCOME_ICON[e.outcome] || 'info'} size={14} /></span>
              <div className="chain-body">
                <div className="chain-meta">
                  <span className="strong">{e.action}</span>
                  <Pill value={e.outcome} />
                  <span>{e.actorUsername || 'anonymous'}</span>
                  {e.targetType ? <span>{e.targetType} {e.targetId}</span> : null}
                  <span><Icon name="clock" size={13} /> {when(e.at)}</span>
                  <span className="hash-short" title={e.hash}>#{e.id} · {shortHash(e.hash)}</span>
                </div>
                {e.detail ? <code className="hash">{e.detail}</code> : null}
                {e.requestId ? <div className="cell-sub mono">request {e.requestId}</div> : null}
              </div>
            </li>
          ))}
        </ol>
      </div>
      {rows.length > 0 && lastPageFull ? (
        <div className="table-foot">
          <Button className="btn btn-sm" busy={more.busy} onClick={older}>Load older events</Button>
        </div>
      ) : null}
    </div>
  );
}
