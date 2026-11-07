-- Password recovery: a one-time link sent by email, asked for from the sign-in page or sent by the provider. Only the
-- hash of the link's token is kept; a link lasts 30 minutes and works once.
create table password_reset (
    id           bigserial primary key,
    user_id      bigint not null references app_user (id),
    token_hash   char(64) not null,
    expires_at   timestamptz not null,
    used_at      timestamptz,
    requested_by varchar(320),
    requested_ip varchar(64),
    created_at   timestamptz not null default now()
);
select hrpay_company_table('password_reset');
create unique index password_reset_token_uk on password_reset (token_hash);
create index password_reset_user on password_reset (user_id, created_at desc);
