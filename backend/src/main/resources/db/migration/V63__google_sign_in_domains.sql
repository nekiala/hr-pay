-- Sign in with Google is accepted only from a company's own Google Workspace: the account's organisation domain
-- (Google's "hd" claim, absent for personal Gmail accounts) and the email's domain must both be in this list.
-- Google checks who owns a domain before a Workspace may use it, so the administrator only names it here.
-- A domain belongs to one company on the whole installation.
create table company_sign_in_domain (
    id          bigserial primary key,
    domain      varchar(253) not null check (domain = lower(domain) and domain ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$'),
    created_by  varchar(320),
    created_at  timestamptz  not null default now(),
    constraint company_sign_in_domain_unique unique (domain)
);
select hrpay_company_table('company_sign_in_domain');

-- People whose email is on one of these domains must sign in with Google; their password is refused.
alter table company add column google_only boolean not null default false;
