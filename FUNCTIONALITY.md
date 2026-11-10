# BalanceVault: how it works, step by step

A plain-language guide to what the app does and why. For install and run commands, see `runbook.md`.

---

## 1. What BalanceVault is, in one sentence
It's a mini bank back office. People get accounts, money moves between them, and every action is recorded in a way that can't be secretly changed later.

---

## 2. The four types of people (roles)

Each user has exactly one role. The role decides which menu items they see and which actions the server allows.

| Role | Think of them as… | Can do | Cannot do |
|---|---|---|---|
| **Admin** | Office manager | Invite anyone, change roles, disable or unlock users, open and freeze accounts, view everything, run the proof check | **Move money** |
| **Operator** | Bank teller | Invite customers, open accounts, freeze or close accounts, deposit, withdraw, transfer, reverse mistakes | Manage staff or view the audit log |
| **Auditor** | Inspector | View all accounts, transactions and the audit log, run the proof check | **Change anything** |
| **Customer** | Account holder | View **only their own** accounts and transactions, transfer from their own accounts | See anyone else's data |

**Why the split?** No single person can do everything. The admin who creates users can't move money, and the operator who moves money can't create staff. This is called *separation of duties*. It's all defined in one file: `src/main/java/com/tapestry/balancevault/security/Roles.java`.

---

## 3. How someone joins (onboarding)

There's no public sign-up page. You can only join by invitation.

1. An admin or operator clicks **People → Invite someone**, then types a name and picks a role. Operators can only invite customers.
2. The app creates a **one-time link** like `…/#/invite/AbC123…`.
   - The link is shown **once**. Copy it, because closing the dialog forgets it.
   - The server stores only a scrambled fingerprint (hash) of it, never the link itself.
3. The person opens the link and picks a username and a strong password (12+ characters with upper case, lower case, a digit and a symbol).
4. The link is now used up. It also expires after a set time (`INVITE_TTL_HOURS` in `.env`) and can be revoked early.

---

## 4. Signing in and staying safe

- **Login:** username and password.
- **Too many wrong passwords:** after **5 failures** the account locks for **15 minutes**. An admin can unlock it under **People → Users**.
- **Too many attempts from one computer:** limited to 10 every 15 minutes per IP address.
- **Session:** a secure cookie that only remembers *who you are*. Your role is re-checked on every click, so if an admin disables you, you're out immediately.
- **Forged requests:** every action that changes something needs a secret token that only the real app page has. This is CSRF protection.
- **Logout:** ends the session on the server.

---

## 5. Accounts

- Each account has a number like **BV0123456789**, an owner (a customer) and a status:
  - 🟢 **active**: normal.
  - 🔵 **frozen**: no money can move.
  - ⚪ **closed**: finished.
- **The balance is never typed in or stored.** It's always calculated by adding up the account's money movements. So a balance can't be quietly edited.
- Click an account card to open its **statement**: every movement, with the running balance after each one.

---

## 6. Moving money (the ledger)

### Every movement has two sides
Like real accounting (double-entry), money always leaves one place and arrives in another, so the two sides always add up to **zero**.

| Action | Who | What happens |
|---|---|---|
| **Deposit** $100 | Operator | Bank's cash box **−100**, customer account **+100** |
| **Withdraw** $40 | Operator | Customer account **−40**, cash box **+40** |
| **Transfer** $25 | Operator, or a customer from their own account | Account A **−25**, account B **+25** |
| **Reverse** | Operator | Creates an equal and opposite movement that cancels the original |

The "cash box" is a built-in system account called `SYSTEM-CASH`.

### Safety rules
- **Nothing is ever edited or deleted.** A mistake is fixed by a *reversal*, so the history shows both the mistake and the fix. The database itself blocks edits and deletes.
- **No double payments.** Each money form carries a unique ID. If you click Submit twice or the network retries, the payment still happens only once.
- **No rounding errors.** Money is stored as whole cents ($12.34 is stored as 1234).
- You can't overdraw an account or move money from a frozen or closed account.

---

## 7. The "proof": why the ledger can be trusted

Every transaction gets a **fingerprint** (a SHA-256 hash). Each fingerprint includes the **previous** one, like links in a chain:

```
[Tx 1 🔒a1b2] → [Tx 2 🔒c3d4 (includes a1b2)] → [Tx 3 🔒e5f6 (includes c3d4)] → …
```

If anyone changes even one character of an old transaction, its fingerprint changes and every later link breaks.

**Proof & audit → Verify now** recalculates everything and checks that:
- ✅ every fingerprint and chain link is correct,
- ✅ each transaction's two sides add up to zero,
- ✅ the whole ledger adds up to zero.

It then shows the **head hash** (the newest fingerprint). Write it down. If someone later rewrites history, the head hash won't match anymore.

---

## 8. The audit log ("who did what")

A second chain of the same kind records **every important action**: logins (including failed ones), invitations, account changes, money movements, permission denials and verifications.

- Each entry stores the time, who did it, what they did, the target, and whether it succeeded, failed or was denied.
- **It never stores passwords, tokens or email addresses.**
- Admins and auditors can filter it by outcome or search it under **Proof & audit**.

---

## 9. The screens (left menu)

| Screen | What it shows | Who sees it |
|---|---|---|
| **Dashboard** | Total balances, a 30-day money in/out chart, activity by type, recent activity | Everyone (customers see only their own data) |
| **Accounts** | Account cards, search and filter, "Open account", statements | Everyone (own accounts only for customers) |
| **Move money** | Deposit, withdraw and transfer forms | Operators and customers (customers: transfer only) |
| **Transactions** | Every journal, with its sides, hash and reverse button | Everyone (own only for customers) |
| **People** | Invitations tab, plus a Users tab for admins | Admins and operators |
| **Proof & audit** | Verify button, head hashes, audit timeline | Admins and auditors |

The sun/moon button in the top bar switches between light and dark themes.

---

## 10. How it's built

```
Browser (React app)  ──/api calls──►  Spring Boot server (Java)  ──►  PostgreSQL database
  client/src/views/*                  web/ controllers                  tables: users, invitations,
  client/src/ui.jsx                   security/ (roles, login)          accounts, journals,
                                      ledger/ + audit/ (the chains)     entries, audit_log
```

- The React app is built into the Java app, so it all runs as **one container**, plus the database container.
- Settings come from `.env`. The first admin is created from `ADMIN_USERNAME` and `ADMIN_PASSWORD`.

---

## 11. Try it yourself in 5 minutes

1. Open **http://localhost:8081** and log in as `admin`, using `ADMIN_PASSWORD` from `.env` (also listed in `runbook.md`).
2. **People → Invite someone**: create an operator, then a customer. Open each link in a private window to finish signing up.
3. As the **operator**: **Accounts → Open account** for the customer, then **Move money → Deposit $100**.
4. As the **customer**: you'll see your $100. Try a transfer.
5. As the **admin**: go to **Proof & audit → Verify now**. You should see ✅ "History is intact".

---

## ⚠️ Things to know

| Severity | Note |
|---|---|
| HIGH | Passwords are stored in plain text, per the demo standard. Don't use this with real users as-is. |
| MEDIUM | Error messages include technical details (stack traces). That helps debugging but reveals internal code to anyone who triggers an error. |
| MEDIUM | The client has no `package-lock.json`, so builds aren't perfectly reproducible. |
| INFO | To deploy on Rubi, storage must move from PostgreSQL to SQLite or Snowflake first. |
