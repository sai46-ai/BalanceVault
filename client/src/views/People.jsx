import { useRef, useState } from 'react';
import { api, when } from '../api.js';
import { Avatar, Button, Dialog, Empty, Icon, Notice, PageHead, Pill, Skeleton, useAction, useLoad, useToast } from '../ui.jsx';

const ROLES = [
  { id: 'customer', blurb: 'Own accounts, transfers' },
  { id: 'operator', blurb: 'Opens accounts, moves money' },
  { id: 'auditor', blurb: 'Read-only, verifies ledger' },
  { id: 'admin', blurb: 'Manages people' },
];

/** Invitations for staff with customers:onboard; user administration (tab) for users:manage. */
export default function People({ user, can }) {
  const invitations = useLoad(() => api('/api/invitations'), []);
  const [tab, setTab] = useState('invitations');
  const [inviting, setInviting] = useState(false);
  const admin = can('users:manage');
  const rows = invitations.data?.invitations || [];

  return (
    <section className="view">
      <PageHead eyebrow="Administration" title="People" lead="Onboarding is by single-use invitation only. Links expire and only their hash is stored.">
        <button type="button" className="btn btn-primary" onClick={() => setInviting(true)}><Icon name="mail" size={15} /> Invite someone</button>
      </PageHead>

      <div className="card card-flush">
        <div className="tabs" role="tablist">
          <button type="button" role="tab" aria-selected={tab === 'invitations'} className={tab === 'invitations' ? 'tab on' : 'tab'} onClick={() => setTab('invitations')}>
            Invitations <span className="muted">· {rows.filter((i) => i.state === 'open').length} open</span>
          </button>
          {admin ? (
            <button type="button" role="tab" aria-selected={tab === 'users'} className={tab === 'users' ? 'tab on' : 'tab'} onClick={() => setTab('users')}>Users</button>
          ) : null}
        </div>
        {tab === 'invitations' || !admin
          ? <InvitationTable list={invitations} />
          : <Users selfId={user.id} />}
      </div>

      <Invite open={inviting} canStaff={admin} onClose={() => setInviting(false)} onCreated={invitations.reload} />
    </section>
  );
}

/** Invitation dialog. The single-use link is shown exactly once, then forgotten when the dialog closes. */
function Invite({ open, canStaff, onClose, onCreated }) {
  const [displayName, setDisplayName] = useState('');
  const [role, setRole] = useState('customer');
  const [link, setLink] = useState(null);
  const linkRef = useRef(null);
  const action = useAction();
  const toast = useToast();

  /** Submits the invitation. */
  async function submit(e) {
    e.preventDefault();
    const r = await action.run(() => api('/api/invitations', { method: 'POST', body: { displayName: displayName.trim(), role } }));
    if (r) {
      setLink(`${window.location.origin}/#/invite/${r.token}`);
      setDisplayName('');
      onCreated();
    }
  }

  /** Copies the link; the Clipboard API needs HTTPS, so on plain HTTP the text is selected for Ctrl+C instead. */
  async function copy() {
    linkRef.current?.select();
    if (navigator.clipboard && window.isSecureContext) {
      try {
        await navigator.clipboard.writeText(link);
        toast('Invitation link copied');
        return;
      } catch (err) {
        toast(`Copy failed: ${err.message}. Press Ctrl+C instead.`, 'error');
        return;
      }
    }
    toast('Link selected. Press Ctrl+C to copy.');
  }

  /** Closes and wipes the one-time link from memory. */
  function close() {
    setLink(null);
    setRole('customer');
    action.reset();
    onClose();
  }

  return (
    <Dialog open={open} onClose={close} title={link ? 'Invitation ready' : 'Invite someone'}
            subtitle={link ? 'Send this link privately. It is shown only once.' : 'They choose their own username and password.'}
            footer={link ? <button type="button" className="btn btn-primary" onClick={close}>Done</button> : (
              <>
                <button type="button" className="btn btn-ghost" onClick={close}>Cancel</button>
                <Button type="submit" form="invite-form" className="btn btn-primary" busy={action.busy}>Create invitation</Button>
              </>
            )}>
      {link ? (
        <>
          <Notice ok="Invitation created. Anyone with this link can claim the account until it expires." />
          <div className="copy-box">
            <input ref={linkRef} className="input" readOnly value={link} aria-label="Invitation link" onFocus={(e) => e.target.select()} />
            <button type="button" className="btn" onClick={copy}><Icon name="copy" size={15} /> Copy</button>
          </div>
        </>
      ) : (
        <form id="invite-form" className="form" onSubmit={submit}>
          <Notice error={action.error} />
          <label className="field">
            Full name
            <input value={displayName} maxLength={80} required autoFocus onChange={(e) => setDisplayName(e.target.value)} />
          </label>
          <div className="field">
            Role
            <div className="role-grid" role="radiogroup" aria-label="Role">
              {ROLES.filter((r) => canStaff || r.id === 'customer').map((r) => (
                <button key={r.id} type="button" role="radio" aria-checked={role === r.id} className={role === r.id ? 'role-opt on' : 'role-opt'}
                        onClick={() => setRole(r.id)}>
                  <strong>{r.id}</strong>
                  <span>{r.blurb}</span>
                </button>
              ))}
            </div>
          </div>
        </form>
      )}
    </Dialog>
  );
}

