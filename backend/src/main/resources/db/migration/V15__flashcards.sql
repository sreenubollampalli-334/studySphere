CREATE TABLE flashcard_decks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    subject_id BIGINT,
    topic_id BIGINT,
    document_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    INDEX (user_id, id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subjects(id),
    FOREIGN KEY (topic_id) REFERENCES topics(id),
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE TABLE flashcards (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    deck_id BIGINT NOT NULL,
    front TEXT NOT NULL,
    back TEXT NOT NULL,
    due_at DATETIME(6) NOT NULL,
    interval_days INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    INDEX (deck_id, due_at, id),
    FOREIGN KEY (deck_id) REFERENCES flashcard_decks(id) ON DELETE CASCADE
);

CREATE TABLE flashcard_reviews (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    card_id BIGINT NOT NULL,
    request_id BINARY(16) NOT NULL,
    rating VARCHAR(8) NOT NULL,
    reviewed_at DATETIME(6) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    interval_days INT NOT NULL,
    UNIQUE (card_id, request_id),
    FOREIGN KEY (card_id) REFERENCES flashcards(id) ON DELETE CASCADE
);
