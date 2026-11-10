import { useState } from 'react';
import { api, money, newRequestKey, when } from '../api.js';
import { Button, Dialog, Empty, Icon, Notice, PageHead, Pill, Skeleton, shortHash, useAction, useLoad, useToast } from '../ui.jsx';

const KINDS = ['all', 'deposit', 'withdrawal', 'transfer', 'reversal'];
const KIND_ICON = { deposit: 'down', withdrawal: 'up', transfer: 'swap', reversal: 'undo' };

/** Gross size of a balanced journal: the sum of its credit legs. */
function gross(j) {
  return j.legs.filter((l) => l.amountCents > 0).reduce((s, l) => s + l.amountCents, 0);
}

/** Journal history (newest first, keyset paged). Rows expand to show legs and hash; operators can reverse. */
export default function Transactions({ can }) {
  const first = useLoad(() => api('/api/transactions', { query: { limit: 50 } }), []);
  const [pages, setPages] = useState([]);
  const [open, setOpen] = useState(null);
  const [kind, setKind] = useState('all');
  const [reversing, setReversing] = useState(null);
  const more = useAction();
  const rows = [...(first.data?.transactions || []), ...pages];
  // Kind filter narrows only the rows already loaded; paging still walks the full history by id.
  const shown = kind === 'all' ? rows : rows.filter((j) => j.kind === kind);

  /** Loads the next older page using the last journal id as the cursor. */
  async function older() {
    const r = await more.run(() => api('/api/transactions', { query: { before: rows[rows.length - 1].id, limit: 50 } }));
    if (r) setPages((p) => [...p, ...r.transactions]);
  }

  /** Reloads from the top (after a reversal or on demand). */
  function refresh() {
    setPages([]);
    setOpen(null);
    first.reload();
  }

  return (
    <section className="view">
      <PageHead eyebrow="Banking" title="Transactions" lead="Append-only journals. Each one balances to zero and is hash-linked to the one before.">
        <button type="button" className="btn" onClick={refresh}><Icon name="refresh" size={15} /> Refresh</button>
      </PageHead>

      <div className="card card-flush">
        <div className="toolbar">
          <div className="segmented" role="group" aria-label="Filter by kind">
            {KINDS.map((k) => (
              <button key={k} type="button" className={kind === k ? 'on' : ''} aria-pressed={kind === k} onClick={() => setKind(k)}>
                {k[0].toUpperCase() + k.slice(1)}
              </button>
            ))}
          </div>
          <span className="spacer" />
          <span className="muted small">{shown.length} shown</span>
        </div>
        <Notice error={first.error || more.error} />
        {first.loading && !first.data ? <Skeleton lines={8} /> : null}
        {!first.loading && shown.length === 0 ? <Empty icon="list" title="No transactions">Nothing matches this view yet.</Empty> : null}
        {shown.length > 0 ? (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Journal</th><th>Kind</th><th>By</th><th>When</th><th className="num">Amount</th><th aria-label="Expand" /></tr></thead>
              <tbody>
                {shown.map((j) => (
                  <JournalRow key={j.id} j={j} expanded={open === j.id} onToggle={() => setOpen(open === j.id ? null : j.id)}
                              canReverse={can('money:reverse')} onReverse={() => setReversing(j)} />
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        {rows.length > 0 && rows.length % 50 === 0 ? (
          <div className="table-foot"><Button className="btn btn-sm" busy={more.busy} onClick={older}>Load older</Button></div>
        ) : null}
      </div>

      <Reverse journal={reversing} onClose={() => setReversing(null)} onReversed={() => { setReversing(null); refresh(); }} />
    </section>
  );
}

/** One journal row; the detail row lists every leg, the hashes and the reversal link. */
function JournalRow({ j, expanded, onToggle, canReverse, onReverse }) {
  return (
    <>
      <tr className={expanded ? 'clickable expanded' : 'clickable'} onClick={onToggle}>
        <td>
          <div className="cell-main">
            <span className={`cell-icon ${j.kind}`}><Icon name={KIND_ICON[j.kind] || 'list'} size={16} /></span>
            <div>
              <div className="strong">{j.memo || j.kind}</div>
              <div className="cell-sub">
                #{j.id}
                {j.reversesJournalId ? ` · reverses #${j.reversesJournalId}` : ''}
                {j.reversedById ? ` · reversed by #${j.reversedById}` : ''}
              </div>
            </div>
          </div>
        </td>
        <td><Pill value={j.kind} /></td>
        <td>{j.createdBy}</td>
        <td className="small">{when(j.createdAt)}</td>
        <td className="num strong">{money(gross(j))}</td>
        <td>
          <button type="button" className="btn-icon" aria-expanded={expanded} aria-label={expanded ? 'Hide details' : 'Show details'}
                  onClick={(e) => { e.stopPropagation(); onToggle(); }}>
            <Icon name="chevron" size={16} className={expanded ? 'icon rot' : 'icon'} />
          </button>
        </td>
      </tr>
      {expanded ? (
        <tr className="detail">
          <td colSpan="6">
            <div className="grid grid-2">
              <div className="chain-body">
                <div className="chain-meta"><span className="strong">Legs</span><span>sum $0.00</span></div>
                <div className="legs">
                  {j.legs.map((l) => (
                    <div key={l.accountId} className="leg">
                      <span>{l.accountNumber}</span>
                      <span className={l.amountCents < 0 ? 'neg' : 'pos'}>{l.amountCents > 0 ? '+' : ''}{money(l.amountCents)}</span>
                    </div>
                  ))}
                </div>
              </div>
              <div className="chain-body">
                <dl className="kv">
                  <dt>Journal hash</dt><dd className="hash">{j.hash}</dd>
                  <dt>Posted by</dt><dd>{j.createdBy}</dd>
                </dl>
                {canReverse && j.kind !== 'reversal' && !j.reversedById ? (
                  <button type="button" className="btn btn-sm btn-danger mt" onClick={onReverse}><Icon name="undo" size={14} /> Reverse journal</button>
                ) : null}
              </div>
            </div>
          </td>
        </tr>
      ) : null}
    </>
  );
}

/** Reversal dialog: posts the exact opposite with a reason. The key lives as long as the dialog targets one journal. */
function Reverse({ journal, onClose, onReversed }) {
  const [reason, setReason] = useState('');
  const [key, setKey] = useState(newRequestKey);
  const [forId, setForId] = useState(null);
  const action = useAction();
  const toast = useToast();

  // A new target journal gets a fresh key and form; retries against the same journal keep the key.
  if (journal && journal.id !== forId) {
    setForId(journal.id);
    setKey(newRequestKey());
    setReason('');
    action.reset();
  }

  /** Submits the reversal. */
  async function submit(e) {
    e.preventDefault();
    const r = await action.run(() => api(`/api/transactions/${journal.id}/reverse`, { method: 'POST', body: { reason: reason.trim(), requestKey: key } }));
    if (r) {
      toast(`Reversed #${journal.id} with journal #${r.transaction.id}`);
      setForId(null);
      onReversed();
    }
  }

  return (
    <Dialog open={!!journal} onClose={onClose} title={journal ? `Reverse journal #${journal.id}` : 'Reverse'}
            subtitle="Posts the exact opposite legs. The original stays in history."
            footer={(
              <>
                <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
                <Button type="submit" form="reverse-form" className="btn btn-danger" busy={action.busy}>Reverse</Button>
              </>
            )}>
      {journal ? (
        <form id="reverse-form" className="form" onSubmit={submit}>
          <Notice error={action.error} />
          <div className="summary">
            {journal.legs.map((l) => (
              <div key={l.accountId} className="summary-row">
                <span className="mono">{l.accountNumber}</span>
                <span className={l.amountCents > 0 ? 'neg' : 'pos'}>{l.amountCents > 0 ? '' : '+'}{money(-l.amountCents)}</span>
              </div>
            ))}
          </div>
          <label className="field">
            Reason <span className="hint">3-100 characters, recorded in the audit trail</span>
            <input value={reason} minLength={3} maxLength={100} required autoFocus onChange={(e) => setReason(e.target.value)} />
          </label>
        </form>
      ) : null}
      {journal ? <p className="muted small">Hash {shortHash(journal.hash)}</p> : null}
    </Dialog>
  );
}
