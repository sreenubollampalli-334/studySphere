CREATE TABLE room_shared_notes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    body VARCHAR(4000) NOT NULL,
    FOREIGN KEY (room_id) REFERENCES study_rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX ix_room_note_history (room_id,id)
);

CREATE TABLE room_assistant_consents (
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (room_id,user_id),
    FOREIGN KEY (room_id,user_id) REFERENCES room_members(room_id,user_id) ON DELETE CASCADE
);

CREATE TABLE room_ai_summaries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    body VARCHAR(1800) NOT NULL,
    FOREIGN KEY (room_id) REFERENCES study_rooms(id) ON DELETE CASCADE,
    INDEX ix_room_summary_history (room_id,id)
);

CREATE TABLE room_ai_proposals (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    summary_id BIGINT NOT NULL,
    draft JSON NOT NULL,
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    approver_id BIGINT,
    result_id BIGINT,
    FOREIGN KEY (summary_id) REFERENCES room_ai_summaries(id) ON DELETE CASCADE,
    FOREIGN KEY (approver_id) REFERENCES users(id) ON DELETE SET NULL
);
