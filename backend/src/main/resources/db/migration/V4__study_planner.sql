CREATE TABLE study_plans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    subject_id BIGINT,
    title VARCHAR(160) NOT NULL,
    start_date DATE,
    end_date DATE,
    version INT NOT NULL DEFAULT 0,
    UNIQUE (user_id, id),
    INDEX (user_id, start_date, end_date),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subjects(id),
    CHECK (start_date IS NULL OR end_date IS NULL OR start_date <= end_date)
);

CREATE TABLE study_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    plan_id BIGINT,
    parent_id BIGINT,
    title VARCHAR(160) NOT NULL,
    priority VARCHAR(8) NOT NULL DEFAULT 'MEDIUM',
    state VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    due_at DATETIME(6),
    recurrence_rule VARCHAR(6),
    recurrence_zone VARCHAR(80),
    occurrence_origin_id BIGINT,
    occurrence_at DATETIME(6),
    series_anchor_at DATETIME(6),
    version INT NOT NULL DEFAULT 0,
    UNIQUE (user_id, id),
    UNIQUE (occurrence_origin_id, occurrence_at),
    INDEX (user_id, due_at, id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id, plan_id) REFERENCES study_plans(user_id, id) ON DELETE CASCADE,
    FOREIGN KEY (user_id, parent_id) REFERENCES study_tasks(user_id, id) ON DELETE CASCADE,
    CHECK (priority IN ('LOW','MEDIUM','HIGH')),
    CHECK (state IN ('OPEN','COMPLETED')),
    CHECK (recurrence_rule IS NULL OR recurrence_rule IN ('DAILY','WEEKLY'))
);

CREATE TABLE task_reminders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    scheduled_at DATETIME(6) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    UNIQUE (task_id, scheduled_at),
    FOREIGN KEY (task_id) REFERENCES study_tasks(id) ON DELETE CASCADE
);
