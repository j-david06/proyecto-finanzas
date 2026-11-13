CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    name varchar(120) NOT NULL CHECK (btrim(name) <> ''),
    kind varchar(20) NOT NULL CHECK (kind IN ('CASH', 'DEPOSIT', 'CREDIT_CARD')),
    currency varchar(3) NOT NULL DEFAULT 'COP' CHECK (currency ~ '^[A-Z]{3}$'),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id, currency)
);
CREATE INDEX ix_accounts_owner ON accounts (owner_id);

CREATE TABLE categories (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    name varchar(80) NOT NULL CHECK (btrim(name) <> ''),
    kind varchar(10) NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE')),
    active boolean NOT NULL DEFAULT true,
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, id, kind)
);
CREATE UNIQUE INDEX uq_categories_name ON categories (owner_id, kind, lower(btrim(name)));

CREATE TABLE recurring_charges (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    account_id uuid NOT NULL,
    category_id uuid,
    name varchar(160) NOT NULL CHECK (btrim(name) <> ''),
    kind varchar(15) NOT NULL CHECK (kind IN ('SUBSCRIPTION', 'INSTALLMENT')),
    expected_amount numeric(18,2) NOT NULL
        CHECK (expected_amount > 0 AND expected_amount <> 'NaN'::numeric),
    currency varchar(3) NOT NULL DEFAULT 'COP' CHECK (currency ~ '^[A-Z]{3}$'),
    interval_months smallint NOT NULL DEFAULT 1 CHECK (interval_months > 0),
    billing_day smallint NOT NULL CHECK (billing_day BETWEEN 1 AND 31),
    starts_on date NOT NULL,
    ends_on date,
    next_due_on date NOT NULL,
    total_installments integer,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id, account_id, currency),
    FOREIGN KEY (owner_id, account_id, currency) REFERENCES accounts (owner_id, id, currency),
    FOREIGN KEY (owner_id, category_id) REFERENCES categories (owner_id, id),
    CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CHECK (next_due_on >= starts_on),
    CHECK (
        (kind = 'SUBSCRIPTION' AND total_installments IS NULL)
        OR (kind = 'INSTALLMENT' AND total_installments IS NOT NULL AND total_installments > 0)
    )
);
CREATE INDEX ix_recurring_due ON recurring_charges (next_due_on, owner_id) WHERE active;
CREATE INDEX ix_recurring_account ON recurring_charges (owner_id, account_id, currency);
CREATE INDEX ix_recurring_category ON recurring_charges (owner_id, category_id) WHERE category_id IS NOT NULL;

CREATE TABLE transactions (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    account_id uuid NOT NULL,
    counterparty_account_id uuid,
    category_id uuid,
    recurring_charge_id uuid,
    kind varchar(10) NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'POSTED', 'VOIDED')),
    amount numeric(18,2) NOT NULL CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    currency varchar(3) NOT NULL DEFAULT 'COP' CHECK (currency ~ '^[A-Z]{3}$'),
    occurred_at timestamptz NOT NULL,
    merchant varchar(200) CHECK (merchant IS NULL OR btrim(merchant) <> ''),
    description varchar(500) CHECK (description IS NULL OR btrim(description) <> ''),
    bank_provider varchar(40) CHECK (bank_provider IS NULL OR btrim(bank_provider) <> ''),
    bank_event_id varchar(200) CHECK (bank_event_id IS NULL OR btrim(bank_event_id) <> ''),
    recurring_due_on date,
    installment_number integer CHECK (installment_number IS NULL OR installment_number > 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, id),
    FOREIGN KEY (owner_id, account_id, currency) REFERENCES accounts (owner_id, id, currency),
    FOREIGN KEY (owner_id, counterparty_account_id, currency) REFERENCES accounts (owner_id, id, currency),
    FOREIGN KEY (owner_id, category_id, kind) REFERENCES categories (owner_id, id, kind),
    FOREIGN KEY (owner_id, recurring_charge_id, account_id, currency)
        REFERENCES recurring_charges (owner_id, id, account_id, currency),
    CHECK (
        (kind = 'TRANSFER' AND counterparty_account_id IS NOT NULL
            AND counterparty_account_id <> account_id AND category_id IS NULL)
        OR (kind <> 'TRANSFER' AND counterparty_account_id IS NULL)
    ),
    CHECK (bank_event_id IS NULL OR bank_provider IS NOT NULL),
    CHECK (
        (recurring_charge_id IS NULL AND recurring_due_on IS NULL AND installment_number IS NULL)
        OR (recurring_charge_id IS NOT NULL AND recurring_due_on IS NOT NULL AND kind <> 'INCOME')
    )
);
CREATE UNIQUE INDEX uq_transactions_bank_event
    ON transactions (owner_id, account_id, bank_provider, bank_event_id) WHERE bank_event_id IS NOT NULL;
CREATE INDEX ix_transactions_timeline ON transactions (owner_id, occurred_at DESC, id);
CREATE INDEX ix_transactions_account ON transactions (owner_id, account_id, currency, occurred_at DESC);
CREATE INDEX ix_transactions_destination ON transactions (owner_id, counterparty_account_id, currency)
    WHERE counterparty_account_id IS NOT NULL;
CREATE INDEX ix_transactions_category ON transactions (owner_id, category_id, kind) WHERE category_id IS NOT NULL;
CREATE INDEX ix_transactions_recurring ON transactions (owner_id, recurring_charge_id, recurring_due_on)
    WHERE recurring_charge_id IS NOT NULL;

