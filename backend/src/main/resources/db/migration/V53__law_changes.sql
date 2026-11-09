-- Law and rate changes: a dated public record of each legal change HR Pay follows, written by the provider. A change
-- may carry dated rates; publishing it adds them to every company of its country, which may still change them.

create table law_change (
    id              bigserial primary key,
    country_code    char(2) not null check (country_code in ('CD', 'CG')),
    effective_from  date not null,
    category        varchar(12) not null check (category in ('TAX', 'SOCIAL', 'WAGE', 'LABOUR', 'DECLARATION')),
    title_en        varchar(200) not null,
    title_fr        varchar(200) not null,
    summary_en      varchar(2000) not null,
    summary_fr      varchar(2000) not null,
    reference       varchar(300) not null,
    source_url      varchar(500),
    -- APPLIED: HR Pay does it; COMING: HR Pay will; INFO: nothing to do in HR Pay.
    in_app          varchar(8) not null default 'APPLIED' check (in_app in ('APPLIED', 'COMING', 'INFO')),
    in_app_note_en  varchar(500),
    in_app_note_fr  varchar(500),
    verified_by     varchar(200),
    verified_on     date,
    status          varchar(10) not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    published_by    varchar(320),
    published_at    timestamptz,
    applied_to      int not null default 0,
    created_by      varchar(320) not null default 'system',
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);
create index law_change_list on law_change (status, effective_from desc);

create table law_change_rate (
    id              bigserial primary key,
    change_id       bigint not null references law_change (id) on delete cascade,
    code            varchar(40) not null,
    effective_from  date not null,
    value           numeric(14, 4) not null check (value >= 0),
    unique (change_id, code, effective_from)
);

create or replace function hrpay_shared_tables() returns text[] language sql immutable as $$
    select array['company', 'plan', 'installation', 'permission', 'exchange_rate', 'spring_session',
                 'spring_session_attributes', 'job_lock', 'backup_run', 'system_alert', 'login_failure',
                 'flyway_schema_history', 'billing_setting', 'invoice_counter', 'audit_chain_head', 'legal_document',
                 'status_incident', 'status_update', 'trial_request', 'platform_mail_server', 'calculator_lead',
                 'platform_whatsapp', 'firm_client', 'firm_client_staff', 'firm_visit', 'firm_commission',
                 'law_change', 'law_change_rate']
$$;

-- Which change a company's rate came from, so the rates page can show its source.
alter table statutory_rate add column law_change_id bigint references law_change (id) on delete set null;

-- ---- the changes already in HR Pay -----------------------------------------------------------------------------

