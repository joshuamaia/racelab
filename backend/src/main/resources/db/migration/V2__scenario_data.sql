-- ---------------------------------------------------------------------------
-- RaceLab — business data used by the experiments.
--
-- Every row belongs to a namespace (experiment_id). Rows with
-- experiment_id = 'TEMPLATE' are the seeded templates; each experiment copies
-- the templates it needs into its own namespace, so concurrent experiments
-- never touch each other's data and no global TRUNCATE is ever needed.
-- ---------------------------------------------------------------------------

-- Bank -----------------------------------------------------------------------
CREATE TABLE account (
    id                 BIGSERIAL PRIMARY KEY,
    experiment_id      VARCHAR(32)    NOT NULL,
    code               VARCHAR(32)    NOT NULL,
    owner              VARCHAR(100)   NOT NULL,
    balance            NUMERIC(19, 2) NOT NULL,
    version            BIGINT         NOT NULL DEFAULT 0,
    update_count       INTEGER        NOT NULL DEFAULT 0,
    last_fencing_token BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_account_code UNIQUE (experiment_id, code)
);

CREATE TABLE account_movement (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  VARCHAR(32)    NOT NULL,
    account_id     BIGINT         NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    type           VARCHAR(16)    NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    balance_before NUMERIC(19, 2),
    balance_after  NUMERIC(19, 2),
    transaction_id VARCHAR(64),
    worker_id      VARCHAR(64),
    fencing_token  BIGINT,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_account_movement_account ON account_movement (experiment_id, account_id);

-- Inventory ------------------------------------------------------------------
-- NOTE: intentionally no CHECK (stock >= 0): the ATOMIC_UNGUARDED strategy must be
-- able to demonstrate negative stock. The guarded strategies enforce the rule.
CREATE TABLE product (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id VARCHAR(32)  NOT NULL,
    sku           VARCHAR(32)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    stock         INTEGER      NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_sku UNIQUE (experiment_id, sku)
);

CREATE TABLE purchase_order (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  VARCHAR(32)    NOT NULL,
    order_number   VARCHAR(32)    NOT NULL,
    status         VARCHAR(16)    NOT NULL,
    amount         NUMERIC(19, 2),
    transaction_id VARCHAR(64),
    worker_id      VARCHAR(64),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_purchase_order_experiment ON purchase_order (experiment_id);

CREATE TABLE purchase_order_item (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id VARCHAR(32) NOT NULL,
    order_id      BIGINT      NOT NULL REFERENCES purchase_order (id) ON DELETE CASCADE,
    product_id    BIGINT      NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    quantity      INTEGER     NOT NULL CHECK (quantity > 0)
);

CREATE INDEX ix_purchase_order_item_product ON purchase_order_item (experiment_id, product_id);

-- Seat reservation -----------------------------------------------------------
CREATE TABLE seat (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id VARCHAR(32) NOT NULL,
    code          VARCHAR(16) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    reserved_by   VARCHAR(64),
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_seat_code UNIQUE (experiment_id, code)
);

-- Safe structure: at most one reservation per seat, enforced by the database.
CREATE TABLE reservation (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  VARCHAR(32) NOT NULL,
    seat_id        BIGINT      NOT NULL REFERENCES seat (id) ON DELETE CASCADE,
    customer       VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_reservation_seat UNIQUE (experiment_id, seat_id)
);

-- Explicitly UNSAFE structure (no unique constraint) used to demonstrate double booking
-- and to show that locking strategies can protect the invariant without a constraint.
CREATE TABLE reservation_unsafe (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  VARCHAR(32) NOT NULL,
    seat_id        BIGINT      NOT NULL REFERENCES seat (id) ON DELETE CASCADE,
    customer       VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_reservation_unsafe_seat ON reservation_unsafe (experiment_id, seat_id);

-- Payments / idempotency ----------------------------------------------------
CREATE TABLE payment (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   VARCHAR(32)    NOT NULL,
    idempotency_key VARCHAR(64)    NOT NULL,
    customer        VARCHAR(64)    NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    transaction_id  VARCHAR(64),
    worker_id       VARCHAR(64),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_payment_idempotency UNIQUE (experiment_id, idempotency_key)
);

-- Explicitly UNSAFE structure used by the "retry without idempotency" demonstration.
CREATE TABLE payment_unsafe (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   VARCHAR(32)    NOT NULL,
    idempotency_key VARCHAR(64)    NOT NULL,
    customer        VARCHAR(64)    NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    transaction_id  VARCHAR(64),
    worker_id       VARCHAR(64),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_payment_unsafe_key ON payment_unsafe (experiment_id, idempotency_key);

CREATE TABLE idempotency_record (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    payment_id      BIGINT,
    response        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_key UNIQUE (experiment_id, idempotency_key)
);

-- Write skew ------------------------------------------------------------------
CREATE TABLE doctor (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id VARCHAR(32)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    shift_code    VARCHAR(32)  NOT NULL,
    on_call       BOOLEAN      NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_doctor_name UNIQUE (experiment_id, name)
);

CREATE INDEX ix_doctor_shift ON doctor (experiment_id, shift_code);

-- Work items / message processing ------------------------------------------
CREATE TABLE work_item (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id VARCHAR(32) NOT NULL,
    code          VARCHAR(32) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    owner         VARCHAR(64),
    claimed_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_work_item_code UNIQUE (experiment_id, code)
);

CREATE INDEX ix_work_item_status ON work_item (experiment_id, status, id);

-- Externally visible side effects (shipments, e-mails, ledger postings…) whose
-- duplication is what the experiments try to detect.
CREATE TABLE side_effect (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  VARCHAR(32)  NOT NULL,
    resource       VARCHAR(64)  NOT NULL,
    description    VARCHAR(255) NOT NULL,
    worker_id      VARCHAR(64),
    transaction_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_side_effect_resource ON side_effect (experiment_id, resource);

-- Transactional outbox -------------------------------------------------------
CREATE TABLE outbox_event (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id VARCHAR(32) NOT NULL,
    aggregate_id  VARCHAR(64) NOT NULL,
    event_type    VARCHAR(64) NOT NULL,
    payload       JSONB       NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts      INTEGER     NOT NULL DEFAULT 0,
    published_by  VARCHAR(64),
    published_at  TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_outbox_pending ON outbox_event (experiment_id, status, id);

CREATE TABLE processed_event (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id VARCHAR(32) NOT NULL,
    event_id      VARCHAR(64) NOT NULL,
    consumer      VARCHAR(64) NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_processed_event UNIQUE (experiment_id, event_id, consumer)
);

-- Templates --------------------------------------------------------------------
INSERT INTO account (experiment_id, code, owner, balance) VALUES
    ('TEMPLATE', 'ACC-A', 'Conta A', 1000.00),
    ('TEMPLATE', 'ACC-B', 'Conta B', 2000.00),
    ('TEMPLATE', 'ACC-C', 'Conta C', 5000.00);

INSERT INTO product (experiment_id, sku, name, stock) VALUES
    ('TEMPLATE', 'NOTEBOOK', 'Notebook', 10),
    ('TEMPLATE', 'MONITOR', 'Monitor', 5),
    ('TEMPLATE', 'KEYBOARD', 'Teclado', 20);

INSERT INTO seat (experiment_id, code, status) VALUES
    ('TEMPLATE', 'A01', 'AVAILABLE'),
    ('TEMPLATE', 'A02', 'AVAILABLE'),
    ('TEMPLATE', 'A03', 'AVAILABLE');

INSERT INTO doctor (experiment_id, name, shift_code, on_call) VALUES
    ('TEMPLATE', 'Médico A', 'NIGHT-01', TRUE),
    ('TEMPLATE', 'Médico B', 'NIGHT-01', TRUE);

INSERT INTO work_item (experiment_id, code, status) VALUES
    ('TEMPLATE', 'PED-001', 'PENDING'),
    ('TEMPLATE', 'PED-002', 'PENDING'),
    ('TEMPLATE', 'PED-003', 'PENDING');
