package com.bm4a.platform.modules.metadata.internal;

import tools.jackson.databind.JsonNode;

/**
 * Module definition input. On update, {@code null} properties are unchanged and {@code apiKey} must be omitted or
 * equal to the current key.
 */
public record ModuleInput(String apiKey, String labelSingular, String labelPlural, String description,
        JsonNode settings) {
}
