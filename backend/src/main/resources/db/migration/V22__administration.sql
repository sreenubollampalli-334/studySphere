INSERT INTO roles (code) VALUES ('ADMIN');

CREATE TABLE admin_reports (
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('PRIVATE_MESSAGE','COMMUNITY_POST','COMMUNITY_COMMENT')),
    target_id BIGINT NOT NULL,
    reporter_id BIGINT NOT NULL,
    subject_id BIGINT NOT NULL,
    reason VARCHAR(500),
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED','DISMISSED')),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (kind,target_id,reporter_id),
    INDEX ix_admin_reports_page (status,created_at,kind,target_id,reporter_id),
    FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES users(id) ON DELETE CASCADE,
    CHECK (kind <> 'PRIVATE_MESSAGE' OR reason IS NULL)
);

INSERT INTO admin_reports (kind,target_id,reporter_id,subject_id,created_at,updated_at)
SELECT 'PRIVATE_MESSAGE',r.message_id,r.reporter_id,m.sender_id,r.created_at,r.created_at
FROM private_message_reports r JOIN private_messages m ON m.id=r.message_id;

CREATE TABLE admin_account_restrictions (
    user_id BIGINT NOT NULL PRIMARY KEY,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE TABLE admin_audits (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT,
    action VARCHAR(32) NOT NULL,
    target VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX ix_admin_audits_created (created_at,id)
);

CREATE INDEX ix_ai_usage_started_provider ON ai_usage_logs (started_at,provider);
