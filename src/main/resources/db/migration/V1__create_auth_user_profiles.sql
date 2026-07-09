create table auth_user_profiles (
    id uuid primary key,
    keycloak_subject varchar(128) not null unique,
    email varchar(320) not null unique,
    username varchar(150) not null,
    display_name varchar(255),
    authorities_csv varchar(2000) not null default '',
    enabled boolean not null default true,
    last_login_at timestamp null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index idx_auth_user_profiles_email on auth_user_profiles (email);
create index idx_auth_user_profiles_subject on auth_user_profiles (keycloak_subject);
