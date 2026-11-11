# PROMPTS.md: AI Engineering Log & Verification Audit

This log documents the iterative collaboration with AI, recording initial assumptions, places where the AI was wrong or had to be corrected, problem diagnosis, and an honest account of finished versus unfinished components.

---

## 1. Initial Prompt & Task Objective
- **Source:** Provided starter archive `proj-20261110T145713Z-1-001.zip` from placement assessment.
- **Goal:** Unpack, verify, structure, and incrementally commit the BalanceVault banking back-office application into GitHub (`https://github.com/sai46-ai/BalanceVault.git`), ensuring end-to-end correctness, strict Git commit history, and deployment readiness.

---

## 2. Where the AI Was Wrong & How It Was Resolved

### Incident 1: Spring Boot 4.1.2 & Jackson 3 Skepticism
- **What the AI got wrong:** During the initial inspection of `pom.xml`, the AI questioned whether `<version>4.1.2</version>` for `spring-boot-starter-parent` and imports of `tools.jackson.databind.json.JsonMapper` were hallucinated or invalid package references, initially considering downgrading to Spring Boot 3.3.x.
- **Why it was wrong:** Spring Boot 4.1.2 (released late 2026) is the official baseline using Spring Framework 7, Jakarta EE 11, and Jackson 3 (`tools.jackson`). Downgrading would have broken modern standard dependencies.
- **What was done:** Verified Maven central and Java 21 compatibility. Retained the Spring Boot 4.1.2 configuration without altering the core framework specification.

### Incident 2: The `application.properties.txt` File Extension Trap
- **What happened:** In `src/main/resources/`, the configuration file was saved as `application.properties.txt`.
- **Diagnosis:** Spring Boot searches classpath roots for `application.properties` or `application.yml`. A file named `.properties.txt` is completely ignored by Spring Boot's default auto-configuration. On boot, Spring Boot fails with `Could not resolve placeholder 'bv.admin-username'` and database connection properties are never loaded.
- **What was done:** Explicitly created `src/main/resources/application.properties` with the proper environment bindings (`${PORT}`, `${DB_HOST}`, etc.) and committed the fix as an isolated configuration commit.

### Incident 3: Missing `.gitignore` & Risk of Dirty Commits
- **What happened:** The initial project archive had no `.gitignore`. Running `npm install` or `mvn package` would pollute the Git tree with thousands of untracked files (`node_modules/`, `target/`, `dist/`).
- **What was done:** Created a disciplined `.gitignore` before performing builds or copying untracked files, ensuring only clean source code and assets are tracked.

### Incident 4: Missing `package-lock.json`
- **What happened:** `FUNCTIONALITY.md` flagged that the frontend lacked a `package-lock.json`, risking non-reproducible builds across different machines.
- **What was done:** Executed `npm install` inside `client/` using Node.js v24, validated that `client/package-lock.json` was generated, verified Vite build succeeds (`npm run build` in 248ms), and locked the file into version control.

### Incident 5: Missing `runbook.md`
- **What happened:** `FUNCTIONALITY.md` repeatedly pointed candidates and operators to `runbook.md` for run instructions and admin credentials, but no `runbook.md` existed in the archive.
- **What was done:** Authored a complete, production-grade `RUNBOOK.md` outlining the architecture, Docker Compose startup, 5-step role verification walkthrough, and Cloud deployment steps.

---

## 3. Commit Architecture & Progression

Rather than a single monolithic dump, the repository was committed incrementally in logical, reviewable stages:
1. `build: initialize project configuration, Docker environment, and gitignore`
2. `feat(db): add double-entry ledger schema, immutable triggers, and migrations`
3. `fix(config): configure application properties for environment and datasource binding`
4. `feat(core): implement domain models, security filters, rate limiter, and error handling`
5. `feat(ledger): implement double-entry ledger, audit hash chain, and rule validation tests`
6. `feat(api): implement REST controllers and application bootstrap`
7. `feat(client): set up React SPA foundation, styles, and UI components`
8. `feat(client): implement role-based banking views and dashboard`
9. `docs: add operational RUNBOOK.md and lock reproducible client dependencies`
10. `docs: create PROMPTS.md tracking AI collaboration and verification`
11. `docs: add comprehensive project README with architecture, security model, and quickstart`
12. `test: add input validation rules and cryptographic security test suites`
13. `ci: add GitHub Actions workflow for automated test and build verification`
14. `build: optimize Dockerfile client stage with npm ci and package-lock`
15. `docs: update PROMPTS.md with test expansion, CI automation, and Docker caching`

---

## 4. Honest Status: Finished vs Unfinished

### What is Fully Working & Complete:
- **Double-Entry Ledger Engine:** Every journal transaction balances to zero across at least two legs; account balances are dynamically computed from entries (never statically stored or mutable).
- **Cryptographic Hash Chaining:** SHA-256 chain links every journal and every audit event; `GET /api/ledger/verify` recomputes the chain, verifies zero balances, and reports head hashes.
- **Database Immutability:** PostgreSQL triggers (`bv_append_only`) forbid `UPDATE`, `DELETE`, and `TRUNCATE` operations on ledger journals, ledger entries, and audit logs. Mistakes must be corrected through equal and opposite reversal journals.
- **Role-Based Access Control (RBAC):** Strict separation of duties across 4 roles:
  - `Admin`: Can manage users, freeze accounts, verify proofs, but **cannot move money**.
  - `Operator`: Can open accounts, deposit, withdraw, transfer, and reverse journals, but **cannot manage staff or read audit logs**.
  - `Auditor`: Read-only access to all transactions, accounts, and audit logs; can run verification proofs; **cannot mutate state**.
  - `Customer`: Scoped strictly to own accounts and transactions; can transfer funds between active accounts.
- **Onboarding Security:** Single-use invitation tokens hashed via SHA-256; token shown once to inviter; rate-limited acceptance with username/password complexity enforcement.
- **Idempotency Protection:** Client-generated lower-case UUID request keys ensure retried network requests replay the existing transaction without double spending.
- **UI & Frontend:** Pure React 19 with Vite, custom zero-dependency SVG charts (cash flow and donut), dark/light theme switching, and responsive navigation drawer.

### What is Intentionally Left Unfinished / Demo Limitations:
- **Plain-text passwords:** Per `FUNCTIONALITY.md` demo specifications, passwords are currently compared in constant time rather than bcrypt/Argon2. In a production rollout, password hashing with salts must be implemented.
- **In-memory Rate Limiting:** The `LoginRateLimiter` tracks IP windows in an in-memory `ConcurrentHashMap`. For a multi-node horizontal deployment, this must be backed by a distributed cache (e.g. Redis) or database table.
- **Stack Traces in Error Responses:** `GlobalErrorHandler` currently includes full stack traces in error JSON to assist development. Production deployment requires stripping internal stack traces to prevent code disclosure.
