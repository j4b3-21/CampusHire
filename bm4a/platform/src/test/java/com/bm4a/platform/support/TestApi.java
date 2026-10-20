package com.bm4a.platform.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.UnsupportedEncodingException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

/**
 * Thin MockMvc wrapper that sends real bearer tokens, so every call goes through the production security chain.
 */
public final class TestApi {

    private final MockMvc mvc;

    public TestApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    public Caller as(TestJwts.Builder identity) {
        return new Caller(identity);
    }

    public Caller newUser() {
        return as(TestJwts.newUser());
    }

    public Caller newPlatformAdmin() {
        return as(TestJwts.newUser().platformAdmin());
    }

    public static <T> T read(MvcResult result, String path) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), path);
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String etag(MvcResult result) {
        return result.getResponse().getHeader(HttpHeaders.ETAG);
    }

    public final class Caller {

        private final TestJwts.Builder identity;
        private String userId;

        private Caller(TestJwts.Builder identity) {
            this.identity = identity;
        }

        /** Internal user ID (provisions the user on first call). */
        public String id() throws Exception {
            if (userId == null) {
                userId = read(get("/api/v1/me").andExpect(status().isOk()).andReturn(), "$.id");
            }
            return userId;
        }

        public ResultActions get(String url, Object... vars) throws Exception {
            return perform(MockMvcRequestBuilders.get(url, vars), null);
        }

        public ResultActions getWithHeaders(String url, HttpHeaders extra, Object... vars) throws Exception {
            return perform(MockMvcRequestBuilders.get(url, vars).headers(extra), null);
        }

        public ResultActions post(String url, String json, Object... vars) throws Exception {
            return perform(json(MockMvcRequestBuilders.post(url, vars), json), null);
        }

        public ResultActions postIfMatch(String url, String ifMatch, Object... vars) throws Exception {
            return perform(MockMvcRequestBuilders.post(url, vars), ifMatch);
        }

        public ResultActions postWithIfMatch(String url, String ifMatch, String json, Object... vars) throws Exception {
            return perform(json(MockMvcRequestBuilders.post(url, vars), json), ifMatch);
        }

        public ResultActions patch(String url, String ifMatch, String json, Object... vars) throws Exception {
            return perform(json(MockMvcRequestBuilders.patch(url, vars), json), ifMatch);
        }

        public ResultActions put(String url, String ifMatch, String json, Object... vars) throws Exception {
            return perform(json(MockMvcRequestBuilders.put(url, vars), json), ifMatch);
        }

        public ResultActions delete(String url, String ifMatch, Object... vars) throws Exception {
            return perform(MockMvcRequestBuilders.delete(url, vars), ifMatch);
        }

        /** {@code multipart/form-data} POST; parts typed {@code application/json} bind to {@code @RequestPart} objects. */
        public ResultActions multipart(String url, List<MockMultipartFile> parts, Object... vars) throws Exception {
            MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart(url, vars);
            parts.forEach(request::file);
            request.header(HttpHeaders.AUTHORIZATION, identity.bearer());
            return mvc.perform(request);
        }

        private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String json) {
            return request.contentType(MediaType.APPLICATION_JSON).content(json);
        }

        private ResultActions perform(MockHttpServletRequestBuilder request, String ifMatch) throws Exception {
            request.header(HttpHeaders.AUTHORIZATION, identity.bearer());
            if (ifMatch != null) {
                request.header(HttpHeaders.IF_MATCH, ifMatch);
            }
            return mvc.perform(request);
        }
    }
}
