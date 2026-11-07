-- Gross monthly salary history. Phase 2 links it to grades; phase 1 uses it for earnings to date.
create table employee_salary (
    id             bigserial primary key,
    user_id        bigint not null references app_user(id),
    effective_from date not null,
    gross_monthly  numeric(14, 2) not null check (gross_monthly > 0),
    currency       char(3) not null check (currency in ('USD', 'CDF')),
    note           varchar(300),
    created_by     varchar(320) not null,
    created_at     timestamptz not null default now(),
    unique (user_id, effective_from)
);
