-- Countries whose payroll rules are data: Cameroon (CM) and Gabon (GA), the other CEMAC countries to come. A company of
-- such a country lists its contributions and taxes; their rates and ceilings are dated statutory rates, and the flat
-- monthly taxes (Cameroon's RAV and TDL) follow a dated scale. The DRC and the Republic of the Congo keep their own
-- rules in code and have no rows here.

create table payroll_contribution (
    id             bigserial primary key,
    code           varchar(40)  not null,      -- the pay element of its payslip line
    side           varchar(8)   not null check (side in ('EMPLOYEE', 'EMPLOYER')),
    -- What it is worked out on: CNSS = the elements subject to social security, GROSS, TAXABLE = the taxable elements,
    -- NET_TAXABLE = taxable less the employee contributions taken before tax, TAX = the income tax, BASIC = the basic pay.
    base           varchar(12)  not null check (base in ('CNSS', 'GROSS', 'TAXABLE', 'NET_TAXABLE', 'TAX', 'BASIC')),
    rate_code      varchar(40),                -- statutory rate: the percentage
    ceiling_code   varchar(40),                -- statutory rate: the base stops at this monthly amount
    exempt_code    varchar(40),                -- statutory rate: this part of the monthly base is not charged
    scale_code     varchar(40),                -- or a flat amount by band of the base (payroll_scale)
    before_tax     boolean      not null default false,   -- an employee share taken off the taxable pay
    display_order  integer      not null default 0,
    check (rate_code is not null or scale_code is not null)
);
select hrpay_company_table('payroll_contribution');
create unique index payroll_contribution_code on payroll_contribution (company_id, code);

create table payroll_scale (
    id              bigserial primary key,
    code            varchar(40)    not null,
    effective_from  date           not null,
    lower_bound     numeric(18, 2) not null check (lower_bound >= 0),   -- the band holds a base above this
    upper_bound     numeric(18, 2),                                       -- up to and including this; null = no end
    amount          numeric(18, 2) not null check (amount >= 0),          -- a month, in the local currency
    created_by      varchar(320)   not null default 'system',
    check (upper_bound is null or upper_bound > lower_bound)
);
select hrpay_company_table('payroll_scale');
create index payroll_scale_code on payroll_scale (company_id, code, effective_from);

alter table law_change drop constraint law_change_country_code_check;
alter table law_change add constraint law_change_country_code_check check (country_code in ('CD', 'CG', 'CM', 'GA'));
alter table sector_pack drop constraint sector_pack_country_code_check;
alter table sector_pack add constraint sector_pack_country_code_check check (country_code in ('CD', 'CG', 'CM', 'GA'));

alter table declaration drop constraint declaration_kind_check;
alter table declaration add constraint declaration_kind_check
    check (kind in ('CNSS', 'IPR', 'INPP', 'ONEM', 'IERE', 'TUS', 'CNAMGS', 'CAC', 'CFC', 'FNE', 'RAV', 'TDL', 'TCS', 'FNH', 'CFP'));
alter table declaration_rule drop constraint declaration_rule_kind_check;
alter table declaration_rule add constraint declaration_rule_kind_check
    check (kind in ('CNSS', 'IPR', 'INPP', 'ONEM', 'IERE', 'TUS', 'CNAMGS', 'CAC', 'CFC', 'FNE', 'RAV', 'TDL', 'TCS', 'FNH', 'CFP'));
