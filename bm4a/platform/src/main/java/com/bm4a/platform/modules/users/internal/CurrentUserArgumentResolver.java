package com.bm4a.platform.modules.users.internal;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.bm4a.platform.modules.identity.AuthenticatedIdentity;
import com.bm4a.platform.modules.users.CurrentUser;

/**
 * Resolves {@link CurrentUser} controller parameters from the validated token. The resolved user is cached on the
 * request so several parameters or calls within one request hit the database once.
 */
@Configuration(proxyBeanMethods = false)
class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private static final String ATTRIBUTE = CurrentUser.class.getName();

    private final AuthenticatedIdentity identity;
    private final UserService users;

    CurrentUserArgumentResolver(AuthenticatedIdentity identity, UserService users) {
        this.identity = identity;
        this.users = users;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Object cached = request.getAttribute(ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
        if (cached instanceof CurrentUser user) {
            return user;
        }
        CurrentUser user = identity.current()
                .map(users::resolve)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No authenticated identity"));
        request.setAttribute(ATTRIBUTE, user, NativeWebRequest.SCOPE_REQUEST);
        return user;
    }
}
