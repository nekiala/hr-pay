-- Mobile money payouts through NTPay (https://ntpay.cloud/docs, "Paiements de masse"). A company keeps its own NTPay
-- integration keys (encrypted); a mobile money payment file is sent as one NTPay payment batch, approved in NTPay,
-- and each line's result comes back by signed notifications. NTPay pays from the company's own balance: HR Pay never
-- holds the money.

create table company_ntpay (
    id             bigserial primary key,
    enabled        boolean      not null default false,
    test_mode      boolean      not null default true,
    api_key        text         not null,      -- encrypted
    signature_key  text,                       -- encrypted; checks that notifications come from NTPay
    webhook_token  varchar(64)  not null,      -- in the notification URL, to find the company
    checked_at     timestamptz,
    check_result   varchar(300),
    updated_by     varchar(320) not null,
    updated_at     timestamptz  not null default now()
);
select hrpay_company_table('company_ntpay');
create unique index company_ntpay_one on company_ntpay (company_id);
create unique index company_ntpay_token on company_ntpay (webhook_token);

-- Notifications already handled (NTPay retries with the same id).
create table ntpay_notification (
    id              bigserial primary key,
    notification_id varchar(100) not null,
    received_at     timestamptz  not null default now()
);
select hrpay_company_table('ntpay_notification');
create unique index ntpay_notification_once on ntpay_notification (company_id, notification_id);

alter table payment_batch add column ntpay_batch_code    varchar(40);
alter table payment_batch add column ntpay_reference     varchar(60);
alter table payment_batch add column ntpay_status        varchar(20);   -- NTPay's batch status, or REFUSED / UNKNOWN
alter table payment_batch add column ntpay_finished      boolean not null default false;
alter table payment_batch add column ntpay_attempt       integer not null default 0;
alter table payment_batch add column ntpay_test          boolean;
alter table payment_batch add column ntpay_scheduled_for timestamptz;
alter table payment_batch add column ntpay_sent_by       varchar(320);
alter table payment_batch add column ntpay_sent_at       timestamptz;
alter table payment_batch add column ntpay_checked_at    timestamptz;
alter table payment_batch add column ntpay_error         varchar(1000);
create index payment_batch_ntpay on payment_batch (ntpay_batch_code) where ntpay_batch_code is not null;

alter table payment_line add column ntpay_status           varchar(10);  -- waiting, pending, paid, failed
alter table payment_line add column ntpay_payout_reference varchar(60);
alter table payment_line add column ntpay_error            varchar(300);