CREATE TABLE source_connections (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    source varchar(20) NOT NULL CHECK (source IN ('IOS_SHORTCUTS', 'GMAIL', 'IMAP', 'MANUAL')),
    name varchar(120) NOT NULL CHECK (btrim(name) <> ''),
    api_key_sha256 bytea CHECK (api_key_sha256 IS NULL OR octet_length(api_key_sha256) = 32),
    authentication_profile varchar(20) NOT NULL DEFAULT 'API_KEY_ONLY'
        CHECK (authentication_profile IN ('API_KEY_ONLY', 'HMAC_REQUIRED')),
    allowed_account_ids uuid[] NOT NULL,
    active boolean NOT NULL DEFAULT true,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, source, id),
    CHECK (source <> 'IOS_SHORTCUTS' OR api_key_sha256 IS NOT NULL),
    CHECK (cardinality(allowed_account_ids) > 0),
    CHECK (array_ndims(allowed_account_ids) = 1),
    CHECK (array_position(allowed_account_ids, NULL) IS NULL),
    CHECK (expires_at IS NULL OR expires_at > created_at)
);
CREATE UNIQUE INDEX uq_source_connections_api_key ON source_connections (api_key_sha256)
    WHERE api_key_sha256 IS NOT NULL;
CREATE INDEX ix_source_connections_owner ON source_connections (owner_id);
CREATE INDEX ix_source_connections_accounts ON source_connections USING gin (allowed_account_ids);

-- Six tables only: the small MVP account allowlist lives in an array.
-- Validate references and take row locks, as an ordinary FK would.
CREATE FUNCTION validate_source_connection_accounts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    permitted_account uuid;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.owner_id IS DISTINCT FROM OLD.owner_id
                OR NEW.source IS DISTINCT FROM OLD.source THEN
            RAISE EXCEPTION 'Connection identity, owner and source are immutable' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF cardinality(NEW.allowed_account_ids) <> (
        SELECT count(DISTINCT account_id) FROM unnest(NEW.allowed_account_ids) AS ids(account_id)
    ) THEN
        RAISE EXCEPTION 'Allowed account IDs must be distinct and non-null' USING ERRCODE = '23514';
    END IF;
    FOR permitted_account IN SELECT unnest(NEW.allowed_account_ids) LOOP
        PERFORM 1 FROM accounts WHERE id = permitted_account AND owner_id = NEW.owner_id FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Allowed account does not belong to connection owner' USING ERRCODE = '23503';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tr_source_connection_accounts
    BEFORE INSERT OR UPDATE ON source_connections
    FOR EACH ROW EXECUTE FUNCTION validate_source_connection_accounts();

CREATE FUNCTION protect_account_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (
            SELECT 1 FROM source_connections
            WHERE owner_id = OLD.owner_id AND allowed_account_ids @> ARRAY[OLD.id]
        ) THEN
            RAISE EXCEPTION 'Account is referenced by a source connection' USING ERRCODE = '23503';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.owner_id IS DISTINCT FROM OLD.owner_id
            OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.kind IS DISTINCT FROM OLD.kind THEN
        RAISE EXCEPTION 'Account identity, owner, currency and kind are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tr_account_identity
    BEFORE UPDATE OR DELETE ON accounts
    FOR EACH ROW EXECUTE FUNCTION protect_account_identity();

CREATE TABLE ingestion_logs (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    source varchar(20) NOT NULL CHECK (source IN ('IOS_SHORTCUTS', 'GMAIL', 'IMAP', 'MANUAL')),
    source_connection_id uuid NOT NULL,
    idempotency_key varchar(255) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    payload_sha256 bytea NOT NULL CHECK (octet_length(payload_sha256) = 32),
    payload_encrypted bytea,
    payload_key_id varchar(128),
    state varchar(15) NOT NULL DEFAULT 'RECEIVED'
        CHECK (state IN ('RECEIVED', 'PROCESSING', 'RETRY', 'PROCESSED', 'REVIEW', 'DEAD')),
    transaction_id uuid,
    parser_version varchar(40),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    error_code varchar(80),
    received_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, source, source_connection_id, idempotency_key),
    FOREIGN KEY (owner_id, source, source_connection_id) REFERENCES source_connections (owner_id, source, id),
    FOREIGN KEY (owner_id, transaction_id) REFERENCES transactions (owner_id, id),
    CHECK ((payload_encrypted IS NULL) = (payload_key_id IS NULL)),
    CHECK (state NOT IN ('RECEIVED', 'PROCESSING', 'RETRY') OR payload_encrypted IS NOT NULL),
    CHECK (
        (state = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)
    ),
    CHECK (state <> 'PROCESSED' OR transaction_id IS NOT NULL)
);
CREATE INDEX ix_ingestion_ready ON ingestion_logs (available_at, received_at, id)
    WHERE state IN ('RECEIVED', 'RETRY');
CREATE INDEX ix_ingestion_expired_lease ON ingestion_logs (lease_until) WHERE state = 'PROCESSING';
CREATE INDEX ix_ingestion_owner ON ingestion_logs (owner_id, received_at DESC);
CREATE INDEX ix_ingestion_transaction ON ingestion_logs (owner_id, transaction_id) WHERE transaction_id IS NOT NULL;

COMMENT ON COLUMN source_connections.api_key_sha256 IS
    'SHA-256 digest of the complete UTF-8 API key, 32 raw bytes. Never store the cleartext key.';
COMMENT ON COLUMN source_connections.authentication_profile IS
    'Phase 1 supports API_KEY_ONLY. HMAC_REQUIRED is reserved; reject until verifier is implemented.';
COMMENT ON TABLE recurring_charges IS
    'Forecast obligations. Advancing next_due_on must never create an expense automatically.';
