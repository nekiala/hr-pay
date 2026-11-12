-- Pay period per grade category: the calendar month (default) or three decades. Decades are tied to the calendar
--   (1 to 10, 11 to 20, 21 to the last day); the category chooses the day its first decade starts (1, 11 or 21).
--   With 21, the November payroll takes its attendance from 21 October to 20 November. The salary, IPR/ITS, CNSS and
--   declarations stay on the calendar month: only absences, unpaid leave, overtime and days-worked allowances follow
--   the period. Each payslip keeps the attendance period it used, so the next one starts the day after.

set local app.bypass = 'on';

alter table grade_category
    add column pay_period varchar(10) not null default 'MONTH' check (pay_period in ('MONTH', 'DECADES')),
    add column first_decade_day smallint not null default 1 check (first_decade_day in (1, 11, 21));

alter table payslip
    add column var_from date,
    add column var_to date;
