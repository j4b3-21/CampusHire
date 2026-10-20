package com.bm4a.platform.modules.tenancy.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bm4a.platform.modules.tenancy.InvitationMail;
import com.bm4a.platform.modules.tenancy.InvitationMailer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvitationProperties.class)
class InvitationConfiguration {

    /** Default when the application provides no mailer: records that an invitation exists, never the link. */
    @Bean
    @ConditionalOnMissingBean(InvitationMailer.class)
    InvitationMailer loggingInvitationMailer() {
        Logger log = LoggerFactory.getLogger(InvitationMailer.class);
        return (InvitationMail mail) -> log.info(
                "Invitation {} to organization {} for {} as '{}' (expires {}); no InvitationMailer is configured, "
                        + "so no e-mail was sent", mail.invitationId(), mail.organizationName(),
                InvitationService.maskEmail(mail.email()), mail.roleLabel(), mail.expiresAt());
    }
}
