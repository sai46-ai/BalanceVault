import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { Button, Icon, Notice, Skeleton, useAction } from '../ui.jsx';

const USERNAME = /^[a-z0-9._-]{3,32}$/;
const PASSWORD = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{12,128}$/;

/** Brand panel shared by sign-in and onboarding (hidden on narrow screens). */
function AuthArt() {
  return (
    <aside className="auth-art" aria-hidden="true">
      <div className="brand">
        <span className="brand-mark"><Icon name="lock" size={19} /></span>
        <div>
          <div className="brand-name">BalanceVault</div>
          <div className="brand-sub">Provable ledger</div>
        </div>
      </div>
      <div>
        <h1>Money movement with a ledger you can <em>prove</em>.</h1>
        <p className="lead">Every deposit, withdrawal and transfer is double-entry, append-only and sealed into a
          SHA-256 hash chain, so history cannot be rewritten without it showing.</p>
      </div>
      <div className="features">
        <div className="feature"><Icon name="shield" /><strong>Role-based access</strong><span>Admin, operator, auditor and customer, with separation of duties.</span></div>
        <div className="feature"><Icon name="mail" /><strong>Invite-only onboarding</strong><span>Single-use links that expire.</span></div>
        <div className="feature"><Icon name="hash" /><strong>Tamper-evident</strong><span>Journals and audit events are hash-chained.</span></div>
        <div className="feature"><Icon name="check" /><strong>Always balanced</strong><span>Every journal sums to zero.</span></div>
      </div>
      <div className="chain-art">
        <span className="blk">#0 genesis</span><span className="lnk" />
        <span className="blk">#1 a3f9…</span><span className="lnk" />
        <span className="blk">#2 7c01…</span><span className="lnk" />
        <span className="blk">#3 e54b…</span>
      </div>
    </aside>
  );
}

/** Password input with a show/hide toggle. */
function PasswordField({ label, value, onChange, autoComplete, hint, children }) {
  const [show, setShow] = useState(false);
  return (
    <label className="field">
      {label}
      <div className="pw">
        <input type={show ? 'text' : 'password'} autoComplete={autoComplete} value={value} maxLength={128} required
               onChange={(e) => onChange(e.target.value)} />
        <button type="button" className="btn-icon" aria-label={show ? 'Hide password' : 'Show password'} onClick={() => setShow(!show)}>
          <Icon name={show ? 'eyeOff' : 'eye'} size={16} />
        </button>
      </div>
      {children}
      {hint ? <span className="hint">{hint}</span> : null}
    </label>
  );
}

/** Username/password sign-in. The server rate-limits by IP and locks the account after 5 failures. */
export function Login({ onSignedIn, error }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const action = useAction();

  /** Submits credentials, then reloads the session (which fetches the CSRF token). */
  async function submit(e) {
    e.preventDefault();
    const ok = await action.run(() => api('/api/auth/login', { method: 'POST', body: { username: username.trim(), password } }));
    setPassword('');
    if (ok) await onSignedIn();
  }

  return (
    <div className="auth">
      <AuthArt />
      <main className="auth-panel">
        <div className="auth-card">
          <div>
            <div className="eyebrow">Secure sign-in</div>
            <h2>Welcome back</h2>
            <p className="muted">Sign in to manage accounts and money movement.</p>
          </div>
          <form className="card" onSubmit={submit}>
            <div className="card-body form">
              <Notice error={action.error || error} />
              <label className="field">
                Username
                <input autoComplete="username" value={username} maxLength={64} required autoFocus
                       onChange={(e) => setUsername(e.target.value)} />
              </label>
              <PasswordField label="Password" value={password} onChange={setPassword} autoComplete="current-password" />
              <Button type="submit" className="btn btn-primary btn-block" busy={action.busy}>
                {action.busy ? 'Signing in…' : 'Sign in'}
              </Button>
            </div>
          </form>
          <div className="auth-foot">
            <span><Icon name="mail" size={14} /> Have an invitation? Open its link to set up your account.</span>
          </div>
        </div>
      </main>
    </div>
  );
}

/** 0-4 score from the same rules the server enforces (length, cases, digit, symbol). */
function strength(pw) {
  if (!pw) return 0;
  const classes = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((r) => r.test(pw)).length;
  if (pw.length < 8) return 1;
  if (pw.length < 12) return Math.min(2, classes);
  return classes === 4 ? 4 : Math.max(2, classes - 1);
}

/** Invitation acceptance: confirms who the invite is for, then lets the invitee choose a username and password. */
export function Onboarding({ token, onDone }) {
  const [invite, setInvite] = useState(null);
  const [lookupError, setLookupError] = useState(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [localError, setLocalError] = useState(null);
  const action = useAction();

  useEffect(() => {
    api('/api/onboarding/lookup', { method: 'POST', body: { token } })
      .then((r) => setInvite(r.invitation))
      .catch(setLookupError);
  }, [token]);

  /** Validates locally with the same rules as the server, then accepts the invitation. */
  async function submit(e) {
    e.preventDefault();
    setLocalError(null);
    if (!USERNAME.test(username)) return setLocalError(new Error('Username must be 3-32 lower-case letters, digits, . _ or -'));
    if (!PASSWORD.test(password)) return setLocalError(new Error('Password must be 12+ characters with upper, lower, digit and symbol'));
    if (password.toLowerCase().includes(username)) return setLocalError(new Error('Password must not contain the username'));
    if (password !== confirm) return setLocalError(new Error('Passwords do not match'));
    await action.run(() => api('/api/onboarding/accept', { method: 'POST', body: { token, username, password } }),
      'Your account is ready. Sign in with your new username and password.');
    setPassword('');
    setConfirm('');
  }

  const score = strength(password);

  return (
    <div className="auth">
      <AuthArt />
      <main className="auth-panel">
        <div className="auth-card">
          <div>
            <div className="eyebrow">Invitation</div>
            <h2>Set up your account</h2>
            {invite ? (
              <p className="muted">
                For <strong>{invite.displayName}</strong> as <strong>{invite.role}</strong> · expires {new Date(invite.expiresAt).toLocaleString()}
              </p>
            ) : null}
          </div>
          <div className="card">
            <div className="card-body form">
              <Notice error={lookupError} ok={action.ok} />
              {!invite && !lookupError ? <Skeleton lines={4} /> : null}
              {invite && !action.ok ? (
                <form className="form" onSubmit={submit}>
                  <Notice error={localError || action.error} />
                  <label className="field">
                    Choose a username
                    <input autoComplete="username" value={username} maxLength={32} required autoFocus
                           onChange={(e) => setUsername(e.target.value.toLowerCase())} />
                    <span className="hint">3-32 characters: a-z, 0-9, . _ -</span>
                  </label>
                  <PasswordField label="Choose a password" value={password} onChange={setPassword} autoComplete="new-password"
                                 hint="12+ characters with upper, lower, digit and symbol">
                    <div className={`strength s${score}`} aria-hidden="true"><i /><i /><i /><i /></div>
                  </PasswordField>
                  <PasswordField label="Confirm password" value={confirm} onChange={setConfirm} autoComplete="new-password" />
                  <Button type="submit" className="btn btn-primary btn-block" busy={action.busy}>Create my account</Button>
                </form>
              ) : null}
              {action.ok || lookupError ? (
                <button type="button" className="btn btn-primary btn-block" onClick={onDone}>Go to sign in</button>
              ) : null}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
