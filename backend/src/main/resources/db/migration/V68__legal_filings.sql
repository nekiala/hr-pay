-- Legal workforce filings and the personnel register.
--
-- DRC: each hiring and each departure is declared to the labour administration and to the ONEM within fifteen days
-- (Code du travail art. 217 as amended by Loi 16/010 du 15 juillet 2016; the older Arrêté 006/CAB/PVPM/ETPS/2010 says
-- 48 hours, so a reminder goes out after two days). Once a year the employer sends the national and foreign workforce
-- it employs and an annual social report, the bilan social (art. 218, Arrêté 005/CAB/PVPM/ETPS/2010: by 31 January).
-- Fines: art. 321. Republic of the Congo: the employer keeps a personnel register, the registre d'employeur, at the
-- workplace for the labour inspector (Code du travail, Loi 45-75, art. 182).
--
-- The due dates are data, one row per country and filing; the filings themselves are made from the employee files,
-- contracts, departures and payslips, so only what was filed (date, reference) is recorded.

create table filing_rule (
    id               bigserial primary key,
    country_code     varchar(2)   not null,
    kind             varchar(16)  not null check (kind in ('HIRE', 'DEPARTURE', 'WORKFORCE', 'BILAN_SOCIAL')),
    authority        varchar(120) not null,
    due_days         int check (due_days > 0),                -- event filings: days after the event
    remind_after_days int check (remind_after_days >= 0),     -- event filings: first reminder, days after the event
    due_month        int check (due_month between 1 and 12),  -- annual filings: the day and month it is due ...
    due_day          int check (due_day between 1 and 31),
    year_offset      int not null default 0 check (year_offset in (0, 1)),  -- ... in the year covered, or the next one
    legal_basis      varchar(400) not null,
    verified         boolean not null default false,
    unique (country_code, kind),
    check ((due_days is null) <> (due_month is null))
);

insert into filing_rule (country_code, kind, authority, due_days, remind_after_days, due_month, due_day, year_offset, legal_basis, verified) values
    ('CD', 'HIRE', 'Inspection du travail, ONEM', 15, 2, null, null, 0,
     'Code du travail art. 217 (Loi 16/010 du 15 juillet 2016): déclaration dans les quinze jours; Arrêté 006/CAB/PVPM/ETPS/2010 du 1er avril 2010 (48 heures); amende art. 321', true),
    ('CD', 'DEPARTURE', 'Inspection du travail, ONEM', 15, 2, null, null, 0,
     'Code du travail art. 217 (Loi 16/010 du 15 juillet 2016): tout départ, pour quelque cause que ce soit, avec sa date; Arrêté 006/CAB/PVPM/ETPS/2010; amende art. 321', true),
    ('CD', 'WORKFORCE', 'Inspection du travail, ONEM', null, null, 1, 31, 0,
     'Code du travail art. 218 (Loi 16/010 du 15 juillet 2016); Arrêté 005/CAB/PVPM/ETPS/2010 du 1er avril 2010: main-d''œuvre nationale et étrangère, au plus tard le 31 janvier; amende art. 321', true),
    ('CD', 'BILAN_SOCIAL', 'Inspection du travail, ONEM', null, null, 1, 31, 1,
     'Code du travail art. 218 (Loi 16/010 du 15 juillet 2016): bilan social annuel. Contenu et date: aucun arrêté trouvé, envoyé avec la déclaration annuelle (à confirmer)', false);

-- What was filed: one row per event or per year.
create table legal_filing (
    id          bigserial primary key,
    kind        varchar(16)  not null check (kind in ('HIRE', 'DEPARTURE', 'WORKFORCE', 'BILAN_SOCIAL')),
    user_id     bigint references app_user (id),
    event_date  date,                 -- the hiring or the last day
    year        int,                  -- an annual filing's year
    filed_on    date         not null,
    reference   varchar(100),
    note        varchar(300),
    filed_by    varchar(320) not null,
    filed_at    timestamptz  not null default now(),
    check ((kind in ('HIRE', 'DEPARTURE')) = (user_id is not null and event_date is not null and year is null)),
    check ((kind in ('WORKFORCE', 'BILAN_SOCIAL')) = (year is not null and user_id is null))
);
select hrpay_company_table('legal_filing');
create unique index legal_filing_event_uk on legal_filing (company_id, kind, user_id, event_date) where user_id is not null;
create unique index legal_filing_year_uk on legal_filing (company_id, kind, year) where year is not null;

-- The register's third part: the labour inspector's visits, their observations and the formal notices.
create table register_visit (
    id           bigserial primary key,
    visited_on   date         not null,
    inspector    varchar(150) not null,
    observations varchar(2000),
    entered_by   varchar(320) not null,
    entered_at   timestamptz  not null default now()
);
select hrpay_company_table('register_visit');

-- From when hirings and departures are declared here (people hired before are not listed), and whether the company
-- keeps its personnel register here: null follows the country (yes in the Republic of the Congo).
alter table company add column filings_from date;
update company set filings_from = current_date;
alter table company alter column filings_from set default current_date;
alter table company alter column filings_from set not null;
alter table company add column personnel_register boolean;
