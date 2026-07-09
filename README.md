# CampusHire auth module

The CRM uses Keycloak for OAuth2 login, email/password authentication, and logout. The backend stores a local user profile for the signed-in Keycloak subject.

## Module layout

- `modules/auth/controller`
- `modules/auth/dto`
- `modules/auth/entity`
- `modules/auth/repository`
- `modules/auth/service`
- `modules/auth/util`

## Local Keycloak

```bash
cd keycloak
docker compose up --build
```

The realm import creates:

- realm: `campushire`
- client: `campushire-crm`

## Production config

Set these environment variables in production:

- `KEYCLOAK_AUTHORIZATION_URI`
- `KEYCLOAK_TOKEN_URI`
- `KEYCLOAK_USER_INFO_URI`
- `KEYCLOAK_JWK_SET_URI`
- `KEYCLOAK_LOGOUT_URI`
- `KEYCLOAK_CLIENT_ID`
- `APP_CORS_ALLOWED_ORIGINS`
- `APP_POST_LOGIN_REDIRECT_URI`
- `APP_POST_LOGOUT_REDIRECT_URI`

## Auth endpoints

- `GET /auth/login`
- `GET /auth/register`
- `GET /auth/me`
- `POST /auth/logout`
- `POST /auth/logout-all`
