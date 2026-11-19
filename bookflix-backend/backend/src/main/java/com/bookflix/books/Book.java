package com.bookflix.books;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import com.bookflix.authors.Author;
import com.bookflix.genres.Genre;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "books")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private Author author;

    @Column(length = 13, unique = true)
    private String isbn;

    @Column(name = "cover_url", length = 512)
    private String coverUrl;

    @Column(name = "published_year")
    private Integer publishedYear;

    @Column(nullable = false, length = 32)
    private String language = "en";

    /** Aggregates maintained by native UPDATEs in {@link BookRepository}; never written through the entity. */
    @Column(name = "like_count", nullable = false, insertable = false, updatable = false)
    private int likeCount;

    @Column(name = "rating_count", nullable = false, insertable = false, updatable = false)
    private int ratingCount;

    @Column(name = "avg_rating", nullable = false, insertable = false, updatable = false)
    private double avgRating;

    /** Denormalised "title author description" for the FULLTEXT index; refreshed on every write. */
    @Column(name = "search_text", columnDefinition = "TEXT")
    private String searchText;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToMany
    @JoinTable(name = "book_genres",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Book() {
    }

    public Book(String title, Author author) {
        this.title = title;
        this.author = author;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        refreshSearchText();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        refreshSearchText();
    }

    public void refreshSearchText() {
        searchText = String.join(" ", title == null ? "" : title, author == null ? "" : author.getName(),
                description == null ? "" : description).trim();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Author getAuthor() { return author; }
    public void setAuthor(Author author) { this.author = author; }
    public String getIsbn() { return isbn; }
    public void setIsbn(String isbn) { this.isbn = isbn; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public Integer getPublishedYear() { return publishedYear; }
    public void setPublishedYear(Integer publishedYear) { this.publishedYear = publishedYear; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public int getLikeCount() { return likeCount; }
    public int getRatingCount() { return ratingCount; }
    public double getAvgRating() { return avgRating; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Set<Genre> getGenres() { return genres; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
