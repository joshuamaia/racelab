-- ---------------------------------------------------------------------------
-- RaceLab — experiment metadata (history, events, results, metrics)
-- These tables are written OUTSIDE of the business transactions of the
-- experiments, so events of rolled-back transactions are never lost.
-- ---------------------------------------------------------------------------

CREATE TABLE experiment (
    id              VARCHAR(32)  PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    preset_id       VARCHAR(64),
    scenario_id     VARCHAR(64)  NOT NULL,
    category        VARCHAR(32)  NOT NULL,
    strategy        VARCHAR(40)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    execution_mode  VARCHAR(20)  NOT NULL,
    instance_id     VARCHAR(32)  NOT NULL,
    comparison_id   VARCHAR(32),
    warmup          BOOLEAN      NOT NULL DEFAULT FALSE,
    rerun_of        VARCHAR(32),
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,
    consistent      BOOLEAN,
    violation_count INTEGER      NOT NULL DEFAULT 0,
    event_count     INTEGER      NOT NULL DEFAULT 0,
    error_message   TEXT
);

CREATE INDEX ix_experiment_created_at ON experiment (created_at DESC);
CREATE INDEX ix_experiment_status ON experiment (status);
CREATE INDEX ix_experiment_comparison ON experiment (comparison_id);

CREATE TABLE experiment_configuration (
    experiment_id VARCHAR(32) PRIMARY KEY REFERENCES experiment (id) ON DELETE CASCADE,
    configuration JSONB       NOT NULL
);

CREATE TABLE experiment_event (
    experiment_id  VARCHAR(32)  NOT NULL REFERENCES experiment (id) ON DELETE CASCADE,
    sequence       BIGINT       NOT NULL,
    transaction_id VARCHAR(64),
    worker_id      VARCHAR(64),
    thread_name    VARCHAR(128),
    instance_id    VARCHAR(32),
    correlation_id VARCHAR(64),
    connection_pid INTEGER,
    event_type     VARCHAR(40)  NOT NULL,
    resource       VARCHAR(128),
    occurred_at    TIMESTAMPTZ  NOT NULL,
    elapsed_ms     BIGINT       NOT NULL,
    source         VARCHAR(24)  NOT NULL,
    message        TEXT,
    details        JSONB,
    PRIMARY KEY (experiment_id, sequence)
);

CREATE TABLE experiment_result (
    experiment_id VARCHAR(32) PRIMARY KEY REFERENCES experiment (id) ON DELETE CASCADE,
    result        JSONB       NOT NULL
);

CREATE TABLE experiment_metric (
    experiment_id VARCHAR(32)    NOT NULL REFERENCES experiment (id) ON DELETE CASCADE,
    name          VARCHAR(64)    NOT NULL,
    value         NUMERIC(20, 4) NOT NULL,
    PRIMARY KEY (experiment_id, name)
);

CREATE TABLE experiment_violation (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id VARCHAR(32)  NOT NULL REFERENCES experiment (id) ON DELETE CASCADE,
    kind          VARCHAR(32)  NOT NULL,
    rule          VARCHAR(255) NOT NULL,
    severity      VARCHAR(16)  NOT NULL,
    expected      VARCHAR(128),
    actual        VARCHAR(128),
    difference    VARCHAR(128),
    description   TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_experiment_violation_experiment ON experiment_violation (experiment_id);

CREATE TABLE comparison (
    id            VARCHAR(32)  PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    scenario_id   VARCHAR(64)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    strategies    JSONB        NOT NULL,
    configuration JSONB        NOT NULL,
    repetitions   INTEGER      NOT NULL,
    warmup_runs   INTEGER      NOT NULL,
    progress      JSONB,
    results       JSONB,
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);
