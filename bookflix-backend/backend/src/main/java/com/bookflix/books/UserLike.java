package com.bookflix.books;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_likes")
public class UserLike {

    @EmbeddedId
    private Key id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserLike() {
    }

    public UserLike(long userId, long bookId) {
        this.id = new Key(userId, bookId);
        this.createdAt = Instant.now();
    }

    public Key getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "user_id")
        private Long userId;
        @Column(name = "book_id")
        private Long bookId;

        protected Key() {
        }

        public Key(Long userId, Long bookId) {
            this.userId = userId;
            this.bookId = bookId;
        }

        public Long getUserId() { return userId; }
        public Long getBookId() { return bookId; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && Objects.equals(bookId, k.bookId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, bookId);
        }
    }
}
