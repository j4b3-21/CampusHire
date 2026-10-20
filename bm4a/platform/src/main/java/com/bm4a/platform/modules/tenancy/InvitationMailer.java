package com.bm4a.platform.modules.tenancy;

/**
 * Delivers invitation e-mails. Called after the invitation's transaction committed (a rolled-back invitation never
 * sends mail), in the request thread; failures are logged and do not undo the invitation (it can be revoked and sent
 * again). The platform's default implementation only logs that an invitation was created, without the link.
 * Applications provide their own bean to send real mail.
 */
public interface InvitationMailer {

    void send(InvitationMail mail);
}
