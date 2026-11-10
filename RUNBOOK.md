# BalanceVault Runbook & Operations Guide

Welcome to **BalanceVault**. This runbook covers local startup, container execution, role testing workflows, and deployment guidelines.

---

## 1. Quick Start with Docker (Recommended)

BalanceVault runs as a containerized stack comprising:
- **PostgreSQL 17** (database engine with immutable append-only triggers)
- **Spring Boot 4.1.2 + React 19** (monolithic container serving both the API and bundled static SPA)

### Prerequisites
- Docker Engine / Docker Desktop (v20+ / Docker Compose v2+)

### Run Commands
```bash
# 1. Start the stack (Postgres + BalanceVault)
docker compose up --build

# 2. Access the application in your browser
http://localhost:8081
```

> **Port note:** Docker maps host port `8081` to container port `8080` to prevent collisions with any existing local services running on port `8080`.

---

## 2. Configuration & Environment Variables

Settings are configured via `.env` in the project root:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | Internal HTTP port |
| `SESSION_TTL_MINUTES` | `30` | Inactivity session timeout |
| `COOKIE_SECURE` | `false` | Set to `true` when behind HTTPS/TLS |
| `INVITE_TTL_HOURS` | `72` | Expiration time for single-use invite tokens |
| `ADMIN_USERNAME` | `admin` | Bootstrap administrator username |
| `ADMIN_PASSWORD` | `T@pestry@321` | Bootstrap administrator password |
| `DB_HOST` | `db` | Database host |
| `DB_PORT` | `5432` | Database port |
| `DB_NAME` | `balancevault` | Database schema name |
| `DB_USER` | `balancevault` | Database username |
| `DB_PASSWORD` | *(from LOCAL_PG_PASSWORD)* | Database password |

---

## 3. End-to-End Verification Walkthrough (5-Minute Smoke Test)

Follow this 5-step journey to verify all roles, separation of duties, and the cryptographic proof chain:

### Step 1: Sign in as Admin
- URL: `http://localhost:8081`
- Username: `admin`
- Password: `T@pestry@321` (from `.env`)
- Navigate to **People → Invite someone**:
  - Create an **operator** invitation (Name: *Alice Operator*).
  - Create a **customer** invitation (Name: *Bob Customer*).
- Copy each one-time invitation link displayed.

### Step 2: Accept Invitations (Incognito / New Window)
- Open the operator link (`http://localhost:8081/#/invite/<TOKEN>`):
  - Choose username: `alice_op`
  - Choose password: `Password@1234` (must meet 12+ chars, upper, lower, number, symbol rule)
- Open the customer link:
  - Choose username: `bob_cust`
  - Choose password: `Password@1234`

### Step 3: Bank Operations (as Operator `alice_op`)
- Sign in as `alice_op`.
- Go to **Accounts → Open account**:
  - Select Customer: `Bob Customer (bob_cust)`
  - Kind: `checking`
  - Note the generated account number (e.g. `BV0123456789`).
- Go to **Move money → Deposit**:
  - Deposit `$100.00` into Bob's account (Cash Box pays Bob's account).

### Step 4: Customer Experience (as Customer `bob_cust`)
- Sign in as `bob_cust`.
- Notice the balance shows `$100.00`.
- Statements show the exact ledger movement.
- Separation of duties check: Customer can only see their own accounts and cannot access People or Audit tabs.

### Step 5: Ledger Integrity Check (as Admin `admin`)
- Sign back in as `admin`.
- Navigate to **Proof & audit → Verify now**:
  - Confirms:
    1. Every journal hash matches its contents.
    2. Every chain link connects unbroken to its predecessor (`prev_hash`).
    3. Every journal balances to zero (`legs sum = 0`).
    4. Entire ledger sums to zero (`customer funds = -system cash`).
  - View the Head Hash and Audit Trail.

---

## 4. Running the Client Locally for Development

To run the React frontend in Vite dev mode with hot reload:

```bash
cd client
npm install
npm run dev
```

Vite proxies `/api` calls directly to the Spring Boot backend (`http://localhost:8080` or `http://localhost:8081`).

---

## 5. Deployment Guide

### Deploying with Docker (Cloud Run, Railway, Render, or VPS)
1. Build the production container:
   ```bash
   docker build -t balancevault .
   ```
2. Set environment variables on your platform:
   - `COOKIE_SECURE=true` (behind HTTPS reverse proxy)
   - `ADMIN_USERNAME` and strong `ADMIN_PASSWORD`
   - Point `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` to your managed PostgreSQL instance.
3. Expose port `8080`.
