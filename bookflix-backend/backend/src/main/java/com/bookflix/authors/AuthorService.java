package com.bookflix.authors;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.authors.AuthorDtos.AuthorDto;
import com.bookflix.authors.AuthorDtos.AuthorRequest;
import com.bookflix.common.ApiException;
import com.bookflix.recommendations.ModelRebuildService;

@Service
public class AuthorService {

    private final AuthorRepository authors;
    private final ModelRebuildService modelRebuild;

    public AuthorService(AuthorRepository authors, ModelRebuildService modelRebuild) {
        this.authors = authors;
        this.modelRebuild = modelRebuild;
    }

    @Transactional(readOnly = true)
    public List<AuthorDto> list(String q) {
        List<Author> result = q == null || q.isBlank()
                ? authors.findAllByOrderByNameAsc()
                : authors.findByNameContainingIgnoreCaseOrderByNameAsc(q.trim());
        return result.stream().map(AuthorDto::from).toList();
    }

    @Transactional
    public AuthorDto create(AuthorRequest req) {
        return AuthorDto.from(authors.save(new Author(req.name().trim(), blankToNull(req.bio()))));
    }

    @Transactional
    public AuthorDto update(long id, AuthorRequest req) {
        Author author = authors.findById(id).orElseThrow(() -> ApiException.notFound("Author"));
        author.setName(req.name().trim());
        author.setBio(blankToNull(req.bio()));
        authors.saveAndFlush(author);
        authors.refreshBookSearchText(id);
        modelRebuild.markCatalogChanged();
        return AuthorDto.from(author);
    }

    /** Authors referenced by any book (including soft-deleted ones) cannot be deleted (409). */
    @Transactional
    public void delete(long id) {
        Author author = authors.findById(id).orElseThrow(() -> ApiException.notFound("Author"));
        if (authors.countBooksByAuthor(id) > 0) {
            throw ApiException.conflict("Author still has books; delete or reassign them first");
        }
        authors.delete(author);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
