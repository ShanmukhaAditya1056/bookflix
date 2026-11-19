package com.bookflix.users;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bookflix.config.BookflixProperties;

/**
 * Creates a local development admin from environment variables. Only active with the "dev" profile
 * AND bookflix.dev-admin.enabled=true. No credentials are shipped in source control.
 */
@Component
@Profile("dev")
public class DevAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAdminSeeder.class);

    private final BookflixProperties props;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DevAdminSeeder(BookflixProperties props, UserRepository users, PasswordEncoder passwordEncoder) {
        this.props = props;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        BookflixProperties.DevAdmin cfg = props.devAdmin();
        if (cfg == null || !cfg.enabled()) {
            return;
        }
        if (isBlank(cfg.email()) || isBlank(cfg.password()) || cfg.password().length() < 8) {
            log.warn("Dev admin enabled but BOOKFLIX_DEV_ADMIN_EMAIL / BOOKFLIX_DEV_ADMIN_PASSWORD (>= 8 chars) missing; skipping");
            return;
        }
        String email = cfg.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            return; // idempotent
        }
        users.save(new User(isBlank(cfg.name()) ? "Local Admin" : cfg.name(), email,
                passwordEncoder.encode(cfg.password()), Role.ADMIN));
        log.info("Created local development admin account {}", email);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
