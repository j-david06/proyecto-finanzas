CREATE TABLE email_mailboxes (
    source_connection_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    source varchar(20) NOT NULL DEFAULT 'IMAP' CHECK (source='IMAP'),
    account_id uuid NOT NULL,
    destination_account_id uuid NOT NULL,
    currency varchar(3) NOT NULL DEFAULT 'COP' CHECK(currency='COP'),
    folder varchar(255) NOT NULL CHECK (btrim(folder)<>''),
    login_sha256 bytea NOT NULL CHECK(octet_length(login_sha256)=32),
    uid_validity bigint CHECK (uid_validity>0),
    last_uid bigint NOT NULL DEFAULT 0 CHECK (last_uid>=0),
    lease_token uuid,
    lease_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    error_code varchar(80),
    FOREIGN KEY(owner_id,source,source_connection_id) REFERENCES source_connections(owner_id,source,id),
    FOREIGN KEY(owner_id,account_id,currency) REFERENCES accounts(owner_id,id,currency),
    FOREIGN KEY(owner_id,destination_account_id,currency) REFERENCES accounts(owner_id,id,currency),
    CHECK(account_id<>destination_account_id),
    CHECK((lease_token IS NULL)=(lease_until IS NULL))
);

CREATE TABLE email_receipts (
    id uuid PRIMARY KEY,
    source_connection_id uuid NOT NULL REFERENCES email_mailboxes(source_connection_id),
    uid_validity bigint NOT NULL CHECK (uid_validity>0),
    message_uid bigint NOT NULL CHECK (message_uid>0),
    kind varchar(20) NOT NULL CHECK(kind IN ('CARD_PAYMENT','CUT_ALERT','STATEMENT','UNKNOWN')),
    state varchar(20) NOT NULL CHECK(state IN ('QUEUED','ALERT','REVIEW','IGNORED')),
    error_code varchar(80),
    payload_sha256 bytea CHECK(payload_sha256 IS NULL OR octet_length(payload_sha256)=32),
    payload_encrypted bytea,
    payload_key_id varchar(128),
    cut_on date,
    ingestion_id uuid REFERENCES ingestion_logs(id),
    received_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(source_connection_id,uid_validity,message_uid),
    CHECK((payload_encrypted IS NULL)=(payload_key_id IS NULL)),
    CHECK(state<>'QUEUED' OR (ingestion_id IS NOT NULL AND kind='CARD_PAYMENT')),
    CHECK(state<>'ALERT' OR kind IN ('CUT_ALERT','STATEMENT'))
);
CREATE INDEX ix_email_receipts_connection ON email_receipts(source_connection_id,received_at DESC);
COMMENT ON TABLE email_receipts IS 'Technical email audit and non-financial alerts. No transaction is created for an alert.';

CREATE TABLE billing_statements (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    account_id uuid NOT NULL,
    currency varchar(3) NOT NULL DEFAULT 'COP' CHECK(currency='COP'),
    minimum_payment numeric(18,2) NOT NULL CHECK(minimum_payment>=0 AND minimum_payment<>'NaN'::numeric),
    billing_period_start date NOT NULL,
    billing_period_end date NOT NULL,
    cut_off_date date NOT NULL,
    payment_due_date date NOT NULL,
    email_receipt_id uuid NOT NULL REFERENCES email_receipts(id),
    observed_at timestamptz NOT NULL,
    UNIQUE(owner_id,account_id,cut_off_date),
    FOREIGN KEY(owner_id,account_id,currency) REFERENCES accounts(owner_id,id,currency),
    CHECK(billing_period_end=cut_off_date),
    CHECK(billing_period_end-billing_period_start BETWEEN 1 AND 62),
    CHECK(payment_due_date-cut_off_date BETWEEN 1 AND 62)
);
CREATE INDEX ix_billing_statements_due ON billing_statements(owner_id,payment_due_date);