insert into law_change (id, country_code, effective_from, category, title_en, title_fr, summary_en, summary_fr, reference,
                        source_url, in_app, in_app_note_en, in_app_note_fr, verified_by, verified_on, status, published_by,
                        published_at) values
 (1, 'CD', '2025-05-01', 'WAGE', 'Minimum wage (SMIG): 14,500 FC a day', 'SMIG : 14 500 FC par jour',
  'The daily guaranteed minimum wage rises to 14,500 FC from the May 2025 pay. Each category''s minimum is the SMIG times its coefficient (annex 2), a month counting 26 days. Family allowances are at least 1/27 of the daily SMIG per child and day; the housing allowance at least 1/5 of that.',
  'Le SMIG journalier passe à 14 500 FC à partir de la paie de mai 2025. Le minimum de chaque catégorie est le SMIG multiplié par son coefficient (annexe 2), le mois comptant 26 jours. Les allocations familiales valent au moins 1/27 du SMIG journalier par enfant et par jour ; la contre-valeur du logement au moins 1/5 de ce montant.',
  'Décret n° 25/22 du 30 mai 2025, art. 3, 5, 6 et 7 (JO 28 octobre 2025)', 'https://annuairetravail-rdc.cd',
  'APPLIED', 'Rate SMIG_DAILY; each grade''s legal minimum is checked before approval.',
  'Taux SMIG_DAILY ; le minimum légal de chaque grade est contrôlé avant l''approbation.', null, null,
  'PUBLISHED', 'system', now()),
 (2, 'CD', '2026-01-01', 'WAGE', 'Minimum wage (SMIG): 21,500 FC a day', 'SMIG : 21 500 FC par jour',
  'Second step of the decree: the daily SMIG is 21,500 FC from January 2026, with the same coefficients.',
  'Deuxième palier du décret : le SMIG journalier est de 21 500 FC à partir de janvier 2026, avec les mêmes coefficients.',
  'Décret n° 25/22 du 30 mai 2025, art. 3', 'https://annuairetravail-rdc.cd', 'APPLIED',
  'Rate SMIG_DAILY from 1 January 2026; HR can raise grades to the legal minimum in one step.',
  'Taux SMIG_DAILY au 1er janvier 2026 ; les RH peuvent relever les grades au minimum légal en une fois.', null, null,
  'PUBLISHED', 'system', now()),
 (3, 'CD', '2025-10-01', 'SOCIAL', 'ONEM: 0.5 % of gross pay', 'ONEM : 0,5 % de la rémunération brute',
  'The employer contribution to the national employment office goes from 0.2 % to 0.5 % of gross pay.',
  'La contribution patronale à l''Office national de l''emploi passe de 0,2 % à 0,5 % de la rémunération brute.',
  'Arrêté n° 028/CAB/MIN.ET/FMM/RK/09/2025 du 24 septembre 2025', null, 'APPLIED',
  'Rate ONEM from the October 2025 payroll.', 'Taux ONEM à partir de la paie d''octobre 2025.', null, null,
  'PUBLISHED', 'system', now()),
 (4, 'CD', '2026-01-01', 'TAX', 'IRPP replaces the IPR scale on salaries', 'L''IRPP remplace le barème de l''IPR sur les salaires',
  'Annual bands: 3 % up to 1,944,000 FC, 15 % up to 21,600,000 FC, 30 % up to 43,200,000 FC, 40 % above, the tax capped at 30 % of the taxable income.',
  'Tranches annuelles : 3 % jusqu''à 1 944 000 FC, 15 % jusqu''à 21 600 000 FC, 30 % jusqu''à 43 200 000 FC, 40 % au-delà, l''impôt étant plafonné à 30 % du revenu imposable.',
  'Loi n° 23/053 du 30 novembre 2023; loi de finances 2026 (Loi n° 25/060 du 29 décembre 2025)', 'https://www.dgi.gouv.cd',
  'APPLIED', 'Tax scale dated 1 January 2026.', 'Barème daté du 1er janvier 2026.', null, null, 'PUBLISHED', 'system', now()),
 (5, 'CD', '2026-01-01', 'TAX', 'Minimum tax on salaries: 2,500 FC a month', 'Impôt minimum sur les salaires : 2 500 FC par mois',
  'When tax is due on a salary, the amount withheld is never less than 2,500 FC a month.',
  'Lorsque l''impôt est dû sur un salaire, la retenue n''est jamais inférieure à 2 500 FC par mois.',
  'DGI, IRPP sur les revenus salariaux (loi de finances 2026)', 'https://www.dgi.gouv.cd', 'APPLIED',
  'Rate IPR_MIN_MONTHLY.', 'Taux IPR_MIN_MONTHLY.', null, null, 'PUBLISHED', 'system', now()),
 (6, 'CG', '2026-01-01', 'TAX', 'ITS 2026: new scale per family part', 'ITS 2026 : nouveau barème par part',
  'Yearly scale per part: a flat 1,200 FCFA up to 615,000; 10 % up to 1,500,000; 15 % up to 3,500,000; 20 % up to 5,000,000; 30 % above. Pay below the SMIG owes at least 1,200 FCFA a year. Withheld monthly, the net rounded down to the thousand. Parts: single parent with one child 2 parts, then half a part per child, 6.5 at most.',
  'Barème annuel par part : forfait de 1 200 FCFA jusqu''à 615 000 ; 10 % jusqu''à 1 500 000 ; 15 % jusqu''à 3 500 000 ; 20 % jusqu''à 5 000 000 ; 30 % au-delà. Un salaire inférieur au SMIG doit au moins 1 200 FCFA par an. Retenue mensuelle, revenu arrondi au millier inférieur. Parts : parent isolé avec un enfant 2 parts, puis une demi-part par enfant, 6,5 au plus.',
  'Loi n° 42-2025 du 31 décembre 2025 (loi de finances 2026), CGI art. 116 A à 116 G', null, 'APPLIED',
  'Rates ITS_FIRST_BAND_FLAT and ITS_MINIMUM_YEARLY, scale and parts in the ITS calculation.',
  'Taux ITS_FIRST_BAND_FLAT et ITS_MINIMUM_YEARLY, barème et parts dans le calcul de l''ITS.', null, null,
  'PUBLISHED', 'system', now()),
 (7, 'CG', '2026-01-01', 'TAX', 'Allowances taxable, housing included', 'Indemnités imposables, logement compris',
  'All allowances are taxable, the housing allowance included; benefits in kind are valued at fixed shares of the capped salary (housing 20 %, food 20 %, staff 7 %, utilities 5 %, car 3 %, phone 2 %). Transport is exempt when given to all staff.',
  'Toutes les indemnités sont imposables, l''indemnité de logement comprise ; les avantages en nature sont évalués à des parts fixes du salaire plafonné (logement 20 %, nourriture 20 %, domesticité 7 %, eau et électricité 5 %, voiture 3 %, téléphone 2 %). Le transport est exonéré s''il est accordé à tout le personnel.',
  'Loi n° 42-2025 du 31 décembre 2025, CGI art. 114 A et 115', null, 'APPLIED',
  'Housing is taxable in the Congo pack.', 'Le logement est imposable dans le paramétrage Congo.', null, null,
  'PUBLISHED', 'system', now()),
 (8, 'CG', '2026-01-01', 'DECLARATION', 'ITS paid by the 15th, 50 % penalty when late', 'ITS versé avant le 15, pénalité de 50 % en cas de retard',
  'Tax withheld in a month is paid within the first 15 days of the next; employers paying 5 people or fewer pay each quarter. Paid late, it costs 50 % of the sum, 100 % beyond two months. A monthly statement is due from employers of more than 3 people, the annual statement of salaries in January.',
  'L''impôt retenu au cours d''un mois est versé dans les 15 premiers jours du mois suivant ; les employeurs payant 5 personnes au plus versent chaque trimestre. Versé en retard, il coûte 50 % de la somme, 100 % au-delà de deux mois. Une déclaration mensuelle est due au-delà de 3 salariés, la déclaration annuelle des salaires en janvier.',
  'Code général des impôts, art. 173, 176, 177 bis et 512', null, 'APPLIED',
  'Due dates, reminders and penalty estimate in the declarations calendar.',
  'Échéances, rappels et estimation des pénalités dans le calendrier des déclarations.', null, null,
  'PUBLISHED', 'system', now()),
 (9, 'CD', '2026-01-01', 'DECLARATION', 'Declarations and payment by the 15th', 'Déclaration et versement avant le 15',
  'Tax withheld in a month is declared and paid within 15 days, even when nothing is due. A late declaration adds 25 %; a late payment 2 % for each month started. One form for IPR, CNSS, INPP and ONEM at the guichet unique.',
  'L''impôt retenu au cours d''un mois est déclaré et versé dans les 15 jours, même néant. Une déclaration tardive ajoute 25 % ; un versement tardif 2 % par mois commencé. Un seul formulaire pour l''IPR, la CNSS, l''INPP et l''ONEM au guichet unique.',
  'Loi n° 004/2003, art. 17, 18, 89 et 91; arrêté interministériel du 12 mai 2015 (guichet unique)', null,
  'APPLIED', 'Due dates, reminders and penalty estimate in the declarations calendar.',
  'Échéances, rappels et estimation des pénalités dans le calendrier des déclarations.', null, null,
  'PUBLISHED', 'system', now()),
 -- Drafts to confirm before they are published.
 (10, 'CG', '2025-01-01', 'WAGE', 'Minimum wage (SMIG): 70,400 FCFA a month', 'SMIG : 70 400 FCFA par mois',
  'The guaranteed minimum wage would be 70,400 FCFA a month from January 2025 (secondary source; the decree''s text could not be read).',
  'Le SMIG serait de 70 400 FCFA par mois à partir de janvier 2025 (source secondaire ; le texte du décret n''a pas pu être lu).',
  'Décret n° 2024-2762 du 20 novembre 2024 (JO n° 51-2024, p. 1563)', null, 'COMING',
  'HR Pay uses 90,000 FCFA until this is confirmed.', 'HR Pay utilise 90 000 FCFA jusqu''à confirmation.', null, null,
  'DRAFT', null, null),
 (11, 'CG', '2024-01-01', 'SOCIAL', 'CAMU: universal health insurance contributions', 'CAMU : cotisations à l''assurance maladie universelle',
  'Contributions to the universal health insurance fund: 2.27 % for the employee and 4.55 % for the employer (to confirm).',
  'Cotisations à la caisse d''assurance maladie universelle : 2,27 % pour le salarié et 4,55 % pour l''employeur (à confirmer).',
  'Décret n° 2024-133 (à confirmer)', null, 'COMING', 'Not in the Congo payroll yet.', 'Pas encore dans la paie Congo.',
  null, null, 'DRAFT', null, null);
select setval('law_change_id_seq', 100);

insert into law_change_rate (change_id, code, effective_from, value) values
    (1, 'SMIG_DAILY', '2025-05-01', 14500), (2, 'SMIG_DAILY', '2026-01-01', 21500), (3, 'ONEM', '2025-10-01', 0.5),
    (5, 'IPR_MIN_MONTHLY', '2026-01-01', 2500), (6, 'ITS_FIRST_BAND_FLAT', '2026-01-01', 1200),
    (6, 'ITS_MINIMUM_YEARLY', '2026-01-01', 1200), (10, 'SMIG_MONTHLY', '2025-01-01', 70400);

-- The companies' rates that came from these changes.
update statutory_rate s set law_change_id = r.change_id
from law_change_rate r join law_change c on c.id = r.change_id, company co
where c.status = 'PUBLISHED' and co.id = s.company_id and co.country_code = c.country_code
  and s.code = r.code and s.effective_from = r.effective_from;
update law_change c set applied_to = (select count(distinct s.company_id) from statutory_rate s where s.law_change_id = c.id)
where c.status = 'PUBLISHED';
