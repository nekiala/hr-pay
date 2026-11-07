-- Phase 7: leave paid at a share per type, half days, seniority days, and supporting documents checked by HR.

-- Share of the day's pay a leave type keeps (100 = full pay, 0 = unpaid). "paid" stays as pay_percent > 0.
alter table leave_type add column pay_percent numeric(5, 2) not null default 100 check (pay_percent between 0 and 100);
update leave_type set pay_percent = case when paid then 100 else 0 end;
-- Sample: sick leave at two thirds of pay, to confirm with a payroll specialist.
update leave_type set pay_percent = 66.67 where code = 'SICK';

-- A one-day request can cover the morning or the afternoon only.
alter table leave_request add column half_day varchar(2) check (half_day in ('AM', 'PM'));
alter table leave_request add constraint leave_request_half_day_one_day check (half_day is null or start_date = end_date);

-- Kinds that need a document: the manager approves, then HR checks the document before the leave counts.
alter table leave_request drop constraint leave_request_status_check;
alter table leave_request alter column status type varchar(12);
alter table leave_request add constraint leave_request_status_check
    check (status in ('PENDING', 'AWAITING_HR', 'APPROVED', 'REJECTED', 'CANCELLED'));
alter table leave_request add column document_checked_by varchar(320);
alter table leave_request add column document_checked_at timestamptz;

create table leave_document (
    id                bigserial primary key,
    leave_request_id  bigint not null references leave_request (id) on delete cascade,
    file_name         varchar(200) not null,
    content_type      varchar(100) not null,
    size_bytes        integer not null,
    data              bytea not null,
    uploaded_by       varchar(320) not null,
    uploaded_at       timestamptz not null default now()
);
create index leave_document_request on leave_document (leave_request_id);

-- Extra annual leave days with seniority (sample DRC rule: 1 more day per 5 full years of service).
insert into payroll_setting (key, value) values
    ('LEAVE_SENIORITY_YEARS', '5'),
    ('LEAVE_SENIORITY_DAYS', '1');

-- Evaluation templates can also apply to a unit and every unit below it (the most specific template wins).
alter table evaluation_template add column org_unit_id bigint references org_unit (id);
