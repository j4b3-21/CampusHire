# E-mail delivery

The `notifications` module sends all platform e-mail (invitations, `EMAIL` notifications, `Notifier.sendEmail`)
through an **outbox**: business transactions insert rows into `email_outbox`, and a scheduled worker on every API
replica delivers them over SMTP. A rolled-back transaction never sends mail, and an SMTP outage only delays it.

## How delivery works

- Every 5 s (`bm4a.notifications.email.poll-interval`) each replica claims up to 50 due rows with
  `FOR UPDATE SKIP LOCKED` (replicas never claim the same row), marks them `SENDING` with a 10-minute lease and sends
  them over one SMTP connection. A full batch is followed immediately by the next one (up to 20 per run).
- Sent rows become `SENT`; their HTML and text bodies are deleted (they may contain one-time links such as invitation
  tokens). Recipient, subject and timestamps stay for audit.
- A failed attempt is retried with exponential backoff: 1, 2, 4, 8, 16, 32 minutes, then hourly. After the 8th
  attempt the row becomes `FAILED` (bodies are kept for inspection). Rejected recipient addresses fail immediately.
- A replica that dies mid-send leaves rows in `SENDING`; they are claimed again when the lease expires. Delivery is
  therefore at-least-once: in that rare case a recipient can get a message twice.
- Without an SMTP host the worker sends nothing, logs one warning, and rows stay `PENDING` until a host is configured
  and the replicas restart; startup never fails because of mail settings.

## Configuration

| Env variable | Property | Default | Meaning |
| --- | --- | --- | --- |
| `SMTP_HOST` | `bm4a.notifications.email.smtp.host` | (empty: delivery off) | SMTP server |
| `SMTP_PORT` | `...smtp.port` | `587` | `587` with STARTTLS, `465` with `SMTP_SSL=true`, `1025` for Mailpit |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | `...smtp.username` / `password` | (empty: no AUTH) | SMTP AUTH credentials |
| `SMTP_STARTTLS` | `...smtp.starttls` | `true` | Require STARTTLS (set `false` only for Mailpit or a trusted local relay) |
| `SMTP_SSL` | `...smtp.ssl` | `false` | Implicit TLS (port 465) instead of STARTTLS |
| `MAIL_FROM` | `bm4a.notifications.email.from` | SMTP user name if it is an address, else `no-reply@<web host>` | `CampusHire <placements@college.edu>` or a bare address (the display name then is `APP_NAME`) |
| `MAIL_ENABLED` | `bm4a.notifications.email.enabled` | `true` | `false` keeps queueing but never sends |
| `APP_NAME` | `bm4a.app.name` | `BM4A` | Product name in the layout, subjects and the sender name |
| `APP_BASE_URL` | `bm4a.app.base-url` | `http://localhost:3000` | Base of links in e-mail (invitations, notification links) |

TLS certificates are verified (`checkserveridentity`). Connect, read and write timeouts are 10 s
(`...smtp.timeout`). An application that defines its own `JavaMailSender` bean (or sets `spring.mail.host`) is used
when `SMTP_HOST` is empty.

## Development: Mailpit

Run [Mailpit](https://mailpit.axllent.org/) (CampusHire's `infrastructure/` compose already includes it and sets
these variables) and open its web UI to read everything the platform sends:

```bash
docker run -d --name mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit:v1.27
SMTP_HOST=localhost SMTP_PORT=1025 SMTP_STARTTLS=false ./mvnw -pl bm4a/platform spring-boot:run \
  -Dspring-boot.run.profiles=local
# UI: http://localhost:8025   API: GET http://localhost:8025/api/v1/messages
```

## Production with Google

**Google Workspace SMTP relay** (recommended for a college domain on Workspace): an administrator enables it under
*Admin console > Apps > Google Workspace > Gmail > Routing > SMTP relay service*, allowing either the server's public
IP address or SMTP authentication, and requiring TLS.

```bash
SMTP_HOST=smtp-relay.gmail.com SMTP_PORT=587 SMTP_STARTTLS=true
SMTP_USERNAME=placements@college.edu SMTP_PASSWORD=<app password>   # omit both with IP-based allowlisting
MAIL_FROM="CampusHire <placements@college.edu>"
```

**A single Gmail or Workspace mailbox** (`smtp.gmail.com`) needs 2-Step Verification on the account and an
[app password](https://support.google.com/accounts/answer/185833) (administrators can disable app passwords):

```bash
SMTP_HOST=smtp.gmail.com SMTP_PORT=587 SMTP_STARTTLS=true
SMTP_USERNAME=placements@college.edu SMTP_PASSWORD=<16-character app password>
MAIL_FROM="CampusHire <placements@college.edu>"   # must be the account or one of its verified aliases
```

Limits (check Google's current documentation before relying on them): a mailbox sends about 2,000 messages a day on
Workspace and about 500 recipients a day on a consumer Gmail account; the SMTP relay allows about 10,000 recipients
per user per day. Exceeding them returns temporary errors and the outbox keeps retrying with backoff. E-mailing every
student about a drive (~9,000 recipients) exceeds a single mailbox: use the SMTP relay, a transactional mail
provider (Amazon SES, Mailgun, Brevo, Postmark - all plain SMTP), or in-app notifications only.

For any provider, publish SPF, DKIM and DMARC records for the sender domain, otherwise mail lands in spam.

## Operations

```sql
-- Backlog and failures
SELECT status, count(*), min(next_attempt_at) FROM email_outbox GROUP BY status;
SELECT id, to_address, subject, attempts, last_error FROM email_outbox WHERE status = 'FAILED' ORDER BY created_at DESC;
-- Retry failed messages after fixing the cause
UPDATE email_outbox SET status = 'PENDING', attempts = 0, next_attempt_at = now() WHERE status = 'FAILED';
-- Retention: sent rows only hold recipient and subject
DELETE FROM email_outbox WHERE status = 'SENT' AND sent_at < now() - interval '180 days';
```

| Meter | Type | Meaning |
| --- | --- | --- |
| `bm4a.notifications.email{outcome=sent}` | counter | Messages accepted by the SMTP server |
| `bm4a.notifications.email{outcome=retried}` | counter | Failed attempts that will be retried |
| `bm4a.notifications.email{outcome=failed}` | counter | Messages that reached `FAILED` |
| `bm4a.notifications.streams` | gauge | Open notification streams (SSE) on the replica |

Templates live in `templates/bm4a/mail/` (`layout.html`, `generic.html|txt`, `invitation.html|txt`); applications
add their own pairs on the classpath and send them with `EmailMessage.of(subject, template, model)`.
