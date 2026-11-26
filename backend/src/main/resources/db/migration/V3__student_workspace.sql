CREATE TABLE student_profiles (
    user_id BIGINT PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    institution VARCHAR(160),
    program VARCHAR(160),
    bio VARCHAR(1200),
    discoverable BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE learning_preferences (
    user_id BIGINT PRIMARY KEY,
    language VARCHAR(35) NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    learning_styles JSON NOT NULL,
    skills JSON NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE availability_slots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    weekday TINYINT UNSIGNED NOT NULL,
    starts_at TIME NOT NULL,
    ends_at TIME NOT NULL,
    CHECK (weekday BETWEEN 1 AND 7 AND ends_at > starts_at),
    UNIQUE (user_id, weekday, starts_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE subjects (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    slug VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL
);

CREATE TABLE topics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    subject_id BIGINT NOT NULL,
    slug VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    UNIQUE (subject_id, slug),
    FOREIGN KEY (subject_id) REFERENCES subjects(id)
);

CREATE TABLE user_subjects (
    user_id BIGINT NOT NULL,
    subject_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, subject_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subjects(id)
);

CREATE TABLE learning_goals (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    subject_id BIGINT,
    description VARCHAR(500) NOT NULL,
    target_date DATE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    CHECK (status IN ('ACTIVE', 'COMPLETED')),
    INDEX ix_learning_goals_owner (user_id, id),
    INDEX ix_learning_goals_owner_status (user_id, status),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subjects(id)
);

INSERT INTO subjects (id, slug, name) VALUES
    (1, 'computer-science', 'Computer Science'),
    (2, 'mathematics', 'Mathematics'),
    (3, 'physics', 'Physics'),
    (4, 'chemistry', 'Chemistry'),
    (5, 'biology', 'Biology'),
    (6, 'english', 'English'),
    (7, 'databases', 'Databases');

INSERT INTO topics (subject_id, slug, name) VALUES
    (1, 'data-structures', 'Data Structures'),
    (1, 'algorithms', 'Algorithms'),
    (2, 'calculus', 'Calculus'),
    (2, 'statistics', 'Statistics'),
    (3, 'mechanics', 'Mechanics'),
    (3, 'electricity', 'Electricity and Magnetism'),
    (4, 'chemical-bonding', 'Chemical Bonding'),
    (4, 'organic-chemistry', 'Organic Chemistry'),
    (5, 'cell-biology', 'Cell Biology'),
    (5, 'genetics', 'Genetics'),
    (6, 'academic-writing', 'Academic Writing'),
    (6, 'literary-analysis', 'Literary Analysis'),
    (7, 'sql', 'SQL'),
    (7, 'normalization', 'Normalization');
