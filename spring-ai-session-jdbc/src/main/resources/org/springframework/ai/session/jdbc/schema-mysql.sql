-- MySQL has no CREATE INDEX IF NOT EXISTS, so indexes are declared inline to keep the
-- script safe to re-run (spring.ai.session.jdbc.initialize-schema=always).
CREATE TABLE IF NOT EXISTS AI_SESSION (
    id            VARCHAR(255)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(255)  NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    expires_at    DATETIME(6),
    metadata      LONGTEXT,
    event_version BIGINT        NOT NULL DEFAULT 0,
    INDEX idx_ai_session_user_id (user_id),
    INDEX idx_ai_session_expires_at (expires_at)
);

CREATE TABLE IF NOT EXISTS AI_SESSION_EVENT (
    seq             BIGINT        NOT NULL AUTO_INCREMENT,
    id              VARCHAR(255)  NOT NULL PRIMARY KEY,
    session_id      VARCHAR(255)  NOT NULL,
    timestamp       DATETIME(6)   NOT NULL,
    message_type    VARCHAR(20)   NOT NULL,
    message_content LONGTEXT,
    message_data    LONGTEXT,
    synthetic       TINYINT(1)    NOT NULL DEFAULT 0,
    archived        TINYINT(1)    NOT NULL DEFAULT 0,
    branch          VARCHAR(500),
    metadata        LONGTEXT,
    UNIQUE KEY uq_ai_session_event_seq (seq),
    INDEX idx_ai_session_event_session_seq (session_id, seq),
    CONSTRAINT fk_ai_session_event_session
        FOREIGN KEY (session_id) REFERENCES AI_SESSION (id) ON DELETE CASCADE
);
