CREATE TABLE IF NOT EXISTS AI_SESSION (
    id            VARCHAR(255)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(255)  NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    expires_at    TIMESTAMP,
    metadata      TEXT,
    event_version BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ai_session_user_id
    ON AI_SESSION (user_id);

CREATE INDEX IF NOT EXISTS idx_ai_session_expires_at
    ON AI_SESSION (expires_at);

CREATE TABLE IF NOT EXISTS AI_SESSION_EVENT (
    id              VARCHAR(255)  NOT NULL PRIMARY KEY,
    session_id      VARCHAR(255)  NOT NULL,
    "timestamp"     TIMESTAMP     NOT NULL,
    message_type    VARCHAR(20)   NOT NULL,
    message_content TEXT,
    message_data    TEXT,
    synthetic       BOOLEAN       NOT NULL DEFAULT FALSE,
    branch          VARCHAR(500),
    metadata        TEXT,
    CONSTRAINT fk_ai_session_event_session
        FOREIGN KEY (session_id) REFERENCES AI_SESSION (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ai_session_event_session_ts
    ON AI_SESSION_EVENT (session_id, "timestamp");
