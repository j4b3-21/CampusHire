package com.bm4a.platform.modules.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestJwts;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class AuthenticationIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void missingTokenIsRejectedWithProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.code").value("unauthenticated"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = TestJwts.newUser().expiresAt(Instant.now().minusSeconds(3600)).bearer();
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        String token = TestJwts.newUser().issuer("https://evil.test/realms/bm4a").bearer();
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForAnotherAudienceIsRejected() throws Exception {
        String token = TestJwts.newUser().audience("some-other-client").bearer();
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithForgedSignatureIsRejected() throws Exception {
        String token = TestJwts.newUser().signedWithUntrustedKey().bearer();
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownPathsAreDeniedEvenWhenAuthenticated() throws Exception {
        mvc.perform(get("/internal/anything").header(HttpHeaders.AUTHORIZATION, TestJwts.newUser().bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void validTokenProvisionsStableInternalUserKeyedBySubject() throws Exception {
        TestJwts.Builder alice = TestJwts.newUser();
        String first = mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, alice.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(alice.subject() + "@example.test"))
                .andExpect(jsonPath("$.platformAdmin").value(false))
                .andReturn().getResponse().getContentAsString();
        String firstId = JsonPath.read(first, "$.id");

        // E-mail changes in Keycloak do not change the internal identity.
        String second = mvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, alice.email("renamed@example.test").bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("renamed@example.test"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(firstId);

        // A different subject with the same e-mail is a different user.
        String other = mvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, TestJwts.newUser().email("renamed@example.test").bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(other, "$.id")).isNotEqualTo(firstId);
    }

    @Test
    void platformAdminRoleComesFromRealmRoles() throws Exception {
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, TestJwts.newUser().platformAdmin().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(true));
    }

    @Test
    void profileCanBeUpdatedAndIsValidated() throws Exception {
        String token = TestJwts.newUser().bearer();
        mvc.perform(patch("/api/v1/me/profile").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locale":"en-IN","timeZone":"Asia/Kolkata","preferences":{"theme":"dark"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.locale").value("en-IN"))
                .andExpect(jsonPath("$.profile.timeZone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.profile.preferences.theme").value("dark"));

        mvc.perform(patch("/api/v1/me/profile").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeZone\":\"Mars/Olympus\",\"preferences\":[1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[?(@.field=='timeZone')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='preferences')]").exists());

        mvc.perform(patch("/api/v1/me/profile").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"00000000-0000-0000-0000-000000000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_property"));
    }
}
