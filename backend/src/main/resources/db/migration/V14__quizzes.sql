CREATE TABLE quizzes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(150) NOT NULL,
    questions JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX (user_id, id)
);

CREATE TABLE quiz_attempts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    quiz_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    score SMALLINT UNSIGNED,
    results JSON,
    submitted_at DATETIME(6),
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX (user_id, quiz_id, id)
);

CREATE TABLE quiz_evidence (
    attempt_id BIGINT NOT NULL,
    question_index SMALLINT UNSIGNED NOT NULL,
    user_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    correct BOOLEAN NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attempt_id, question_index),
    FOREIGN KEY (attempt_id) REFERENCES quiz_attempts(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE CASCADE,
    INDEX (user_id, topic_id, observed_at)
);
