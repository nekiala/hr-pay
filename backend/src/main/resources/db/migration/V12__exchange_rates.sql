-- Central bank (BCC) rates: CDF per unit of a currency, one row per currency and day.
create table exchange_rate (
    id            bigserial primary key,
    currency      char(3) not null,
    rate_date     date not null,
    cdf_per_unit  numeric(18, 6) not null check (cdf_per_unit > 0),
    source        varchar(100) not null,
    source_url    varchar(500),
    entered_by    varchar(320),            -- null when fetched automatically
    fetched_at    timestamptz not null default now(),
    unique (currency, rate_date)
);
