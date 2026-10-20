package com.bm4a.platform.modules.users.internal;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.identity.IdentityClaims;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.users.CurrentUser;
import com.bm4a.platform.modules.users.UserDirectory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class UserService implements UserDirectory {

    static final int MAX_PREFERENCES_BYTES = 16 * 1024;

    private final UserRepository users;
    private final JsonColumns json;
    private final Clock clock;

    UserService(UserRepository users, JsonColumns json, Clock clock) {
        this.users = users;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Maps verified claims to the internal user, creating it on first sight. Suspended users are rejected.
     */
    @Transactional
    public CurrentUser resolve(IdentityClaims claims) {
        Instant now = SqlValues.now(clock);
        UserRow user = users.findByIdentity(claims.issuer(), claims.subject()).orElseGet(() -> {
            users.insertIfAbsent(UUID.randomUUID(), claims.issuer(), claims.subject(), claims.email(),
                    claims.displayName(), now);
            return users.findByIdentity(claims.issuer(), claims.subject()).orElseThrow();
        });
        if (!user.active()) {
            throw ApiException.forbidden("user_suspended", "User account is suspended");
        }
        if (!Objects.equals(user.email(), claims.email()) || !Objects.equals(user.displayName(), claims.displayName())) {
            users.updateClaims(user.id(), claims.email(), claims.displayName(), now);
        }
        return new CurrentUser(user.id(), claims.email(), claims.displayName(), claims.platformAdmin());
    }

    @Transactional(readOnly = true)
    public ProfileRow profile(UUID userId) {
        return users.findProfile(userId).orElseThrow(() -> ApiException.notFound("Profile"));
    }

    @Transactional
    public ProfileRow updateProfile(UUID userId, String locale, String timeZone, JsonNode preferences) {
        ProfileRow current = profile(userId);
        List<FieldViolation> violations = new ArrayList<>();
        String newLocale = current.locale();
        if (locale != null) {
            newLocale = normalizeLocale(locale, violations);
        }
        String newTimeZone = current.timeZone();
        if (timeZone != null) {
            newTimeZone = normalizeTimeZone(timeZone, violations);
        }
        ObjectNode newPreferences = current.preferences();
        if (preferences != null) {
            if (!(preferences instanceof ObjectNode object)) {
                violations.add(new FieldViolation("preferences", "type", "preferences must be a JSON object"));
            } else if (json.write(object).length() > MAX_PREFERENCES_BYTES) {
                violations.add(new FieldViolation("preferences", "size", "preferences must not exceed 16 KiB"));
            } else {
                newPreferences = object;
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        users.updateProfile(new ProfileRow(userId, newLocale, newTimeZone, newPreferences, current.version(),
                SqlValues.now(clock)));
        return profile(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findActive(UUID userId) {
        return users.findById(userId).filter(UserRow::active).map(UserService::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> findAll(Collection<UUID> userIds) {
        return users.findByIds(userIds).stream()
                .map(UserService::summary)
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    private static UserSummary summary(UserRow row) {
        return new UserSummary(row.id(), row.email(), row.displayName());
    }

    private static String normalizeLocale(String value, List<FieldViolation> violations) {
        if (value.isBlank()) {
            return null;
        }
        try {
            Locale parsed = new Locale.Builder().setLanguageTag(value.trim()).build();
            if (parsed.getLanguage().isEmpty()) {
                throw new IllformedLocaleException("no language");
            }
            return parsed.toLanguageTag();
        } catch (IllformedLocaleException ex) {
            violations.add(new FieldViolation("locale", "format", "locale must be a BCP 47 language tag"));
            return null;
        }
    }

    private static String normalizeTimeZone(String value, List<FieldViolation> violations) {
        if (value.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(value.trim()).getId();
        } catch (DateTimeException ex) {
            violations.add(new FieldViolation("timeZone", "format", "timeZone must be an IANA time zone ID"));
            return null;
        }
    }
}
