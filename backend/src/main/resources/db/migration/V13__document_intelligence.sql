CREATE TABLE documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    resource_id BIGINT UNIQUE,
    owner_id BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    error VARCHAR(160),
    INDEX (status, id),
    FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE SET NULL
);

CREATE TABLE document_chunks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    position_index INT NOT NULL,
    page_number INT,
    content TEXT NOT NULL,
    UNIQUE (document_id, position_index),
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE TABLE ingestion_jobs (
    document_id BIGINT PRIMARY KEY,
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX (available_at, document_id),
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);
