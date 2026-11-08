-- The allocation de congé gets its own payslip (bulletin d'allocation de congé): numbered when Finance approves it,
-- with CNSS and income tax withheld. The pay it counts is the remuneration of Labour Code art. 7 (basic, salary
-- complement: the prorated elements in the CNSS base), plus the 12-month average of bonuses and overtime. Housing,
-- transport and family allowances are not remuneration: housing and family allowances stay on the monthly payslip.
-- The payslips of the months the leave covers no longer pay those leave days: they add the allocation as an earning
-- (so CNSS and income tax are worked out on the whole month) and take back the net already paid.

set local app.bypass = 'on';

alter table leave_allowance
    add column number           varchar(30),
    add column lines            jsonb not null default '[]',
    add column cnss_base        numeric(14, 2),
    add column taxable_base     numeric(14, 2),
    add column taxable_base_cdf numeric(16, 2),
    add column employee_deductions numeric(14, 2),
    add column net              numeric(14, 2),
    add column employer_charges numeric(14, 2),
    add column usd_cdf          numeric(18, 6),
    add column rate_date        date,
    add column snapshot         jsonb not null default '{}';
create unique index leave_allowance_number_uk on leave_allowance (company_id, number) where number is not null;

-- Each month's share: the allocation's gross (an earning on that month's payslip) and its net paid in advance.
alter table leave_allowance_month rename column variable_amount to gross_amount;

update pay_element set name = case when name like 'Allocation%' then 'Allocation de congé' else 'Leave allowance' end
where code = 'LEAVE_ALLOWANCE' and name in ('Leave allowance (average of bonuses and overtime)',
                                          'Allocation de congé (moyenne des primes et heures supplémentaires)');
