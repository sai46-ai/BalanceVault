import { useState } from 'react';
import { api, money, when } from '../api.js';
import { Button, Dialog, Empty, Icon, Notice, PageHead, Pill, Skeleton, useAction, useLoad, useToast } from '../ui.jsx';

const FILTERS = ['all', 'active', 'frozen', 'closed'];

/** "BV0123456789" -> "BV01 2345 6789" for card display. */
function grouped(n) {
  return `${n.slice(0, 4)} ${n.slice(4, 8)} ${n.slice(8)}`;
}

/** Account cards with derived balances; staff can open accounts, and a card opens its statement drawer. */
export default function Accounts({ can }) {
  const list = useLoad(() => api('/api/accounts'), []);
  const [selected, setSelected] = useState(null);
  const [opening, setOpening] = useState(false);
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState('all');
  const accounts = list.data?.accounts || [];
  const needle = q.trim().toLowerCase();
  // Client-side narrowing of the (server-capped) list already on screen; the server does all money arithmetic.
  const shown = accounts.filter((a) => (filter === 'all' || a.status === filter)
    && (!needle || [a.accountNumber, a.ownerName, a.nickname].some((v) => v && v.toLowerCase().includes(needle))));

  return (
    <section className="view">
      <PageHead eyebrow="Banking" title="Accounts" lead="Balances are computed live from ledger entries, never stored.">
        {can('accounts:open') ? (
          <button type="button" className="btn btn-primary" onClick={() => setOpening(true)}><Icon name="plus" size={15} /> Open account</button>
        ) : null}
      </PageHead>
      <Notice error={list.error} />

      <div className="card card-flush">
        <div className="toolbar">
          <div className="search">
            <Icon name="search" size={15} />
            <input className="input" type="search" placeholder="Search number, owner or nickname" value={q} maxLength={60}
                   aria-label="Search accounts" onChange={(e) => setQ(e.target.value)} />
          </div>
          <div className="segmented" role="group" aria-label="Filter by status">
            {FILTERS.map((f) => (
              <button key={f} type="button" className={filter === f ? 'on' : ''} aria-pressed={filter === f} onClick={() => setFilter(f)}>
                {f[0].toUpperCase() + f.slice(1)}
              </button>
            ))}
          </div>
          <span className="spacer" />
          <span className="muted small">{shown.length} of {accounts.length}</span>
        </div>
        <div className="card-body">
          {list.loading && !list.data ? <div className="acct-grid">{[0, 1, 2].map((i) => <div key={i} className="skeleton block" />)}</div> : null}
          {!list.loading && shown.length === 0 ? (
            <Empty icon="wallet" title={accounts.length ? 'No matching accounts' : 'No accounts yet'}>
              {accounts.length ? 'Try a different search or filter.' : 'Accounts appear here once opened.'}
            </Empty>
          ) : null}
          <div className="acct-grid">
            {shown.map((a) => (
              <button key={a.id} type="button" className={`acct ${a.kind} ${a.status}`} onClick={() => setSelected(a.id)}
                      aria-label={`Open statement for ${a.accountNumber}`}>
                <div className="acct-top">
                  <span className="acct-kind">{a.kind}{a.nickname ? ` · ${a.nickname}` : ''}</span>
                  <span className="chip-art" />
                </div>
                <div className="acct-balance"><small>Balance</small>{money(a.balanceCents)}</div>
                <div className="acct-bottom">
                  <div>
                    <div className="acct-number">{grouped(a.accountNumber)}</div>
                    <div className="acct-owner">{a.ownerName || 'System'}</div>
                  </div>
                  <span className="acct-status">{a.status}</span>
                </div>
              </button>
            ))}
          </div>
        </div>
      </div>

      {can('accounts:open') ? (
        <OpenAccount open={opening} onClose={() => setOpening(false)} onOpened={() => { setOpening(false); list.reload(); }} />
      ) : null}
      <Dialog variant="drawer" open={selected !== null} onClose={() => setSelected(null)} title="Statement"
              subtitle="Newest first, with the running balance after each entry">
        {selected !== null ? <Statement key={selected} id={selected} can={can} onChanged={list.reload} /> : null}
      </Dialog>
    </section>
  );
}

/** Dialog that opens a checking or savings account for an active customer. */
function OpenAccount({ open, onClose, onOpened }) {
  const customers = useLoad(() => (open ? api('/api/customers') : Promise.resolve(null)), [open]);
  const [ownerId, setOwnerId] = useState('');
  const [kind, setKind] = useState('checking');
  const [nickname, setNickname] = useState('');
  const action = useAction();
  const toast = useToast();

  /** Submits the open request; on success toasts the new number and refreshes the list. */
  async function submit(e) {
    e.preventDefault();
    const r = await action.run(() => api('/api/accounts', {
      method: 'POST', body: { ownerId: Number(ownerId), kind, nickname: nickname.trim() || null },
    }));
    if (r) {
      toast(`Opened ${r.account.accountNumber}`);
      setNickname('');
      setOwnerId('');
      onOpened();
    }
  }

  return (
    <Dialog open={open} onClose={onClose} title="Open an account" subtitle="The account starts at a zero balance."
            footer={(
              <>
                <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
                <Button type="submit" form="open-account" className="btn btn-primary" busy={action.busy}>Open account</Button>
              </>
            )}>
      <form id="open-account" className="form" onSubmit={submit}>
        <Notice error={customers.error || action.error} />
        <label className="field">
          Customer
          <select value={ownerId} required onChange={(e) => setOwnerId(e.target.value)}>
            <option value="">Choose a customer…</option>
            {(customers.data?.customers || []).map((c) => (
              <option key={c.id} value={c.id}>{c.displayName} ({c.username})</option>
            ))}
          </select>
        </label>
        <div className="field">
          Kind
          <div className="segmented" role="group" aria-label="Account kind">
            {['checking', 'savings'].map((k) => (
              <button key={k} type="button" className={kind === k ? 'on' : ''} aria-pressed={kind === k} onClick={() => setKind(k)}>
                <Icon name={k === 'checking' ? 'card' : 'wallet'} size={15} /> {k[0].toUpperCase() + k.slice(1)}
              </button>
            ))}
          </div>
        </div>
        <label className="field">
          Nickname <span className="hint">Optional, up to 40 characters</span>
          <input value={nickname} maxLength={40} onChange={(e) => setNickname(e.target.value)} />
        </label>
      </form>
    </Dialog>
  );
}

