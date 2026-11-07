-- Statutory rates, each with an effective date so past payslips can be reproduced.
-- APPROXIMATE SAMPLE VALUES from memory: a DRC payroll accountant must confirm every one before go-live.

create table statutory_rate (
    id              bigserial primary key,
    code            varchar(40) not null,
    effective_from  date not null,
    value           numeric(14, 4) not null check (value >= 0),
    note            varchar(300),
    created_by      varchar(320) not null default 'system',
    created_at      timestamptz not null default now(),
    unique (code, effective_from)
);

-- Which codes exist, what they mean and their unit. Rates are added, never edited, so history stays.
create table statutory_rate_type (
    code         varchar(40) primary key,
    name         varchar(150) not null,
    unit         varchar(10) not null check (unit in ('PERCENT', 'COUNT', 'CDF')),
    description  varchar(400) not null,
    display_order int not null
);

insert into statutory_rate_type (code, name, unit, description, display_order) values
    ('CNSS_EMPLOYEE',          'CNSS, employee share',            'PERCENT', 'Withheld from elements subject to CNSS.', 10),
    ('CNSS_EMPLOYER',          'CNSS, employer share',            'PERCENT', 'Pensions, family benefits and work accidents, on elements subject to CNSS.', 20),
    ('IPR_CAP',                'IPR cap',                         'PERCENT', 'IPR may not exceed this share of the taxable income.', 30),
    ('IPR_DEPENDANT_REDUCTION','IPR reduction per dependant',     'PERCENT', 'Reduction of the IPR for each dependant in charge.', 40),
    ('IPR_MAX_DEPENDANTS',     'IPR dependants counted, at most', 'COUNT',   'Dependants beyond this number give no further reduction.', 50),
    ('INPP_SMALL',             'INPP, up to 50 staff',            'PERCENT', 'Employer training levy for 1 to 50 employees.', 60),
    ('INPP_MEDIUM',            'INPP, 51 to 300 staff',           'PERCENT', 'Employer training levy for 51 to 300 employees.', 70),
    ('INPP_LARGE',             'INPP, above 300 staff',           'PERCENT', 'Employer training levy above 300 employees.', 80),
    ('ONEM',                   'ONEM',                            'PERCENT', 'Employer contribution to the employment office.', 90),
    ('IERE',                   'IERE',                            'PERCENT', 'Employer tax on the pay of expatriate employees.', 100),
    ('SMIG_DAILY',             'Minimum wage (SMIG), per day',    'CDF',     'Basic pay may not fall below this daily amount; checked, not paid.', 110);

insert into statutory_rate (code, effective_from, value, note) values
    ('CNSS_EMPLOYEE',           '2020-01-01', 5,     'Sample, to verify'),
    ('CNSS_EMPLOYER',           '2020-01-01', 13,    'Sample, to verify'),
    ('IPR_CAP',                 '2020-01-01', 30,    'Sample, to verify'),
    ('IPR_DEPENDANT_REDUCTION', '2020-01-01', 2,     'Sample, to verify'),
    ('IPR_MAX_DEPENDANTS',      '2020-01-01', 9,     'Sample, to verify'),
    ('INPP_SMALL',              '2020-01-01', 3,     'Sample, to verify'),
    ('INPP_MEDIUM',             '2020-01-01', 2,     'Sample, to verify'),
    ('INPP_LARGE',              '2020-01-01', 1,     'Sample, to verify'),
    ('ONEM',                    '2020-01-01', 0.2,   'Sample, to verify'),
    ('IERE',                    '2020-01-01', 25,    'Sample, to verify'),
    ('SMIG_DAILY',              '2020-01-01', 14500, 'Sample, to verify');

-- The IPR barème: annual taxable income bands in CDF. A new set takes effect from its date; the engine
-- annualises the month's taxable income, applies the bands, and divides by 12.
create table ipr_bracket (
    id              bigserial primary key,
    effective_from  date not null,
    lower_bound     numeric(16, 2) not null check (lower_bound >= 0),
    upper_bound     numeric(16, 2) check (upper_bound is null or upper_bound > lower_bound),
    rate_percent    numeric(6, 3) not null check (rate_percent >= 0 and rate_percent <= 100),
    created_by      varchar(320) not null default 'system',
    created_at      timestamptz not null default now(),
    unique (effective_from, lower_bound)
);
insert into ipr_bracket (effective_from, lower_bound, upper_bound, rate_percent) values
    ('2020-01-01', 0,          524160,   0),
    ('2020-01-01', 524160,     1428000,  15),
    ('2020-01-01', 1428000,    2700000,  20),
    ('2020-01-01', 2700000,    4620000,  22.5),
    ('2020-01-01', 4620000,    7260000,  25),
    ('2020-01-01', 7260000,    10260000, 30),
    ('2020-01-01', 10260000,   13908000, 32.5),
    ('2020-01-01', 13908000,   16824000, 35),
    ('2020-01-01', 16824000,   22000000, 37.5),
    ('2020-01-01', 22000000,   null,     40);

-- Company headcount band for INPP, and other payroll settings.
create table payroll_setting (
    key    varchar(40) primary key,
    value  varchar(200) not null
);
insert into payroll_setting (key, value) values
    ('INPP_BAND', 'LARGE'),             -- SMALL, MEDIUM or LARGE: more than 300 employees in the pilot
    ('RATE_DAY', 'LAST_OF_MONTH');      -- which BCC rate a run uses: LAST_OF_MONTH or RUN_DATE
