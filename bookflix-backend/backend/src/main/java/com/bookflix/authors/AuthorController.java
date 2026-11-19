package com.bookflix.authors;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bookflix.authors.AuthorDtos.AuthorDto;

import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/authors")
@Validated
public class AuthorController {

    private final AuthorService authorService;

    public AuthorController(AuthorService authorService) {
        this.authorService = authorService;
    }

    @GetMapping
    public List<AuthorDto> list(@RequestParam(required = false) @Size(max = 100) String q) {
        return authorService.list(q);
    }
}
