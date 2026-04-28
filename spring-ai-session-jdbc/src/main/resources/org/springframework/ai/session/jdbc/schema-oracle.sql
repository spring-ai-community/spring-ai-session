CREATE TABLE AI_SESSION (
    id            VARCHAR2(255 CHAR) NOT NULL PRIMARY KEY,
    user_id       VARCHAR2(255 CHAR) NOT NULL,
    created_at    TIMESTAMP          NOT NULL,
    expires_at    TIMESTAMP,
    metadata      JSON,
    event_version NUMBER(19)         DEFAULT 0 NOT NULL
);

CREATE INDEX idx_ai_session_user_id
    ON AI_SESSION (user_id);

CREATE INDEX idx_ai_session_expires_at
    ON AI_SESSION (expires_at);

CREATE TABLE AI_SESSION_EVENT (
      id              VARCHAR2(255 CHAR) NOT NULL PRIMARY KEY,
      session_id      VARCHAR2(255 CHAR) NOT NULL,
      timestamp       TIMESTAMP          NOT NULL,
      message_type    VARCHAR2(20 CHAR)   NOT NULL,
      message_content  VARCHAR2(4000 CHAR),
      message_data    JSON,
      synthetic       NUMBER(1)          DEFAULT 0 NOT NULL,
      branch          VARCHAR2(500 CHAR),
      metadata        JSON,
      CONSTRAINT fk_ai_session_event_session
          FOREIGN KEY (session_id) REFERENCES AI_SESSION (id) ON DELETE CASCADE
);

CREATE INDEX idx_ai_session_event_session_ts
    ON AI_SESSION_EVENT (session_id, timestamp);