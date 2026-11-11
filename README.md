# BalanceVault 🏦🔒

> A provable, tamper-evident double-entry banking back-office system with strict Role-Based Access Control (RBAC), hash-chained journals, append-only database triggers, and invite-only onboarding.

[![CI Build & Test](https://github.com/sai46-ai/BalanceVault/actions/workflows/ci.yml/badge.svg)](https://github.com/sai46-ai/BalanceVault/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-19.3-blue.svg)](https://react.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue.svg)](https://www.postgresql.org/)

---

## 📌 What is BalanceVault?

BalanceVault is a secure mini bank back-office application designed around one core invariant: **money movement must be mathematically provable, immutable, and strictly partitioned by role.**

- **Double-Entry Bookkeeping:** Every transaction consists of balanced legs summing to zero. Account balances are never directly typed or stored; they are derived live by summing an account's entries.
- **Cryptographic Proof Chain:** Every journal entry and every audit event is sealed into a SHA-256 hash chain ($H_n = \text{SHA-256}(H_{n-1} \parallel \text{data})$). Tampering with any historical record breaks the entire subsequent chain.
- **Database Immutability:** PostgreSQL triggers (`bv_append_only`) enforce at the engine level that `journals`, `entries`, and `audit_log` cannot be edited (`UPDATE`), deleted (`DELETE`), or cleared (`TRUNCATE`). Mistakes are corrected solely through explicit reversal journals.
- **Separation of Duties (RBAC):** No single person holds universal privileges. Admins manage users but cannot move money; Operators move money but cannot manage staff; Auditors inspect but cannot alter records.

---

## 👥 Role Permissions Matrix

Permissions are enforced strictly on the server-side via `AccessGuard` and re-evaluated per request:

| Capability | Admin | Operator | Auditor | Customer |
|---|:---:|:---:|:---:|:---:|
| **Invite Staff (Admin/Op/Auditor)** | ✅ | ❌ | ❌ | ❌ |
| **Invite Customers** | ✅ | ✅ | ❌ | ❌ |
| **Manage / Unlock Users** | ✅ | ❌ | ❌ | ❌ |
| **Open Accounts (Checking / Savings)** | ✅ | ✅ | ❌ | ❌ |
| **Freeze / Close Accounts** | ✅ | ✅ | ❌ | ❌ |
| **Deposit & Withdraw Cash** | ❌ | ✅ | ❌ | ❌ |
| **Transfer Between Any Accounts** | ❌ | ✅ | ❌ | ❌ |
| **Reverse Transactions** | ❌ | ✅ | ❌ | ❌ |
| **Transfer From Own Accounts** | ❌ | ❌ | ❌ | ✅ |
| **View Audit Trail** | ✅ | ❌ | ✅ | ❌ |
| **Run Proof Verification** | ✅ | ❌ | ✅ | ❌ |
| **View Account Statements** | All | All | All | Own Only |

---

## 🏗️ Architecture & Technology Stack

```
   ┌─────────────────────────────────────────────────────────┐
   │             React 19 SPA (Vite Bundler)                 │
   │  - Zero external chart libs (Pure SVG cash flow/donut)  │
   │  - Responsive drawer navigation & Dark/Light theme      │
   └───────────────────────────┬─────────────────────────────┘
                               │ JSON / HTTP (HttpOnly Cookies + CSRF)
   ┌───────────────────────────▼─────────────────────────────┐
   │           Spring Boot 4.1.2 (Java 21)                   │
   │  - REST Controllers (Accounts, Auth, Transactions)      │
   │  - SessionAuthFilter & RequestContextFilter             │
   │  - Double-Entry Ledger Engine & SHA-256 Chain Verifier  │
   │  - Advisory Locks (Race-free serial journal appends)    │
   └───────────────────────────┬─────────────────────────────┘
                               │ JDBC / Flyway Migrations
   ┌───────────────────────────▼─────────────────────────────┐
   │             PostgreSQL 17 Database                      │
   │  - Tables: users, invitations, accounts, journals,      │
   │           entries, audit_log                            │
   │  - Immutable Triggers (bv_append_only)                  │
   └─────────────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start (Docker)

The fastest and most reliable way to run BalanceVault locally:

```bash
# 1. Clone the repository
git clone https://github.com/sai46-ai/BalanceVault.git
cd BalanceVault

# 2. Build and start the stack
docker compose up --build

# 3. Open your browser
http://localhost:8081
```

Default administrator credentials (from `.env`):
- **Username:** `admin`
- **Password:** `T@pestry@321`

---

## 🧪 Testing & Verification

### Running Unit Tests
```bash
# Backend test suite (Double-entry rules, hashing, input validation)
mvn test
```

### Running Frontend Dev Server
```bash
cd client
npm install
npm run dev
```

### Proof & Chain Verification
Navigate to **Proof & audit → Verify now** to verify that:
1. Every journal's legs sum to zero.
2. Every journal hash links unbroken to the previous hash back to genesis (`000...000`).
3. Total ledger credits equal total ledger debits across all customer and system accounts.
4. Audit trail hash chain is intact.

---

## 📚 Key Documentation

- [`FUNCTIONALITY.md`](./FUNCTIONALITY.md) — Step-by-step guide to business rules, accounts, and ledger mechanics.
- [`RUNBOOK.md`](./RUNBOOK.md) — Detailed operational runbook, role walkthroughs, and deployment options.
- [`PROMPTS.md`](./PROMPTS.md) — AI engineering log, problem diagnosis, corrections, and completed vs. unfinished feature disclosures.
