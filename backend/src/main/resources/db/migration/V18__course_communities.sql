CREATE TABLE communities (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(600) NOT NULL DEFAULT '',
    FOREIGN KEY (subject_id) REFERENCES subjects(id),
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE community_members (
    community_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(9) NOT NULL CHECK (role IN ('OWNER','MODERATOR','MEMBER')),
    PRIMARY KEY (community_id,user_id),
    INDEX ix_community_members_user (user_id,community_id),
    FOREIGN KEY (community_id) REFERENCES communities(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE community_posts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    community_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    body TEXT NOT NULL,
    allow_ai_summary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    INDEX ix_community_posts (community_id,id),
    FOREIGN KEY (community_id) REFERENCES communities(id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE community_comments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    body TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX ix_community_comments (post_id,id),
    FOREIGN KEY (post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE community_votes (
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    value SMALLINT NOT NULL CHECK (value IN (-1,1)),
    PRIMARY KEY (post_id,user_id),
    FOREIGN KEY (post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE community_resources (
    community_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    shared_by BIGINT NOT NULL,
    PRIMARY KEY (community_id,resource_id),
    FOREIGN KEY (community_id,shared_by) REFERENCES community_members(community_id,user_id) ON DELETE CASCADE,
    FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE CASCADE
);
