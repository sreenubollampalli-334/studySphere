CREATE TABLE exams (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    subject_id BIGINT NOT NULL,
    title VARCHAR(150) NOT NULL,
    exam_date DATE NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    syllabus VARCHAR(4000),
    goal VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    INDEX ix_exams_owner_date (user_id, exam_date, id),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subjects (id)
);

CREATE TABLE exam_topics (
    exam_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    PRIMARY KEY (exam_id, topic_id),
    FOREIGN KEY (exam_id) REFERENCES exams (id) ON DELETE CASCADE,
    FOREIGN KEY (topic_id) REFERENCES topics (id)
);

CREATE TABLE focus_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    subject_id BIGINT,
    focus_minutes SMALLINT UNSIGNED NOT NULL,
    break_minutes SMALLINT UNSIGNED NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    version BIGINT NOT NULL DEFAULT 0,
    INDEX ix_focus_sessions_owner_start (user_id, started_at, id),
    INDEX ix_focus_sessions_owner_state (user_id, state),
    CHECK (focus_minutes BETWEEN 1 AND 180 AND break_minutes BETWEEN 0 AND 60),
    CHECK ((state = 'ACTIVE' AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL AND completed_at >= started_at)),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subjects (id)
);

CREATE TABLE study_session_logs (
    focus_session_id BIGINT PRIMARY KEY,
    duration_seconds INT UNSIGNED NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    FOREIGN KEY (focus_session_id) REFERENCES focus_sessions (id) ON DELETE CASCADE
);
