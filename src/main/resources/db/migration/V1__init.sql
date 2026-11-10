-- BalanceVault schema. Money is integer cents (BIGINT) everywhere; no floating point.

CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  display_name  VARCHAR(80)  NOT NULL,
  -- Plain text per the internal demo-app standard; compared in constant time in AuthController.
  password      VARCHAR(128) NOT NULL,
  role          VARCHAR(16)  NOT NULL CHECK (role IN ('admin', 'operator', 'auditor', 'customer')),
  status        VARCHAR(16)  NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
  failed_logins INT          NOT NULL DEFAULT 0,
  locked_until  TIMESTAMPTZ,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  created_by    BIGINT REFERENCES users (id)
);

-- Onboarding invitations. Only the SHA-256 of the token is stored; the token itself is shown once to the inviter.
CREATE TABLE invitations (
  id           BIGSERIAL PRIMARY KEY,
  token_hash   CHAR(64)    NOT NULL UNIQUE,
  display_name VARCHAR(80) NOT NULL,
  role         VARCHAR(16) NOT NULL CHECK (role IN ('admin', 'operator', 'auditor', 'customer')),
  created_by   BIGINT      NOT NULL REFERENCES users (id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at   TIMESTAMPTZ NOT NULL,
  accepted_at  TIMESTAMPTZ,
  accepted_by  BIGINT REFERENCES users (id),
  revoked_at   TIMESTAMPTZ
);

CREATE TABLE accounts (
  id             BIGSERIAL PRIMARY KEY,
  account_number VARCHAR(16) NOT NULL UNIQUE,
  owner_id       BIGINT REFERENCES users (id),
  kind           VARCHAR(16) NOT NULL CHECK (kind IN ('checking', 'savings', 'system')),
  nickname       VARCHAR(40),
  status         VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'frozen', 'closed')),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by     BIGINT REFERENCES users (id),
  -- Customer accounts always have an owner; the system cash account never does.
  CHECK ((kind = 'system') = (owner_id IS NULL))
);
CREATE UNIQUE INDEX accounts_one_system ON accounts (kind) WHERE kind = 'system';

-- One row per balanced money movement. id is the chain position (1, 2, 3 ...), assigned under an advisory lock.
CREATE TABLE journals (
  id                  BIGINT PRIMARY KEY CHECK (id > 0),
  kind                VARCHAR(16) NOT NULL CHECK (kind IN ('deposit', 'withdrawal', 'transfer', 'reversal')),
  memo                VARCHAR(140),
  idempotency_key     VARCHAR(36) NOT NULL UNIQUE,
  reverses_journal_id BIGINT UNIQUE REFERENCES journals (id),
  created_by          BIGINT      NOT NULL REFERENCES users (id),
  created_at          TIMESTAMPTZ NOT NULL,
  prev_hash           CHAR(64)    NOT NULL,
  hash                CHAR(64)    NOT NULL UNIQUE
);

-- The legs of each journal. Legs of one journal always sum to zero (enforced in LedgerService, proven by /api/ledger/verify).
CREATE TABLE entries (
  id           BIGSERIAL PRIMARY KEY,
  journal_id   BIGINT NOT NULL REFERENCES journals (id),
  account_id   BIGINT NOT NULL REFERENCES accounts (id),
  amount_cents BIGINT NOT NULL CHECK (amount_cents <> 0),
  UNIQUE (journal_id, account_id)
);
CREATE INDEX entries_by_account ON entries (account_id, id) INCLUDE (amount_cents);

-- Hash-chained audit trail of security-relevant actions (logins, onboarding, role changes, denials, money).
CREATE TABLE audit_log (
  id          BIGINT PRIMARY KEY CHECK (id > 0),
  at          TIMESTAMPTZ NOT NULL,
  actor_id    BIGINT REFERENCES users (id),
  action      VARCHAR(64) NOT NULL,
  target_type VARCHAR(32),
  target_id   VARCHAR(64),
  outcome     VARCHAR(16) NOT NULL CHECK (outcome IN ('success', 'failure', 'denied')),
  detail      TEXT,
  request_id  VARCHAR(36),
  prev_hash   CHAR(64)    NOT NULL,
  hash        CHAR(64)    NOT NULL UNIQUE
);

-- History is immutable: corrections are new reversal journals, never edits.
CREATE FUNCTION bv_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER journals_append_only BEFORE UPDATE OR DELETE ON journals FOR EACH ROW EXECUTE FUNCTION bv_append_only();
CREATE TRIGGER entries_append_only BEFORE UPDATE OR DELETE ON entries FOR EACH ROW EXECUTE FUNCTION bv_append_only();
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION bv_append_only();
CREATE TRIGGER journals_no_truncate BEFORE TRUNCATE ON journals FOR EACH STATEMENT EXECUTE FUNCTION bv_append_only();
CREATE TRIGGER entries_no_truncate BEFORE TRUNCATE ON entries FOR EACH STATEMENT EXECUTE FUNCTION bv_append_only();
CREATE TRIGGER audit_no_truncate BEFORE TRUNCATE ON audit_log FOR EACH STATEMENT EXECUTE FUNCTION bv_append_only();

-- The bank side of every deposit and withdrawal. Its balance is the negative of all customer money held.
INSERT INTO accounts (account_number, owner_id, kind, nickname, status)
VALUES ('SYSTEM-CASH', NULL, 'system', 'Cash in / out', 'active');