/** Invitation list with revoke for open ones. */
function InvitationTable({ list }) {
  const action = useAction();
  const toast = useToast();
  const rows = list.data?.invitations || [];

  /** Revokes one open invitation. */
  async function revoke(id) {
    const r = await action.run(() => api(`/api/invitations/${id}/revoke`, { method: 'POST' }));
    if (r) {
      toast('Invitation revoked');
      list.reload();
    }
  }

  if (list.loading && !list.data) return <Skeleton lines={5} />;
  return (
    <>
      <Notice error={list.error || action.error} />
      {rows.length === 0 ? <Empty icon="mail" title="No invitations yet">Invite a customer or colleague to get started.</Empty> : (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Invitee</th><th>Role</th><th>Created by</th><th>Expires</th><th>State</th><th aria-label="Actions" /></tr></thead>
            <tbody>
              {rows.map((i) => (
                <tr key={i.id}>
                  <td>
                    <div className="cell-main">
                      <Avatar name={i.displayName} id={i.id} small />
                      <div><div className="strong">{i.displayName}</div><div className="cell-sub">created {when(i.createdAt)}</div></div>
                    </div>
                  </td>
                  <td className="small">{i.role}</td>
                  <td>{i.createdBy}</td>
                  <td className="small">{when(i.expiresAt)}</td>
                  <td><Pill value={i.state} /></td>
                  <td className="num">
                    {i.state === 'open' ? (
                      <button type="button" className="btn btn-sm btn-ghost" disabled={action.busy} onClick={() => revoke(i.id)}>Revoke</button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

/** Every user, with role changes, disable/enable and lockout clearing (enable resets the lockout). */
function Users({ selfId }) {
  const users = useLoad(() => api('/api/users'), []);
  const action = useAction();
  const toast = useToast();

  /** Sends a role and/or status patch for one user. */
  async function update(id, patch, message) {
    const r = await action.run(() => api(`/api/users/${id}`, { method: 'PATCH', body: patch }));
    if (r) {
      toast(message);
      users.reload();
    }
  }

  if (users.loading && !users.data) return <Skeleton lines={5} />;
  return (
    <>
      <Notice error={users.error || action.error} />
      <div className="table-wrap">
        <table className="table">
          <thead><tr><th>User</th><th>Role</th><th>Status</th><th>Joined</th><th aria-label="Actions" /></tr></thead>
          <tbody>
            {(users.data?.users || []).map((u) => {
              const usable = u.status === 'active' && !u.locked;
              return (
                <tr key={u.id}>
                  <td>
                    <div className="cell-main">
                      <Avatar name={u.displayName} id={u.id} small />
                      <div><div className="strong">{u.displayName}</div><div className="cell-sub mono">{u.username}</div></div>
                    </div>
                  </td>
                  <td>
                    {u.id === selfId ? <span className="small">{u.role}</span> : (
                      <select className="input" value={u.role} disabled={action.busy} aria-label={`Role for ${u.username}`}
                              onChange={(e) => update(u.id, { role: e.target.value }, `Role changed to ${e.target.value}`)}>
                        {ROLES.map((r) => <option key={r.id} value={r.id}>{r.id}</option>)}
                      </select>
                    )}
                  </td>
                  <td><div className="row"><Pill value={u.status} />{u.locked ? <Pill value="locked" /> : null}</div></td>
                  <td className="small">{when(u.createdAt)}</td>
                  <td className="num">
                    {u.id === selfId ? <span className="muted small">you</span> : (
                      <button type="button" className={usable ? 'btn btn-sm btn-ghost' : 'btn btn-sm'} disabled={action.busy}
                              onClick={() => update(u.id, { status: usable ? 'disabled' : 'active' }, usable ? 'User disabled' : 'User enabled')}>
                        {usable ? 'Disable' : (u.locked ? 'Unlock' : 'Enable')}
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}
