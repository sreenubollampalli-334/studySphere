CREATE TABLE resource_folders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    UNIQUE (user_id, name),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE resources (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    folder_id BIGINT,
    object_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    title VARCHAR(160) NOT NULL,
    file_name VARCHAR(180) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 20971520),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX (user_id, created_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (folder_id) REFERENCES resource_folders(id) ON DELETE SET NULL
);

CREATE TABLE resource_tags (
    resource_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    PRIMARY KEY (resource_id, name),
    INDEX (name, resource_id),
    FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE CASCADE
);

CREATE TABLE resource_permissions (
    resource_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (resource_id, user_id),
    INDEX (user_id, resource_id),
    FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE bookmarks (
    user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, resource_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE CASCADE
);

CREATE TABLE collections (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    UNIQUE (user_id, name),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE collection_resources (
    collection_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    PRIMARY KEY (collection_id, resource_id),
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
    FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE CASCADE
);

CREATE TABLE resource_access_history (
    user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    accessed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, resource_id),
    INDEX (user_id, accessed_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE CASCADE
);

CREATE TABLE resource_object_cleanup (
    object_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    available_at DATETIME(6) NOT NULL,
    INDEX (available_at)
);
