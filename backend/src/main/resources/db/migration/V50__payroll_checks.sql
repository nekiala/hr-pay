-- Checks before a payroll is approved and closed. The checks themselves are worked out from the calculated payslips
-- each time they are read; only HR's acceptances are kept. The legal ones (net pay below zero, basic pay below the
-- grade's legal minimum) cannot be accepted: they must be fixed. Every other one is fixed or accepted with a reason.
-- A new calculation of the month starts the acceptances over.

set local app.bypass = 'on';

create table payroll_check_acceptance (
    id           bigserial primary key,
    run_id       bigint not null references payroll_run (id) on delete cascade,
    code         varchar(30) not null,
    user_id      bigint not null default 0,          -- 0: a check on the whole run
    reason       varchar(500) not null,
    accepted_by  varchar(320) not null,
    accepted_at  timestamptz not null default now(),
    unique (run_id, code, user_id)
);
select hrpay_company_table('payroll_check_acceptance');
