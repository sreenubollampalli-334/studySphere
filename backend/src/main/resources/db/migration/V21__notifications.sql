CREATE TABLE notification_preferences (
    user_id BIGINT PRIMARY KEY,
    task_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    invitations BOOLEAN NOT NULL DEFAULT TRUE,
    messages BOOLEAN NOT NULL DEFAULT TRUE,
    exams BOOLEAN NOT NULL DEFAULT TRUE,
    revision BOOLEAN NOT NULL DEFAULT TRUE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    event_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(160) NOT NULL,
    body VARCHAR(300) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    read_at DATETIME(6),
    UNIQUE (user_id, event_key),
    INDEX (user_id, created_at, id),
    INDEX (user_id, read_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX ix_reminder_dispatch ON task_reminders (state, scheduled_at, id);