/** Drawer body: balance hero, optional status control, and the keyset-paged statement. */
function Statement({ id, can, onChanged }) {
  const first = useLoad(() => api(`/api/accounts/${id}/statement`, { query: { limit: 50 } }), [id]);
  const [pages, setPages] = useState([]);
  const more = useAction();
  const account = first.data?.account;
  const lines = [...(first.data?.lines || []), ...pages];

  /** Loads the next older page using the last entry id as the cursor. */
  async function older() {
    const r = await more.run(() => api(`/api/accounts/${id}/statement`, { query: { before: lines[lines.length - 1].entryId, limit: 50 } }));
    if (r) setPages((p) => [...p, ...r.lines]);
  }

  if (first.error) return <Notice error={first.error} />;
  if (!account) return <Skeleton lines={6} />;

  return (
    <>
      <div className={`acct ${account.kind} ${account.status}`}>
        <div className="acct-top">
          <span className="acct-kind">{account.kind}{account.nickname ? ` · ${account.nickname}` : ''}</span>
          <span className="chip-art" />
        </div>
        <div className="acct-balance"><small>Current balance</small>{money(account.balanceCents)}</div>
        <div className="acct-bottom">
          <div>
            <div className="acct-number">{grouped(account.accountNumber)}</div>
            <div className="acct-owner">{account.ownerName || 'System'} · opened {when(account.createdAt)}</div>
          </div>
          <span className="acct-status">{account.status}</span>
        </div>
      </div>

      {can('accounts:status') && account.kind !== 'system' && account.status !== 'closed' ? (
        <StatusControl account={account} onChanged={() => { setPages([]); first.reload(); onChanged(); }} />
      ) : null}

      <div className="card card-flush">
        <div className="card-head"><h3>Entries</h3><span className="muted small">{lines.length} shown</span></div>
        <Notice error={more.error} />
        {lines.length === 0 ? <Empty icon="list" title="No entries yet" /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>When</th><th>Description</th><th className="num">Amount</th><th className="num">Balance</th></tr></thead>
              <tbody>
                {lines.map((l) => (
                  <tr key={l.entryId}>
                    <td className="small">{when(l.createdAt)}</td>
                    <td>
                      <div className="strong">{l.memo || l.kind}</div>
                      <div className="cell-sub"><Pill value={l.kind} /> journal #{l.journalId}</div>
                    </td>
                    <td className={l.amountCents < 0 ? 'num neg' : 'num pos'}>{l.amountCents > 0 ? '+' : ''}{money(l.amountCents)}</td>
                    <td className="num strong">{money(l.balanceAfterCents)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {lines.length > 0 && lines.length % 50 === 0 ? (
          <div className="table-foot"><Button className="btn btn-sm" busy={more.busy} onClick={older}>Load older</Button></div>
        ) : null}
      </div>
    </>
  );
}

/** Freeze / unfreeze / close with a mandatory reason (written to the audit trail). Closing needs a zero balance. */
function StatusControl({ account, onChanged }) {
  const options = ['active', 'frozen', 'closed'].filter((s) => s !== account.status);
  const [status, setStatus] = useState(options[0]);
  const [reason, setReason] = useState('');
  const action = useAction();
  const toast = useToast();

  /** Posts the status change. */
  async function submit(e) {
    e.preventDefault();
    const r = await action.run(() => api(`/api/accounts/${account.id}/status`, { method: 'POST', body: { status, reason: reason.trim() } }));
    if (r) {
      toast(`Account is now ${status}`);
      setReason('');
      onChanged();
    }
  }

  return (
    <form className="card card-body form" onSubmit={submit}>
      <div className="split">
        <h3>Change status</h3>
        <div className="segmented" role="group" aria-label="New status">
          {options.map((s) => (
            <button key={s} type="button" className={status === s ? 'on' : ''} aria-pressed={status === s} onClick={() => setStatus(s)}>
              <Icon name={s === 'frozen' ? 'snow' : s === 'closed' ? 'lock' : 'check'} size={14} /> {s === 'active' ? 'Unfreeze' : s === 'frozen' ? 'Freeze' : 'Close'}
            </button>
          ))}
        </div>
      </div>
      <Notice error={action.error} info={status === 'closed' ? 'Closing is permanent and requires a zero balance.' : null} />
      <div className="row">
        <input className="input" placeholder="Reason (recorded in the audit trail)" value={reason} minLength={3} maxLength={100} required
               aria-label="Reason" onChange={(e) => setReason(e.target.value)} />
        <Button type="submit" className={status === 'closed' ? 'btn btn-danger' : 'btn btn-primary'} busy={action.busy}>Apply</Button>
      </div>
    </form>
  );
}
