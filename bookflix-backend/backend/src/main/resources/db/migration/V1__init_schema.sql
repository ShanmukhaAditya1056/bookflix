-- Bookflix core schema (MySQL 8+)

CREATE TABLE users (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    name                 VARCHAR(100) NOT NULL,
    email                VARCHAR(255) NOT NULL,
    password_hash        VARCHAR(255) NOT NULL,
    role                 VARCHAR(20)  NOT NULL,
    onboarding_completed BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('READER', 'ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE authors (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(200) NOT NULL,
    bio        TEXT         NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_authors_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE genres (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    name VARCHAR(80) NOT NULL,
    slug VARCHAR(80) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_genres_name UNIQUE (name),
    CONSTRAINT uk_genres_slug UNIQUE (slug)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Books are soft-deleted (deleted_at) so reviews/likes/shelf history survive; see docs/architecture/deletion-policy.md.
-- like_count / rating_count / avg_rating are denormalised aggregates recomputed on every interaction write.
-- search_text denormalises title + author + description into one FULLTEXT index (InnoDB cannot reliably
-- combine several MATCH() predicates on one index, and cross-table MATCH needs every token in one place).
CREATE TABLE books (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    title          VARCHAR(255) NOT NULL,
    description    TEXT         NULL,
    author_id      BIGINT       NOT NULL,
    isbn           VARCHAR(13)  NULL,
    cover_url      VARCHAR(512) NULL,
    published_year INT          NULL,
    language       VARCHAR(32)  NOT NULL DEFAULT 'en',
    like_count     INT          NOT NULL DEFAULT 0,
    rating_count   INT          NOT NULL DEFAULT 0,
    avg_rating     DOUBLE       NOT NULL DEFAULT 0,
    -- title + author name + description; maintained by the application, searched via FULLTEXT
    search_text    TEXT         NULL,
    deleted_at     DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_books_isbn UNIQUE (isbn),
    CONSTRAINT fk_books_author FOREIGN KEY (author_id) REFERENCES authors (id) ON DELETE RESTRICT,
    INDEX idx_books_title (title),
    INDEX idx_books_author (author_id),
    INDEX idx_books_deleted_created (deleted_at, created_at),
    INDEX idx_books_deleted_popular (deleted_at, like_count, rating_count),
    INDEX idx_books_deleted_rating (deleted_at, avg_rating),
    INDEX idx_books_deleted_year (deleted_at, published_year),
    FULLTEXT INDEX ft_books_search_text (search_text)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE book_genres (
    book_id  BIGINT NOT NULL,
    genre_id BIGINT NOT NULL,
    PRIMARY KEY (book_id, genre_id),
    CONSTRAINT fk_book_genres_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    CONSTRAINT fk_book_genres_genre FOREIGN KEY (genre_id) REFERENCES genres (id) ON DELETE CASCADE,
    INDEX idx_book_genres_genre (genre_id, book_id)
) ENGINE = InnoDB;

CREATE TABLE user_genres (
    user_id  BIGINT NOT NULL,
    genre_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, genre_id),
    CONSTRAINT fk_user_genres_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_genres_genre FOREIGN KEY (genre_id) REFERENCES genres (id) ON DELETE CASCADE,
    INDEX idx_user_genres_genre (genre_id)
) ENGINE = InnoDB;

CREATE TABLE user_likes (
    user_id    BIGINT      NOT NULL,
    book_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, book_id),
    CONSTRAINT fk_user_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_likes_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    INDEX idx_user_likes_book_created (book_id, created_at),
    INDEX idx_user_likes_created (created_at)
) ENGINE = InnoDB;

CREATE TABLE reviews (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    book_id    BIGINT      NOT NULL,
    rating     INT         NOT NULL,
    comment    TEXT        NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reviews_user_book UNIQUE (user_id, book_id),
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT fk_reviews_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_reviews_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    INDEX idx_reviews_book_created (book_id, created_at),
    INDEX idx_reviews_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE bookshelf (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    book_id    BIGINT      NOT NULL,
    status     VARCHAR(20) NOT NULL,
    progress   INT         NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bookshelf_user_book UNIQUE (user_id, book_id),
    CONSTRAINT ck_bookshelf_status CHECK (status IN ('WANT_TO_READ', 'READING', 'COMPLETED')),
    CONSTRAINT ck_bookshelf_progress CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT fk_bookshelf_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_bookshelf_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    INDEX idx_bookshelf_user_status (user_id, status, updated_at),
    INDEX idx_bookshelf_book_updated (book_id, updated_at)
) ENGINE = InnoDB;
