// Thin fetch wrapper. The session lives in an HttpOnly cookie; the CSRF token is kept in memory only (never storage).
let csrfToken = null;
let onSignedOut = () => {};

/** Registers the callback run when the server says the session is gone (401). */
export function setSignedOutHandler(fn) {
  onSignedOut = fn;
}

/** Remembers the CSRF token returned by /api/auth/me. */
export function setCsrf(token) {
  csrfToken = token;
}

/** Error carrying the server's message, status and requestId for display. */
export class ApiError extends Error {
  constructor(status, body) {
    super(body?.error || `Request failed (HTTP ${status})`);
    this.status = status;
    this.requestId = body?.requestId || null;
  }
}

/** Calls a same-origin API path. Writes send JSON with the CSRF header; every failure throws ApiError. */
export async function api(path, { method = 'GET', body, query } = {}) {
  const url = new URL(path, window.location.origin);
  Object.entries(query || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v));
  });
  const headers = { Accept: 'application/json' };
  if (method !== 'GET') {
    headers['Content-Type'] = 'application/json';
    if (csrfToken) headers['X-CSRF-TOKEN'] = csrfToken;
  }
  const res = await fetch(url.pathname + url.search, {
    method,
    headers,
    credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let data = null;
  try {
    data = await res.json();
  } catch (err) {
    throw new ApiError(res.status, { error: `Unexpected response from server: ${err.message}` });
  }
  if (!res.ok || data?.success === false) {
    if (res.status === 401 && !path.startsWith('/api/auth/login')) onSignedOut();
    throw new ApiError(res.status, data);
  }
  return data;
}

/**
 * Lower-case v4 UUID used as a money movement's idempotency key. Built from getRandomValues because
 * crypto.randomUUID only exists on HTTPS pages and the app is served over plain HTTP.
 */
export function newRequestKey() {
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Integer cents to a display string like "$1,234.56" (no floating-point arithmetic). */
export function money(cents) {
  const n = BigInt(cents);
  const neg = n < 0n;
  const abs = neg ? -n : n;
  const whole = (abs / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const frac = (abs % 100n).toString().padStart(2, '0');
  return `${neg ? '-' : ''}$${whole}.${frac}`;
}

/** ISO instant to a short local date-time. */
export function when(iso) {
  return iso ? new Date(iso).toLocaleString() : '';
}
