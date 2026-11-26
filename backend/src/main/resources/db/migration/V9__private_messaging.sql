CREATE TABLE conversations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 member_low BIGINT NOT NULL,
 member_high BIGINT NOT NULL,
 UNIQUE(member_low,member_high),
 CHECK(member_low < member_high),
 FOREIGN KEY(member_low) REFERENCES users(id) ON DELETE CASCADE,
 FOREIGN KEY(member_high) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE private_messages (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 conversation_id BIGINT NOT NULL,
 sender_id BIGINT NOT NULL,
 body VARCHAR(4000) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 attachment_key CHAR(36),
 attachment_name VARCHAR(160),
 INDEX(conversation_id,id),
 FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
 FOREIGN KEY(sender_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE private_message_receipts (
 conversation_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 last_read_id BIGINT NOT NULL,
 PRIMARY KEY(conversation_id,user_id),
 FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
 FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE private_message_reports (
 message_id BIGINT NOT NULL,
 reporter_id BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(message_id,reporter_id),
 FOREIGN KEY(message_id) REFERENCES private_messages(id) ON DELETE CASCADE,
 FOREIGN KEY(reporter_id) REFERENCES users(id) ON DELETE CASCADE
);
