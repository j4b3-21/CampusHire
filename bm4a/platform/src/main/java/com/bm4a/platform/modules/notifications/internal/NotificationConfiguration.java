package com.bm4a.platform.modules.notifications.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ NotificationProperties.class, AppBranding.class })
class NotificationConfiguration {
}
