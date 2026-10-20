package com.bm4a.platform.modules.notifications.internal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateEngineException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.bm4a.platform.modules.notifications.EmailMessage;

/**
 * Renders {@link EmailMessage}s. Uses its own engine (not a bean), so applications keep Spring Boot's Thymeleaf
 * configuration for their views. HTML output is escaped by Thymeleaf; the text part is not markup.
 */
@Component
class MailTemplates {

    static final String PREFIX = "templates/bm4a/mail/";

    record RenderedMail(String subject, String html, String text) {
    }

    private final SpringTemplateEngine engine = new SpringTemplateEngine();
    private final AppBranding branding;

    MailTemplates(AppBranding branding) {
        this.branding = branding;
        engine.setEnableSpringELCompiler(true);
        engine.addTemplateResolver(resolver(TemplateMode.HTML, "*.html", 1));
        engine.addTemplateResolver(resolver(TemplateMode.TEXT, "*.txt", 2));
    }

    RenderedMail render(EmailMessage message) {
        Map<String, Object> model = new LinkedHashMap<>(message.model());
        if (EmailMessage.GENERIC.equals(message.template())) {
            Object actionUrl = model.get("actionUrl");
            if (actionUrl != null) {
                String resolved = branding.resolve(actionUrl.toString());
                if (!resolved.startsWith("https://") && !resolved.startsWith("http://")) {
                    throw new IllegalArgumentException("actionUrl must be an absolute path or an http(s) URL");
                }
                model.put("actionUrl", resolved);
            }
        }
        model.put("appName", branding.name());
        model.put("baseUrl", branding.root());
        model.put("subject", message.subject());
        Context context = new Context(Locale.ENGLISH, model);
        try {
            return new RenderedMail(message.subject(), engine.process(message.template() + ".html", context),
                    engine.process(message.template() + ".txt", context).strip() + "\n");
        } catch (TemplateEngineException ex) {
            throw new IllegalArgumentException("E-mail template '" + message.template() + "' cannot be rendered: "
                    + ex.getMessage(), ex);
        }
    }

    private ClassLoaderTemplateResolver resolver(TemplateMode mode, String pattern, int order) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver(MailTemplates.class.getClassLoader());
        resolver.setPrefix(PREFIX);
        resolver.setTemplateMode(mode);
        resolver.setResolvablePatterns(Set.of(pattern));
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCheckExistence(true);
        resolver.setCacheable(true);
        resolver.setOrder(order);
        return resolver;
    }
}
