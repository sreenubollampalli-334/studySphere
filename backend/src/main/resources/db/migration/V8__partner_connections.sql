CREATE TABLE connection_requests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    low_user_id BIGINT NOT NULL,
    high_user_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    UNIQUE (low_user_id, high_user_id),
    CHECK (low_user_id < high_user_id AND requester_id IN (low_user_id, high_user_id)),
    CHECK (status IN ('PENDING','ACCEPTED','DECLINED','CANCELED')),
    FOREIGN KEY (low_user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (high_user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE user_blocks (
    blocker_id BIGINT NOT NULL,
    blocked_id BIGINT NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id),
    FOREIGN KEY (blocker_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (blocked_id) REFERENCES users(id) ON DELETE CASCADE
);
