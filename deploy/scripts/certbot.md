# Let's Encrypt certificates

nginx serves `/.well-known/acme-challenge/` on port 80 from the `certbot-webroot` volume, so certbot can renew
without stopping the stack. Requirements: a public DNS record for `CAMPUSHIRE_DOMAIN` and port 80 reachable from
the internet.

First issue (the stack needs some certificate to start, so begin with a self-signed one):

```bash
deploy/scripts/gen-self-signed-cert.sh "$CAMPUSHIRE_DOMAIN"
make prod-up

docker run --rm \
  -v campushire-prod_certbot-webroot:/var/www/certbot \
  -v /etc/letsencrypt:/etc/letsencrypt \
  certbot/certbot certonly --webroot -w /var/www/certbot \
  -d "$CAMPUSHIRE_DOMAIN" --email tpo@example.edu --agree-tos --no-eff-email
```

Do not point `CERTS_DIR` at `/etc/letsencrypt/live/<domain>`: its files are symlinks into `../../archive`, which
the container cannot follow. Copy them into `deploy/certs` instead (the renewal cron below does the same):

```bash
sudo install -m 644 /etc/letsencrypt/live/$CAMPUSHIRE_DOMAIN/fullchain.pem deploy/certs/fullchain.pem
sudo install -m 600 /etc/letsencrypt/live/$CAMPUSHIRE_DOMAIN/privkey.pem   deploy/certs/privkey.pem
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec nginx nginx -s reload
```

Renewal (cron, twice a day; certbot only renews within 30 days of expiry):

```cron
17 3,15 * * * cd /opt/campushire && docker run --rm -v campushire-prod_certbot-webroot:/var/www/certbot -v /etc/letsencrypt:/etc/letsencrypt certbot/certbot renew --quiet && install -m 644 /etc/letsencrypt/live/placements.example.edu/fullchain.pem deploy/certs/fullchain.pem && install -m 600 /etc/letsencrypt/live/placements.example.edu/privkey.pem deploy/certs/privkey.pem && docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -T nginx nginx -s reload
```

Institutions with their own CA or a purchased certificate: place `fullchain.pem` (certificate followed by the
intermediate chain) and `privkey.pem` in `deploy/certs/` and reload nginx.
