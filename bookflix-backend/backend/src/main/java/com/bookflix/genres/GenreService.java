package com.bookflix.genres;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.common.ApiException;
import com.bookflix.genres.GenreDtos.GenreDto;
import com.bookflix.genres.GenreDtos.GenreRequest;
import com.bookflix.recommendations.ModelRebuildService;

@Service
public class GenreService {

    private final GenreRepository genres;
    private final ModelRebuildService modelRebuild;

    public GenreService(GenreRepository genres, ModelRebuildService modelRebuild) {
        this.genres = genres;
        this.modelRebuild = modelRebuild;
    }

    @Transactional(readOnly = true)
    public List<GenreDto> list() {
        return genres.findAllByOrderByNameAsc().stream().map(GenreDto::from).toList();
    }

    @Transactional
    public GenreDto create(GenreRequest req) {
        String name = req.name().trim();
        String slug = req.slug() == null || req.slug().isBlank() ? slugify(name) : req.slug();
        if (genres.existsByNameIgnoreCase(name) || genres.existsBySlug(slug)) {
            throw ApiException.conflict("A genre with this name or slug already exists");
        }
        return GenreDto.from(genres.save(new Genre(name, slug)));
    }

    @Transactional
    public GenreDto update(long id, GenreRequest req) {
        Genre genre = genres.findById(id).orElseThrow(() -> ApiException.notFound("Genre"));
        String name = req.name().trim();
        String slug = req.slug() == null || req.slug().isBlank() ? slugify(name) : req.slug();
        if (genres.existsByNameIgnoreCaseAndIdNot(name, id) || genres.existsBySlugAndIdNot(slug, id)) {
            throw ApiException.conflict("A genre with this name or slug already exists");
        }
        genre.setName(name);
        genre.setSlug(slug);
        modelRebuild.markCatalogChanged();
        return GenreDto.from(genre);
    }

    /** Genres still attached to an active book cannot be deleted (409). */
    @Transactional
    public void delete(long id) {
        Genre genre = genres.findById(id).orElseThrow(() -> ApiException.notFound("Genre"));
        if (genres.countActiveBooksWithGenre(id) > 0) {
            throw ApiException.conflict("Genre is still assigned to books; reassign them first");
        }
        genres.delete(genre);
        modelRebuild.markCatalogChanged();
    }

    static String slugify(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            throw ApiException.badRequest("Cannot derive a slug from this name; provide one explicitly");
        }
        return slug.length() > 80 ? slug.substring(0, 80) : slug;
    }
}
