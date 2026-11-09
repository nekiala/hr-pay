-- Sector packs: the rules of a collective agreement (convention collective) laid over the country pack. HR Pay keeps
-- the packs (shared); a company chooses one and applies it after a preview: leave days, overtime rates, seniority
-- bonus, end-of-career payment and other settings. A pack without its agreement's text is a frame: no rules yet.

create table sector_pack (
    code          varchar(40)  primary key check (code ~ '^[A-Z0-9_]{2,40}$'),
    country_code  varchar(2)   not null check (country_code in ('CD', 'CG')),
    name_en       varchar(150) not null,
    name_fr       varchar(150) not null,
    scope_en      varchar(1000),
    scope_fr      varchar(1000),
    reference     varchar(300),
    source_url    varchar(500),
    status        varchar(10)  not null default 'FRAME' check (status in ('FRAME', 'READY', 'RETIRED')),
    verified_by   varchar(150),
    verified_on   date,
    display_order integer      not null default 100,
    updated_by    varchar(150),
    updated_at    timestamptz  not null default now()
);

create table sector_pack_rule (
    id         bigserial    primary key,
    pack_code  varchar(40)  not null references sector_pack (code) on delete cascade on update cascade,
    kind       varchar(12)  not null check (kind in ('SETTING', 'LEAVE_DAYS', 'OVERTIME', 'RATE', 'NOTE')),
    -- SETTING: payroll setting key; LEAVE_DAYS: leave type code; OVERTIME: WEEKDAY, REST_DAY or HOLIDAY;
    -- RATE: statutory rate code; NOTE: none.
    key        varchar(40),
    from_hour  integer,
    to_hour    integer,
    value      varchar(200),
    label_en   varchar(500) not null,
    label_fr   varchar(500) not null,
    article    varchar(100),
    position   integer      not null default 0
);
create index sector_pack_rule_pack on sector_pack_rule (pack_code, position);

create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter', 'audit_chain_head', 'legal_document',
                 'status_incident', 'status_update', 'trial_request', 'platform_mail_server', 'calculator_lead',
                 'platform_whatsapp', 'firm_client', 'firm_client_staff', 'firm_visit', 'firm_commission',
                 'law_change', 'law_change_rate', 'sector_pack', 'sector_pack_rule']
$$;

alter table company add column sector_pack_code varchar(40) references sector_pack (code) on update cascade;
alter table company add column sector_pack_applied_at timestamptz;
alter table company add column sector_pack_applied_by varchar(150);

-- A change on the law page can concern one sector only: its rates go to the companies on that pack.
alter table law_change add column sector_pack_code varchar(40) references sector_pack (code) on update cascade;

-- ---- the DRC interprofessional agreement ----------------------------------------------------------------------------

insert into sector_pack (code, country_code, name_en, name_fr, scope_en, scope_fr, reference, source_url, status, display_order) values
('CD_INTERPRO', 'CD', 'Interprofessional agreement (all sectors)', 'Convention collective interprofessionnelle (toutes branches)',
 'Every employer and worker in the DRC not covered by a better branch or company agreement; cadres de direction are excluded (art. 14).',
 'Tous les employeurs et travailleurs de la RDC qui n''ont pas de convention de branche ou d''entreprise plus favorable ; les cadres de direction sont exclus (art. 14).',
 'Convention collective interprofessionnelle du travail du 20 janvier 2006',
 'https://www.droitcongolais.info/files/827.01.06c-Convention-collective-interprofessionnelle-du-travail-du-20-janvier-2006.pdf',
 'READY', 10),
('CD_MINING', 'CD', 'Mining', 'Mines', null, null, null, null, 'FRAME', 20),
('CD_BANKS', 'CD', 'Banks', 'Banques', null, null, null, null, 'FRAME', 30),
('CG_OIL', 'CG', 'Oil and gas', 'Pétrole', null, null, null, null, 'FRAME', 20),
('CG_BANKS', 'CG', 'Banks and insurance', 'Banques et assurances', null, null, 'Convention collective des banques (2011)', null, 'FRAME', 30),
('CG_COMMERCE', 'CG', 'Commerce', 'Commerce', null, null, null, null, 'FRAME', 40);

insert into sector_pack_rule (pack_code, kind, key, from_hour, to_hour, value, label_en, label_fr, article, position) values
('CD_INTERPRO', 'SETTING', 'LEAVE_MIN_DAYS_MONTH', null, null, '1.5',
 'Annual leave: 1.5 working days per month of service (18 a year)', 'Congé annuel : 1,5 jour ouvrable par mois de service (18 par an)', 'art. 22', 10),
('CD_INTERPRO', 'SETTING', 'LEAVE_MIN_DAYS_MONTH_MINOR', null, null, '2',
 'Annual leave under 18: 2 working days per month', 'Congé annuel des moins de 18 ans : 2 jours ouvrables par mois', 'art. 22', 20),
('CD_INTERPRO', 'SETTING', 'LEAVE_SENIORITY_YEARS', null, null, '5',
 'Extra leave for seniority: every 5 years with the same employer', 'Congé d''ancienneté : tous les 5 ans chez le même employeur', 'art. 22', 30),
('CD_INTERPRO', 'SETTING', 'LEAVE_SENIORITY_DAYS', null, null, '2',
 'Extra leave for seniority: 2 days per 5 years', 'Congé d''ancienneté : 2 jours par tranche de 5 ans', 'art. 22', 40),
