ALTER TABLE study_tasks ADD COLUMN completed_at DATETIME(6);

CREATE TABLE engagement_rewards (
    user_id BIGINT NOT NULL,
    event_type VARCHAR(12) NOT NULL,
    event_id BIGINT NOT NULL,
    xp SMALLINT UNSIGNED NOT NULL,
    earned_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id,event_type,event_id),
    INDEX (user_id,earned_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
