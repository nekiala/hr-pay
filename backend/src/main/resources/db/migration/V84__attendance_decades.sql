-- Closing the attendance decade by decade (1 to 10, 11 to 20, 21 to the end of the month). Once HR closes a decade,
--   its clock entries, corrections, overtime decisions and leave approvals are locked; reopening it takes a reason.
--   No row means the decade is open. Permission attendance.decade.close (HR and admin by default).

set local app.bypass = 'on';

insert into permission (code, category, description) values
    ('attendance.decade.close', 'attendance', 'Close and reopen attendance decades, print the decade sheets')
on conflict do nothing;
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'attendance.decade.close' from role r where r.code in ('ADMIN', 'HR')
on conflict do nothing;

create table attendance_decade (
    id             bigserial primary key,
    start_date     date not null check (extract(day from start_date) in (1, 11, 21)),
    end_date       date not null check (end_date >= start_date),
    status         varchar(6) not null check (status in ('CLOSED', 'OPEN')),
    closed_by      varchar(320),
    closed_at      timestamptz,
    reopened_by    varchar(320),
    reopened_at    timestamptz,
    reopen_reason  varchar(500)
);
select hrpay_company_table('attendance_decade');
create unique index attendance_decade_uk on attendance_decade (company_id, start_date);