('CD_INTERPRO', 'LEAVE_DAYS', 'FAMILY_MARRIAGE', null, null, '3',
 'Family event: own marriage, 3 days', 'Circonstance : mariage du travailleur, 3 jours', 'art. 22', 50),
('CD_INTERPRO', 'LEAVE_DAYS', 'FAMILY_BIRTH', null, null, '2',
 'Family event: wife gives birth, 2 days', 'Circonstance : accouchement de l''épouse, 2 jours', 'art. 22', 60),
('CD_INTERPRO', 'LEAVE_DAYS', 'FAMILY_CHILD_MARRIAGE', null, null, '1',
 'Family event: marriage of a child, 1 day', 'Circonstance : mariage d''un enfant, 1 jour', 'art. 22', 70),
('CD_INTERPRO', 'LEAVE_DAYS', 'FAMILY_DEATH_CLOSE', null, null, '6',
 'Family event: death of the spouse or a 1st-degree relative, 6 days', 'Circonstance : décès du conjoint ou d''un parent au 1er degré, 6 jours', 'art. 22', 80),
('CD_INTERPRO', 'LEAVE_DAYS', 'FAMILY_DEATH_OTHER', null, null, '2',
 'Family event: death of a 2nd-degree relative, 2 days', 'Circonstance : décès d''un parent au 2e degré, 2 jours', 'art. 22', 90),
('CD_INTERPRO', 'SETTING', 'LEAVE_CIRCUMSTANCE_YEAR_MAX', null, null, '18',
 'Family events: at most 18 days a year', 'Circonstances : 18 jours au plus par an', 'art. 22', 100),
('CD_INTERPRO', 'OVERTIME', 'WEEKDAY', 0, 2, '1.3',
 'Overtime: first 2 hours beyond the legal week, +30 %', 'Heures supplémentaires : 2 premières heures au-delà de la durée légale, +30 %', 'art. 23', 110),
('CD_INTERPRO', 'OVERTIME', 'WEEKDAY', 2, null, '1.6',
 'Overtime: from the 3rd hour, +60 %', 'Heures supplémentaires : à partir de la 3e heure, +60 %', 'art. 23', 120),
('CD_INTERPRO', 'OVERTIME', 'REST_DAY', 0, null, '2',
 'Overtime on the weekly rest day, +100 %', 'Heures supplémentaires le jour de repos, +100 %', 'art. 23', 130),
('CD_INTERPRO', 'OVERTIME', 'HOLIDAY', 0, null, '2',
 'Overtime on a public holiday, +100 %', 'Heures supplémentaires un jour férié, +100 %', 'art. 23', 140),
('CD_INTERPRO', 'NOTE', null, null, null, null,
 'Work accident or occupational illness off work more than 30 days: the employer adds one third of the pay for 3 months. Enter it as a one-off bonus.',
 'Accident du travail ou maladie professionnelle de plus de 30 jours : l''employeur ajoute un tiers du salaire pendant 3 mois. À saisir comme prime ponctuelle.',
 'art. 22', 150),
('CD_INTERPRO', 'NOTE', null, null, null, null,
 'Left to branch or company agreements: seniority bonus, extra family allowances, housing, transport, 13th month, leave bonus, notice and end-of-career payment. Set them here if the company has one.',
 'Laissés aux conventions de branche ou d''entreprise : prime d''ancienneté, allocations familiales en plus, logement, transport, 13e mois, pécule de congé, préavis et indemnité de fin de carrière. À régler ici si l''entreprise en a une.',
 'art. 20, 21, 24, 25, 29, 30, 31, 37', 160),
('CD_INTERPRO', 'NOTE', null, null, null, null,
 'Retirement: the employer gives one year''s notice of the retirement date.',
 'Retraite : l''employeur prévient le travailleur un an avant la date de mise à la retraite.',
 'art. 37', 170);

-- ---- the pay elements the packs use --------------------------------------------------------------------------------

insert into pay_element (company_id, code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                         system, display_order)
select c.id, 'SENIORITY_BONUS',
       case when c.language = 'fr' then 'Prime d''ancienneté' else 'Seniority bonus' end,
       'EARNING', 'COMPUTED', true, true, true, false, true, 16
from company c
where exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC')
  and not exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'SENIORITY_BONUS');

insert into pay_element (company_id, code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                         system, display_order)
select c.id, 'END_OF_CAREER',
       case when c.language = 'fr' then 'Indemnité de fin de carrière' else 'End-of-career payment' end,
       'EARNING', 'COMPUTED', false, false, false, false, true, 85
from company c
where exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'BASIC')
  and not exists (select 1 from pay_element p where p.company_id = c.id and p.code = 'END_OF_CAREER');

-- Off (0) until a pack or HR sets them.
insert into payroll_setting (company_id, key, value)
select c.id, s.key, '0'
from company c, (values ('SENIORITY_BONUS_PERCENT_PER_YEAR'), ('SENIORITY_BONUS_MAX_PERCENT'), ('SENIORITY_BONUS_FROM_YEARS'),
                        ('END_OF_CAREER_DAYS_PER_YEAR'), ('END_OF_CAREER_MAX_MONTHS')) as s(key)
where exists (select 1 from payroll_setting p where p.company_id = c.id)
on conflict do nothing;

alter table departure add column end_of_career_amount numeric(14, 2) not null default 0;
