-- MySQL has no CREATE INDEX IF NOT EXISTS, so indexes are declared inline to keep the
-- script safe to re-run (spring.ai.session.repository.jdbc.initialize-schema=always).
CREATE TABLE IF NOT EXISTS AI_SESSION (
    id            VARCHAR(255)  NOT NULL PRIMARY KEY,
    -- Not part of the model since 0.11.0: the owner is the indexed metadata attribute
    -- 'principalName' (AI_SESSION_INDEX). Still written with that attribute, and read as it
    -- for rows whose metadata has none, so existing databases keep working
    user_id       VARCHAR(255)  NOT NULL DEFAULT '',
    -- Session tree (0.11.0): the parent a thread was created from (NULL for a root) and
    -- the root of the tree (the session's own id for a root). No foreign key: the service
    -- deletes a thread with its parent, and an orphan still reads
    parent_session_id VARCHAR(255),
    root_session_id   VARCHAR(255),
    created_at    DATETIME(6)   NOT NULL,
    expires_at    DATETIME(6),
    metadata      LONGTEXT,
    -- Status (0.11.0): the session's own status, why its last turn ended, and when it was
    -- archived; a row written before 0.11.0 reads as an idle root
    status        VARCHAR(16)   NOT NULL DEFAULT 'IDLE',
    stop_reason   VARCHAR(32),
    archived_at   DATETIME(6),
    event_version BIGINT        NOT NULL DEFAULT 0,
    INDEX idx_ai_session_expires_at (expires_at),
    INDEX idx_ai_session_parent_session_id (parent_session_id),
    INDEX idx_ai_session_root_session_id (root_session_id)
);

-- Sessions written before 0.11.0 have no tree columns: they are roots. Safe to re-run.
UPDATE AI_SESSION SET root_session_id = id WHERE root_session_id IS NULL;

-- Indexed metadata attributes: one row per session and indexed attribute name (by
-- default the principal name). findByIndex(name, value) is one query over this table.
CREATE TABLE IF NOT EXISTS AI_SESSION_INDEX (
    session_id    VARCHAR(255)  NOT NULL,
    attr_name     VARCHAR(255)  NOT NULL,
    attr_value    VARCHAR(512)  NOT NULL,
    PRIMARY KEY (session_id, attr_name),
    INDEX idx_ai_session_index_name_value (attr_name, attr_value),
    CONSTRAINT fk_ai_session_index_session
        FOREIGN KEY (session_id) REFERENCES AI_SESSION (id) ON DELETE CASCADE
);

-- Sessions written before 0.11.0 have their owner in user_id only: index it once as the
-- principal name. Safe to re-run.
INSERT INTO AI_SESSION_INDEX (session_id, attr_name, attr_value)
SELECT s.id, 'principalName', s.user_id
FROM AI_SESSION s
WHERE s.user_id <> ''
  AND NOT EXISTS (SELECT 1 FROM AI_SESSION_INDEX i
                  WHERE i.session_id = s.id AND i.attr_name = 'principalName');

CREATE TABLE IF NOT EXISTS AI_SESSION_EVENT (
    seq             BIGINT        NOT NULL AUTO_INCREMENT,
    id              VARCHAR(255)  NOT NULL PRIMARY KEY,
    session_id      VARCHAR(255)  NOT NULL,
    timestamp       DATETIME(6)   NOT NULL,
    event_type      VARCHAR(32)   NOT NULL DEFAULT 'MESSAGE',
    -- NULL for an event that is not a message
    message_type    VARCHAR(20),
    message_content LONGTEXT,
    -- Message parts and metadata, or the payload of an event that is not a message
    payload         LONGTEXT,
    -- Unused since 0.11.0 (parts moved to payload); still read for rows written before
    message_data    LONGTEXT,
    synthetic       TINYINT(1)    NOT NULL DEFAULT 0,
    -- Not written since 0.11.0 (compaction appends a boundary row instead); still read
    -- for sessions a 0.10 compaction flagged and that have no boundary yet. Keep the
    -- column until that read is removed
    archived        TINYINT(1)    NOT NULL DEFAULT 0,
    -- Unused since 0.10.0 (branch support was removed); kept for existing databases
    branch          VARCHAR(500),
    metadata        LONGTEXT,
    UNIQUE KEY uq_ai_session_event_seq (seq),
    INDEX idx_ai_session_event_session_seq (session_id, seq),
    CONSTRAINT fk_ai_session_event_session
        FOREIGN KEY (session_id) REFERENCES AI_SESSION (id) ON DELETE CASCADE
);
