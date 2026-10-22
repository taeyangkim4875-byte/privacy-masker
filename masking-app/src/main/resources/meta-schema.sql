-- Meta DB (H2) schema. Runs on every connection via INIT=RUNSCRIPT, so everything is IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS db_connection (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100)  NOT NULL,
    host          VARCHAR(255)  NOT NULL,
    port          INT           NOT NULL,
    database_name VARCHAR(128)  NOT NULL,
    username      VARCHAR(128)  NOT NULL,
    password_enc  VARCHAR(1024),
    options       VARCHAR(512),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS masking_job (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100)  NOT NULL,
    connection_id BIGINT        NOT NULL,
    source_table  VARCHAR(128)  NOT NULL,
    pk_column     VARCHAR(128)  NOT NULL,
    target_table  VARCHAR(128)  NOT NULL,
    chunk_size    INT           NOT NULL DEFAULT 500,
    cron          VARCHAR(64),
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    policies      VARCHAR(400)  NOT NULL,
    hash_salt     VARCHAR(64)   NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS masking_column (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id       BIGINT       NOT NULL,
    column_name  VARCHAR(128) NOT NULL,
    col_mode     VARCHAR(10)  NOT NULL,
    entity_types VARCHAR(200),
    ordinal      INT          NOT NULL
);

CREATE TABLE IF NOT EXISTS job_watermark (
    job_id     BIGINT PRIMARY KEY,
    last_pk    VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS job_execution (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id          BIGINT       NOT NULL,
    trigger_type    VARCHAR(10)  NOT NULL,
    status          VARCHAR(12)  NOT NULL,
    started_at      TIMESTAMP    NOT NULL,
    ended_at        TIMESTAMP,
    read_count      BIGINT       NOT NULL DEFAULT 0,
    masked_rows     BIGINT       NOT NULL DEFAULT 0,
    span_count      BIGINT       NOT NULL DEFAULT 0,
    written_count   BIGINT       NOT NULL DEFAULT 0,
    start_pk        VARCHAR(255),
    end_pk          VARCHAR(255),
    error_message   VARCHAR(1000)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor      VARCHAR(100) NOT NULL,
    action     VARCHAR(50)  NOT NULL,
    target     VARCHAR(200),
    outcome    VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
