package com.bookflix.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bookflix.recommendations.ModelRebuildService;
import com.bookflix.recommendations.ModelRebuildService.ModelStatus;
import com.bookflix.recommendations.ModelRebuildService.RebuildResult;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final JdbcClient jdbc;
    private final ModelRebuildService modelRebuild;

    public AdminController(JdbcClient jdbc, ModelRebuildService modelRebuild) {
        this.jdbc = jdbc;
        this.modelRebuild = modelRebuild;
    }

    public record AdminStatsDto(long users, long readers, long admins, long books, long deletedBooks, long authors,
                                long genres, long reviews, long likes, long bookshelfEntries, ModelStatus model) {
    }

    @GetMapping("/stats")
    public AdminStatsDto stats() {
        return new AdminStatsDto(
                count("SELECT COUNT(*) FROM users"),
                count("SELECT COUNT(*) FROM users WHERE role = 'READER'"),
                count("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'"),
                count("SELECT COUNT(*) FROM books WHERE deleted_at IS NULL"),
                count("SELECT COUNT(*) FROM books WHERE deleted_at IS NOT NULL"),
                count("SELECT COUNT(*) FROM authors"),
                count("SELECT COUNT(*) FROM genres"),
                count("SELECT COUNT(*) FROM reviews"),
                count("SELECT COUNT(*) FROM user_likes"),
                count("SELECT COUNT(*) FROM bookshelf"),
                modelRebuild.status());
    }

    @PostMapping("/recommendations/rebuild")
    public RebuildResult rebuild() {
        return modelRebuild.rebuildNow();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
