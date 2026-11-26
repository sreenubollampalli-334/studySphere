CREATE TABLE study_rooms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(600) NOT NULL DEFAULT '',
    visibility VARCHAR(7) NOT NULL CHECK (visibility IN ('PUBLIC','PRIVATE')),
    capacity SMALLINT UNSIGNED NOT NULL CHECK (capacity BETWEEN 2 AND 100),
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE room_members (
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(9) NOT NULL CHECK (role IN ('OWNER','MODERATOR','MEMBER')),
    PRIMARY KEY (room_id,user_id),
    INDEX room_members_user (user_id,room_id),
    FOREIGN KEY (room_id) REFERENCES study_rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE room_invitations (
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (room_id,user_id),
    INDEX room_invitations_user (user_id,room_id),
    FOREIGN KEY (room_id) REFERENCES study_rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE room_agenda_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    text VARCHAR(1000) NOT NULL,
    INDEX room_agenda_room (room_id,id),
    FOREIGN KEY (room_id) REFERENCES study_rooms(id) ON DELETE CASCADE
);

CREATE TABLE room_resources (
    room_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    shared_by BIGINT NOT NULL,
    PRIMARY KEY (room_id,resource_id),
    FOREIGN KEY (room_id,shared_by) REFERENCES room_members(room_id,user_id) ON DELETE CASCADE,
    FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE CASCADE
);
