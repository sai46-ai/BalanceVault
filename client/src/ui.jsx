import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';

// Stroke icons as path data (24x24). Inline SVG keeps us inside the CSP (no icon font / CDN).
const PATHS = {
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
  wallet: 'M3 7a2 2 0 0 1 2-2h13v4M3 7v11a2 2 0 0 0 2 2h15V9H5a2 2 0 0 1-2-2zm13 7h.01',
  swap: 'M7 4 3 8l4 4M3 8h14M17 20l4-4-4-4m4 4H7',
  list: 'M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01',
  users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm13 10v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8',
  shield: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10zm-3-10 2 2 4-4',
  logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4m7 14 5-5-5-5m5 5H9',
  sun: 'M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0-16v2m0 18v2M4.2 4.2l1.4 1.4m12.8 12.8 1.4 1.4M1 12h2m18 0h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4',
  moon: 'M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z',
  menu: 'M3 6h18M3 12h18M3 18h18',
  plus: 'M12 5v14M5 12h14',
  x: 'M18 6 6 18M6 6l12 12',
  check: 'M20 6 9 17l-5-5',
  alert: 'M12 9v4m0 4h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z',
  info: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zm0-6v-4m0-4h.01',
  search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zm10 2-4.3-4.3',
  copy: 'M20 9h-9a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2zM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1',
  eye: 'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8zm11 3a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  eyeOff: 'M17.9 17.9A10 10 0 0 1 12 20c-7 0-11-8-11-8a18 18 0 0 1 5.1-5.9M9.9 4.2A9 9 0 0 1 12 4c7 0 11 8 11 8a18 18 0 0 1-2.2 3.2M1 1l22 22',
  down: 'M12 5v14m-7-7 7 7 7-7',
  up: 'M12 19V5m-7 7 7-7 7 7',
  undo: 'M3 7v6h6M3.5 13A9 9 0 1 0 6 6.3L3 9',
  lock: 'M5 11h14v10H5zm3 0V7a4 4 0 0 1 8 0v4',
  link: 'M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7',
  refresh: 'M23 4v6h-6M1 20v-6h6m14.5-5A9 9 0 0 0 6 5.6L1 10m22 4-5 4.4A9 9 0 0 1 2.5 15',
  chevron: 'm9 18 6-6-6-6',
  card: 'M2 7a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2zm0 3h20',
  hash: 'M4 9h16M4 15h16M10 3 8 21m8-18-2 18',
  clock: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zm0-16v6l4 2',
  key: 'M21 2l-2 2m-7.6 7.6a5.5 5.5 0 1 1-7.8 7.8 5.5 5.5 0 0 1 7.8-7.8zm0 0L15.5 7.5m0 0 3 3L22 7l-3-3m-3.5 3.5L19 4',
  mail: 'M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm18 2-10 7L2 6',
  trend: 'm23 6-9.5 9.5-5-5L1 18M17 6h6v6',
  snow: 'M12 2v20M4.9 4.9l14.2 14.2M2 12h20M4.9 19.1 19.1 4.9',
};

/** One stroke icon by name; aria-hidden because every icon sits next to a text label. */
export function Icon({ name, size = 18, className = 'icon' }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d={PATHS[name] || PATHS.info} />
    </svg>
  );
}

/** Shows an ApiError (message + requestId for support) or a success note. Text only; React escapes it. */
export function Notice({ error, ok, info }) {
  if (error) {
    return (
      <div className="notice error" role="alert">
        <Icon name="alert" />
        <div>
          {error.message}
          {error.requestId ? <span className="req">request {error.requestId}</span> : null}
        </div>
      </div>
    );
  }
  if (ok) return <div className="notice ok" role="status"><Icon name="check" /><div>{ok}</div></div>;
  return info ? <div className="notice info"><Icon name="info" /><div>{info}</div></div> : null;
}

/** Loads data with `fn` whenever `deps` change; exposes data, error, loading and reload. */
export function useLoad(fn, deps) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(fn, deps);
  const reload = useCallback(() => {
    setState((s) => ({ ...s, loading: true }));
    load()
      .then((data) => setState({ data, error: null, loading: false }))
      .catch((error) => setState({ data: null, error, loading: false }));
  }, [load]);
  useEffect(reload, [reload]);
  return { ...state, reload };
}

/** Wraps a submit handler with busy/error/ok state so forms cannot double-submit. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [ok, setOk] = useState(null);
  const run = useCallback(async (fn, okMessage) => {
    setBusy(true);
    setError(null);
    setOk(null);
    try {
      const result = await fn();
      if (okMessage) setOk(typeof okMessage === 'function' ? okMessage(result) : okMessage);
      return result;
    } catch (err) {
      setError(err);
      return null;
    } finally {
      setBusy(false);
    }
  }, []);
  const reset = useCallback(() => { setError(null); setOk(null); }, []);
  return { busy, error, ok, run, setOk, reset };
}

/** Coloured pill for statuses and outcomes; the class is reduced to [a-z_] so data can never inject classes. */
export function Pill({ value }) {
  return <span className={`pill pill-${String(value).toLowerCase().replace(/[^a-z_]/g, '')}`}>{value}</span>;
}

