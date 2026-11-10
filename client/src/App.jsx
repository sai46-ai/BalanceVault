import { useCallback, useEffect, useState } from 'react';
import { api, setCsrf, setSignedOutHandler } from './api.js';
import { Avatar, Icon, Notice, Skeleton, ToastProvider } from './ui.jsx';
import { Login, Onboarding } from './views/Auth.jsx';
import Dashboard from './views/Dashboard.jsx';
import Accounts from './views/Accounts.jsx';
import Money from './views/Money.jsx';
import Transactions from './views/Transactions.jsx';
import People from './views/People.jsx';
import Proof from './views/Proof.jsx';

// Each screen shows only when the user holds one of its permissions; the server enforces the same checks.
const TABS = [
  { id: 'dashboard', group: 'Overview', label: 'Dashboard', icon: 'home', any: ['accounts:read_all', 'accounts:read_own'], View: Dashboard },
  { id: 'accounts', group: 'Banking', label: 'Accounts', icon: 'wallet', any: ['accounts:read_all', 'accounts:read_own'], View: Accounts },
  { id: 'money', group: 'Banking', label: 'Move money', icon: 'swap', any: ['money:deposit', 'money:withdraw', 'money:transfer_any', 'money:transfer_own'], View: Money },
  { id: 'transactions', group: 'Banking', label: 'Transactions', icon: 'list', any: ['transactions:read_all', 'transactions:read_own'], View: Transactions },
  { id: 'people', group: 'Administration', label: 'People', icon: 'users', any: ['customers:onboard', 'users:manage'], View: People },
  { id: 'proof', group: 'Administration', label: 'Proof & audit', icon: 'shield', any: ['audit:read', 'ledger:verify'], View: Proof },
];

/** Reads an invitation token from the URL fragment (#/invite/<token>); fragments never reach server logs. */
function inviteToken() {
  const m = window.location.hash.match(/^#\/invite\/([A-Za-z0-9_-]{43})$/);
  return m ? m[1] : null;
}

/** Theme preference (not a secret, so localStorage is fine); falls back to the OS setting. */
function initialTheme() {
  const saved = window.localStorage.getItem('bv-theme');
  if (saved === 'light' || saved === 'dark') return saved;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** Applies and persists the colour theme. */
function useTheme() {
  const [theme, setTheme] = useState(initialTheme);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    window.localStorage.setItem('bv-theme', theme);
  }, [theme]);
  return [theme, () => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))];
}

/** Root component: wraps everything in the toast provider. */
export default function App() {
  return (
    <ToastProvider>
      <Root />
    </ToastProvider>
  );
}

/** Loads the session, then renders login, onboarding or the signed-in workspace. */
function Root() {
  const [user, setUser] = useState(null);
  const [checking, setChecking] = useState(true);
  const [loadError, setLoadError] = useState(null);
  const [invite, setInvite] = useState(inviteToken());
  const [tab, setTab] = useState(null);
  const [navOpen, setNavOpen] = useState(false);
  const [theme, toggleTheme] = useTheme();

  // Session check: 401 simply means "not signed in"; anything else is shown as an error.
  const refreshMe = useCallback(async () => {
    try {
      const me = await api('/api/auth/me');
      setCsrf(me.csrfToken);
      setUser(me.user);
      setLoadError(null);
    } catch (err) {
      setUser(null);
      setCsrf(null);
      if (err.status !== 401) setLoadError(err);
    } finally {
      setChecking(false);
    }
  }, []);

  useEffect(() => {
    setSignedOutHandler(() => {
      setUser(null);
      setCsrf(null);
    });
    refreshMe();
    const onHash = () => setInvite(inviteToken());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, [refreshMe]);

  /** Ends the session server-side, then clears local state even if the server already forgot it. */
  async function logout() {
    try {
      await api('/api/auth/logout', { method: 'POST' });
    } catch (err) {
      if (err.status !== 401) setLoadError(err);
    }
    setCsrf(null);
    setUser(null);
    setTab(null);
  }

  if (checking) return <main className="center"><div className="card boot"><Skeleton lines={3} /></div></main>;

  if (invite && !user) {
    return (
      <Onboarding
        token={invite}
        onDone={() => {
          window.location.hash = '';
          setInvite(null);
        }}
      />
    );
  }

  if (!user) return <Login onSignedIn={refreshMe} error={loadError} />;

  const can = (...perms) => perms.some((p) => user.permissions.includes(p));
  const tabs = TABS.filter((t) => can(...t.any));
  const active = tabs.find((t) => t.id === tab) || tabs[0];
  const groups = [...new Set(tabs.map((t) => t.group))];

  /** Navigates to a screen the user may see (ignored otherwise) and closes the mobile drawer. */
  const go = (id) => {
    if (tabs.some((t) => t.id === id)) setTab(id);
    setNavOpen(false);
    window.scrollTo(0, 0);
  };

  return (
    <div className={navOpen ? 'app nav-open' : 'app'}>
      <aside className="sidebar" aria-label="Main navigation">
        <div className="brand">
          <span className="brand-mark"><Icon name="lock" size={19} /></span>
          <div>
            <div className="brand-name">BalanceVault</div>
            <div className="brand-sub">Provable ledger</div>
          </div>
        </div>

        {groups.map((g) => (
          <nav key={g} className="nav" aria-label={g}>
            <div className="nav-label">{g}</div>
            {tabs.filter((t) => t.group === g).map((t) => (
              <button key={t.id} type="button" className={t === active ? 'nav-item active' : 'nav-item'}
                      aria-current={t === active ? 'page' : undefined} onClick={() => go(t.id)}>
                <Icon name={t.icon} />
                {t.label}
              </button>
            ))}
          </nav>
        ))}

        <div className="sidebar-foot">
          <div className="seal">
            <strong><Icon name="link" size={14} /> Hash-chained</strong>
            Every movement is double-entry, append-only and sealed into a SHA-256 chain.
          </div>
          <div className="me">
            <Avatar name={user.displayName} id={user.id} />
            <div className="me-text">
              <div className="me-name">{user.displayName}</div>
              <div className="me-role">{user.role}</div>
            </div>
            <button type="button" className="btn-icon" aria-label="Sign out" title="Sign out" onClick={logout}>
              <Icon name="logout" />
            </button>
          </div>
        </div>
      </aside>
      {navOpen ? <div className="scrim" onClick={() => setNavOpen(false)} aria-hidden="true" /> : null}

      <div className="main">
        <header className="topbar">
          <button type="button" className="btn-icon menu-btn" aria-label="Open navigation" onClick={() => setNavOpen(true)}>
            <Icon name="menu" />
          </button>
          <div className="crumbs">
            <span>BalanceVault</span>
            <Icon name="chevron" size={14} />
            <strong>{active ? active.label : 'Home'}</strong>
          </div>
          <div className="topbar-actions">
            {active?.id !== 'money' && can('money:deposit', 'money:withdraw', 'money:transfer_any', 'money:transfer_own') ? (
              <button type="button" className="btn btn-primary btn-sm" onClick={() => go('money')}>
                <Icon name="swap" size={15} /> Move money
              </button>
            ) : null}
            <button type="button" className="btn-icon" onClick={toggleTheme}
                    aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}>
              <Icon name={theme === 'dark' ? 'sun' : 'moon'} />
            </button>
          </div>
        </header>
        <main className="content">
          <Notice error={loadError} />
          {active ? <active.View key={active.id} user={user} can={can} go={go} /> : <p>Your role has no screens assigned.</p>}
        </main>
      </div>
    </div>
  );
}
