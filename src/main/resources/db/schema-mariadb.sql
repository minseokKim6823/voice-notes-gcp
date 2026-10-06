CREATE TABLE IF NOT EXISTS voice_notes (
    id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    audio_object VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status VARCHAR(255) NOT NULL,
    summary LONGTEXT NULL,
    error TEXT NULL,
    PRIMARY KEY (id),
    INDEX idx_voice_notes_created_at (created_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
