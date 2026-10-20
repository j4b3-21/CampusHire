package com.bm4a.platform.modules.users;

import java.util.UUID;

/**
 * The authenticated caller as an internal user. Declare it as a controller method parameter to receive it; it is
 * resolved from the validated token and the {@code platform_user} table, never from request input.
 */
public record CurrentUser(UUID id, String email, String displayName, boolean platformAdmin) {
}
