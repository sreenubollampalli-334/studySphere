CREATE TABLE recommendation_feedback (
    user_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, topic_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE CASCADE,
    CHECK (status IN ('ACCEPTED', 'DISMISSED', 'HELPFUL', 'NOT_HELPFUL'))
);
