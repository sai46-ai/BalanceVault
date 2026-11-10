import { useState } from 'react';
import { api, money, newRequestKey } from '../api.js';
import { Button, Empty, Icon, Notice, PageHead, shortHash, useAction, useLoad, useToast } from '../ui.jsx';

const AMOUNT = /^\d{1,7}(\.\d{1,2})?$/;
const ACCOUNT_NUMBER = /^BV\d{10}$/;
const MODES = [
  { id: 'deposit', label: 'Deposit', icon: 'down', perms: ['money:deposit'] },
  { id: 'withdraw', label: 'Withdraw', icon: 'up', perms: ['money:withdraw'] },
  { id: 'transfer', label: 'Transfer', icon: 'swap', perms: ['money:transfer_any', 'money:transfer_own'] },
];

/** "125.5" -> 12550 as an exact integer (string arithmetic, no floats). Null when the format is wrong. */
function toCents(amount) {
  if (!AMOUNT.test(amount)) return null;
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
}

/** One screen for deposits, withdrawals and transfers, with a live preview of the double-entry legs. */
export default function Money({ can, go }) {
  const list = useLoad(() => api('/api/accounts'), []);
  const modes = MODES.filter((m) => can(...m.perms));
  const [mode, setMode] = useState(modes[0]?.id);
  // One idempotency key per mode, replaced only after that mode succeeds: a retry after a timeout or error can
  // never post twice, even if the user switches modes in between.
  const [keys, setKeys] = useState(() => Object.fromEntries(MODES.map((m) => [m.id, newRequestKey()])));
  const [accountId, setAccountId] = useState('');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [memo, setMemo] = useState('');
  const [receipt, setReceipt] = useState(null);
  const action = useAction();
  const toast = useToast();

  const all = list.data?.accounts || [];
  const accounts = all.filter((a) => a.kind !== 'system' && a.status === 'active');
  const from = accounts.find((a) => String(a.id) === accountId);
  const cents = toCents(amount);
  const after = from && cents !== null ? from.balanceCents + (mode === 'deposit' ? cents : -cents) : null;
  const target = all.find((a) => a.accountNumber === to);

  /** Validates, posts the movement for the current mode, then shows a receipt. */
  async function submit(e) {
    e.preventDefault();
    setReceipt(null);
    if (cents === null || cents === 0) {
      await action.run(() => Promise.reject(new Error('Amount must be like 125 or 125.50 and more than zero')));
      return;
    }
    if (mode === 'transfer' && !ACCOUNT_NUMBER.test(to)) {
      await action.run(() => Promise.reject(new Error('Destination must look like BV0123456789')));
      return;
    }
    const body = mode === 'transfer'
      ? { fromAccountId: Number(accountId), toAccountNumber: to, amount, memo: memo.trim() || null, requestKey: keys[mode] }
      : { accountId: Number(accountId), amount, memo: memo.trim() || null, requestKey: keys[mode] };
    const r = await action.run(() => api(`/api/transactions/${mode}`, { method: 'POST', body }));
    if (r) {
      setKeys((k) => ({ ...k, [mode]: newRequestKey() }));
      setReceipt(r);
      toast(r.replayed ? `Already posted as journal #${r.transaction.id} (no money moved twice)` : `Posted journal #${r.transaction.id}`);
      setAmount('');
      setMemo('');
      list.reload();
    }
  }

  /** Switches mode and clears the previous result. */
  function pick(id) {
    setMode(id);
    setReceipt(null);
    action.reset();
  }

  if (modes.length === 0) return <Empty icon="lock" title="No money permissions">Your role cannot move money.</Empty>;
  const label = MODES.find((m) => m.id === mode).label;

  return (
    <section className="view">
      <PageHead eyebrow="Banking" title="Move money"
                lead="Every movement posts one balanced journal. Retries are safe: each attempt carries an idempotency key." />

      <div className="segmented" role="tablist" aria-label="Movement type">
        {modes.map((m) => (
          <button key={m.id} type="button" role="tab" aria-selected={mode === m.id} className={mode === m.id ? 'on' : ''} onClick={() => pick(m.id)}>
            <Icon name={m.icon} size={15} /> {m.label}
          </button>
        ))}
      </div>

      <div className="grid grid-main">
        <form className="card" onSubmit={submit}>
          <div className="card-head">
            <div>
              <h2>{label}</h2>
              <p>{mode === 'deposit' ? 'Cash in to a customer account' : mode === 'withdraw' ? 'Cash out from a customer account' : 'Between two customer accounts'}</p>
            </div>
          </div>
          <div className="card-body form">
            <Notice error={list.error || action.error} />
            <label className="field">
              Amount
              <div className="amount">
                <span className="currency">$</span>
                <input inputMode="decimal" placeholder="0.00" value={amount} maxLength={10} required aria-label="Amount in US dollars"
                       onChange={(e) => setAmount(e.target.value.trim())} />
              </div>
            </label>
            <div className="form-row">
              <label className="field">
                {mode === 'transfer' ? 'From account' : 'Account'}
                <select value={accountId} required onChange={(e) => setAccountId(e.target.value)}>
                  <option value="">Choose…</option>
                  {accounts.map((a) => (
                    <option key={a.id} value={a.id}>{a.accountNumber} · {a.ownerName} · {money(a.balanceCents)}</option>
                  ))}
                </select>
              </label>
              {mode === 'transfer' ? (
                <label className="field">
                  To account number
                  <input className="mono" list="bv-accounts" placeholder="BV0123456789" value={to} maxLength={12} required
                         onChange={(e) => setTo(e.target.value.trim().toUpperCase())} />
                  <datalist id="bv-accounts">
                    {accounts.filter((a) => String(a.id) !== accountId).map((a) => <option key={a.id} value={a.accountNumber}>{a.ownerName}</option>)}
                  </datalist>
                </label>
              ) : null}
            </div>
            <label className="field">
              Memo <span className="hint">Optional, shown on statements</span>
              <input value={memo} maxLength={140} onChange={(e) => setMemo(e.target.value)} />
            </label>
            <Button type="submit" className="btn btn-primary btn-block" busy={action.busy}>
              <Icon name={MODES.find((m) => m.id === mode).icon} size={16} /> {label}{cents ? ` ${money(cents)}` : ''}
            </Button>
          </div>
        </form>

        <div className="stack">
          <div className="card card-body">
            <h3>Preview</h3>
            <div className="summary mt">
              <div className="summary-row"><span className="muted">{mode === 'transfer' ? 'From' : 'Account'}</span><span className="mono">{from ? from.accountNumber : '—'}</span></div>
              {mode === 'transfer' ? (
                <div className="summary-row"><span className="muted">To</span><span className="mono">{to || '—'}{target?.ownerName ? ` · ${target.ownerName}` : ''}</span></div>
              ) : (
                <div className="summary-row"><span className="muted">Counterparty</span><span className="mono">SYSTEM-CASH</span></div>
              )}
              <div className="summary-row"><span className="muted">Balance now</span><span>{from ? money(from.balanceCents) : '—'}</span></div>
              <div className="summary-row"><span className="muted">Balance after</span>
                <span className={after !== null && after < 0 ? 'neg' : ''}>{after !== null ? money(after) : '—'}</span></div>
              <div className="summary-row summary-total"><span>Amount</span><span>{cents ? money(cents) : '$0.00'}</span></div>
            </div>
            {after !== null && after < 0 ? <div className="mt"><Notice info="This would overdraw the account; the server will refuse it." /></div> : null}
          </div>

          <div className="card card-body">
            <h3>How it posts</h3>
            <div className="legs mt">
              <div className="leg"><span>{mode === 'deposit' ? 'SYSTEM-CASH' : from?.accountNumber || 'source'}</span><span className="neg">−{cents ? money(cents) : '$0.00'}</span></div>
              <div className="leg"><span>{mode === 'deposit' ? from?.accountNumber || 'account' : mode === 'withdraw' ? 'SYSTEM-CASH' : to || 'destination'}</span><span className="pos">+{cents ? money(cents) : '$0.00'}</span></div>
              <div className="leg muted"><span>Sum</span><span>$0.00</span></div>
            </div>
            <p className="muted small mt">The journal is appended and hash-linked to the previous one; it can only be corrected by a reversal.</p>
          </div>

          {receipt ? (
            <div className="card card-body">
              <div className="notice ok"><Icon name="check" /><div>
                {receipt.replayed ? 'Already posted earlier — nothing moved twice.' : 'Posted to the ledger.'}
                <span className="req">journal #{receipt.transaction.id} · {shortHash(receipt.transaction.hash)}</span>
              </div></div>
              <button type="button" className="btn btn-sm btn-ghost mt" onClick={() => go('transactions')}>View in transactions <Icon name="chevron" size={14} /></button>
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
}
