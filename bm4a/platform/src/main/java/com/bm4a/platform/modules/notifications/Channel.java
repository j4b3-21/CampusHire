package com.bm4a.platform.modules.notifications;

/**
 * Delivery channels of {@link Notifier#notify}.
 * <ul>
 * <li>{@code IN_APP} - a row in the recipient's notification list, pushed to their open streams</li>
 * <li>{@code EMAIL} - an e-mail from the {@code generic} template to the recipient's account address</li>
 * </ul>
 */
public enum Channel {
    IN_APP, EMAIL
}
