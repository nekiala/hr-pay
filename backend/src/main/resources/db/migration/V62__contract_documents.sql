-- Employment contracts written from the company's model (DRC Code du travail art. 43-47 and 212; arrêtés
-- 062/CAB/PVPM/ETPS/2011 on the form and visa of the contract and 063/2011 on renewing fixed-term contracts).

-- What a written contract states beyond its kind, dates and grade: the trial clause (art. 43, at most one month for an
-- unskilled labourer and six months for the others), why it is fixed-term (art. 40, 45; AM 063 art. 2 for the cases
-- that allow more than one renewal), where it is carried out and signed (art. 212 points 10 and 14), the medical
-- fitness (point 15), when the draft was handed over (art. 46: two working days before signing) and the ONEM visa
-- (art. 47, within fifteen days of signing).
create table contract_terms (
    contract_id         bigint primary key references employee_contract (id) on delete cascade,
    trial_months        smallint not null default 0 check (trial_months between 0 and 6),
    unskilled_labourer  boolean not null default false,
    fixed_term_reason   varchar(16) check (fixed_term_reason in ('TEMPORARY_NEED', 'REPLACEMENT', 'SPECIFIC_WORK', 'SEASONAL',
                                                                 'WORKS', 'PROJECT', 'HUMANITARIAN', 'PART_TIME',
                                                                 'AWAITING_HOLDER', 'ACTIVITY_PEAK')),
    fixed_term_detail   varchar(200),
    permanent_job       boolean not null default false,
    work_place          varchar(200),
    supervisor          varchar(150),
    signed_place        varchar(100),
    signed_on           date,
    draft_given_on      date,
    medical_fit_on      date,
    medical_doctor      varchar(150),
    onem_visa_no        varchar(40),
    onem_visa_on        date,
    updated_by          varchar(320),
    updated_at          timestamptz not null default now(),
    check (trial_months <= 1 or not unskilled_labourer)
);
select hrpay_company_table('contract_terms');

-- Who signs contracts for the employer, and where documents are signed (art. 212 point 14).
alter table company add column representative_name varchar(150),
                    add column representative_title varchar(100),
                    add column signing_place varchar(100);

-- The document control of a model (code, version, classification, owner, approver, adoption), printed in the
-- contract's annex.
alter table letter_template add column doc_code varchar(20),
                            add column doc_version varchar(20),
                            add column doc_classification varchar(60),
                            add column doc_owner varchar(100),
                            add column doc_approver varchar(100),
                            add column doc_adopted_on date,
                            add column doc_effective_on date;

-- A contract document is written for one contract of the person.
alter table employee_letter add column contract_id bigint references employee_contract (id) on delete set null;
create index employee_letter_contract on employee_letter (contract_id);

-- The full contract models are added at start-up (LifecycleDefaults); the short samples nobody changed step aside.