/**
 * Native <dialog> shown with showModal(): the browser supplies the focus trap, Esc-to-close and inert background.
 * `variant` is "modal" (centred) or "drawer" (right edge). Esc and backdrop clicks call onClose.
 */
export function Dialog({ open, onClose, title, subtitle, children, footer, variant = 'modal' }) {
  const ref = useRef(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);

  return (
    <dialog ref={ref} className={variant} aria-label={title}
            onCancel={(e) => { e.preventDefault(); onClose(); }}
            onClick={(e) => { if (e.target === ref.current) onClose(); }}>
      {open ? (
        <div className="sheet">
          <div className="sheet-head">
            <div>
              <h2>{title}</h2>
              {subtitle ? <p>{subtitle}</p> : null}
            </div>
            <button type="button" className="btn-icon" aria-label="Close" onClick={onClose}><Icon name="x" /></button>
          </div>
          <div className="sheet-body">{children}</div>
          {footer ? <div className="sheet-foot">{footer}</div> : null}
        </div>
      ) : null}
    </dialog>
  );
}

const ToastContext = createContext(() => {});

/** Holds the toast stack; each toast auto-dismisses after 5 s and can be closed by hand. */
export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const seq = useRef(0);
  const dismiss = useCallback((id) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const push = useCallback((message, tone = 'ok') => {
    seq.current += 1;
    const id = seq.current;
    setToasts((t) => [...t.slice(-3), { id, message, tone }]);
    setTimeout(() => dismiss(id), 5000);
  }, [dismiss]);

  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.tone === 'error' ? 'error' : 'ok'}`}>
            <Icon name={t.tone === 'error' ? 'alert' : 'check'} />
            <div className="toast-body">{t.message}</div>
            <button type="button" className="btn-icon btn-sm" aria-label="Dismiss" onClick={() => dismiss(t.id)}><Icon name="x" size={14} /></button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

/** Returns push(message, tone) for transient confirmations ("ok" | "error"). */
export function useToast() {
  return useContext(ToastContext);
}

/** Page title block with optional eyebrow, lead text and right-aligned actions. */
export function PageHead({ eyebrow, title, lead, children }) {
  return (
    <div className="page-head">
      <div>
        {eyebrow ? <div className="eyebrow">{eyebrow}</div> : null}
        <h1>{title}</h1>
        {lead ? <p className="lead">{lead}</p> : null}
      </div>
      {children ? <div className="page-actions">{children}</div> : null}
    </div>
  );
}

/** KPI tile. `feature` renders the dark navy hero variant. */
export function Stat({ label, value, foot, icon, feature }) {
  return (
    <div className={feature ? 'card stat feature' : 'card stat'}>
      <div className="stat-top">
        <span className="stat-label">{label}</span>
        {icon ? <span className="stat-icon"><Icon name={icon} size={17} /></span> : null}
      </div>
      <div className="stat-value">{value}</div>
      {foot ? <div className="stat-foot">{foot}</div> : null}
    </div>
  );
}

/** Friendly empty state. */
export function Empty({ icon = 'info', title, children }) {
  return (
    <div className="empty">
      <span className="empty-icon"><Icon name={icon} size={22} /></span>
      <h3>{title}</h3>
      {children ? <p>{children}</p> : null}
    </div>
  );
}

/** Shimmering placeholder lines while data loads. */
export function Skeleton({ lines = 4 }) {
  return (
    <div className="skeleton-stack" aria-busy="true" aria-label="Loading">
      {Array.from({ length: lines }, (_, i) => <div key={i} className="skeleton line" />)}
    </div>
  );
}

/** Up to two initials from a display name. */
export function initials(name) {
  return String(name || '?').trim().split(/\s+/).slice(0, 2).map((w) => w[0] || '').join('').toUpperCase() || '?';
}

/** Initials avatar; the tone is derived from a numeric id so it is stable per person. */
export function Avatar({ name, id = 0, small }) {
  const tone = Math.abs(Number(id) || 0) % 5;
  return <span className={`avatar${small ? ' sm' : ''}${tone ? ` tone-${tone}` : ''}`} aria-hidden="true">{initials(name)}</span>;
}

/** Button that shows a spinner while busy. */
export function Button({ busy, children, className = 'btn', ...rest }) {
  return (
    <button type="button" {...rest} className={className} disabled={busy || rest.disabled}>
      {busy ? <span className="spinner" aria-hidden="true" /> : null}
      {children}
    </button>
  );
}

/** Shortens a hex hash to its first and last characters for display. */
export function shortHash(h) {
  return h && h.length > 16 ? `${h.slice(0, 8)}…${h.slice(-6)}` : h || '';
}
