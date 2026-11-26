CREATE TABLE study_plan_proposals (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    minutes_per_day SMALLINT UNSIGNED NOT NULL,
    schedule JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PREVIEW',
    approved_plan_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    INDEX (user_id, id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CHECK (status IN ('PREVIEW','APPROVED'))
);
