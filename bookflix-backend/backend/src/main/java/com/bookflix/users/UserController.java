package com.bookflix.users;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bookflix.common.CurrentUser;
import com.bookflix.users.UserDtos.UpdateGenresRequest;
import com.bookflix.users.UserDtos.UpdateProfileRequest;
import com.bookflix.users.UserDtos.UserDto;
import com.bookflix.users.UserDtos.UserStatsDto;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public UserDto me(@AuthenticationPrincipal Jwt jwt) {
        return userService.me(CurrentUser.id(jwt));
    }

    @PutMapping
    public UserDto update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest req) {
        return userService.updateProfile(CurrentUser.id(jwt), req);
    }

    @PutMapping("/genres")
    public UserDto updateGenres(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateGenresRequest req) {
        return userService.updateGenres(CurrentUser.id(jwt), req);
    }

    @GetMapping("/stats")
    public UserStatsDto stats(@AuthenticationPrincipal Jwt jwt) {
        return userService.stats(CurrentUser.id(jwt));
    }
}
