package com.bookflix.auth;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.auth.AuthDtos.AuthResponse;
import com.bookflix.auth.AuthDtos.LoginRequest;
import com.bookflix.auth.AuthDtos.RegisterRequest;
import com.bookflix.common.ApiException;
import com.bookflix.common.ErrorCode;
import com.bookflix.users.Role;
import com.bookflix.users.User;
import com.bookflix.users.UserDtos.UserDto;
import com.bookflix.users.UserRepository;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    /** Used to keep login timing similar whether or not the email exists. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode("bookflix-timing-equaliser");
    }

    /** Self-registration always creates a READER. Admins are provisioned out of band. */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = normalise(req.email());
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "An account with this email already exists");
        }
        User user = new User(req.name().trim(), email, passwordEncoder.encode(req.password()), Role.READER);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "An account with this email already exists");
        }
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = users.findByEmail(normalise(req.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(req.password(), dummyHash);
            throw invalid();
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw invalid();
        }
        user.getGenres().size(); // initialise for DTO mapping
        return toResponse(user);
    }

    private AuthResponse toResponse(User user) {
        return new AuthResponse(tokens.issue(user), "Bearer", tokens.ttlSeconds(), UserDto.from(user));
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
