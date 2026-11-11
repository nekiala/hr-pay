-- Promotions and transfers (mutations) as one move on the employee file.
--   DRC Code du travail (Loi 015/2002, Loi 16/010): art. 36, 50 and 55 tie the work, its conditions and its place to
--   the contract; art. 44 wants the contract in writing; art. 61 ter: a unilateral change of an element of the contract
--   is the same as ending it. So a promotion or a mutation is written in an avenant or a note signed by the employee.
--   Art. 43: a trial clause is in writing, at most 6 months (1 month for an unskilled labourer). Art. 138: a mutation
--   outside the place of employment owes decent housing or an allowance. Art. 140-141: seniority stays with the employer.
--   Republic of the Congo (Loi 45-75, art. 34-35 via secondary sources): trial in writing, at most 6 months.

set local app.bypass = 'on';

create table career_move (
    id              bigserial primary key,
    user_id         bigint not null references app_user (id),
    kind            varchar(24) not null check (kind in ('PROMOTION', 'TRANSFER', 'PROMOTION_TRANSFER', 'RECLASSIFICATION')),
    effective_on    date not null,
    from_grade_id   bigint references grade (id),
    to_grade_id     bigint references grade (id),
    from_occupation_id bigint references occupation (id),
    to_occupation_id   bigint references occupation (id),
    from_unit_id    bigint references org_unit (id),
    to_unit_id      bigint references org_unit (id),
    from_site_id    bigint references site (id),
    to_site_id      bigint references site (id),
    contract_id     bigint references employee_contract (id),
    trial_months    integer check (trial_months between 1 and 6),
    keep_net        boolean not null default false,
    note            varchar(300),
    letter_id       bigint references employee_letter (id) on delete set null,
    created_by      varchar(320),
    created_at      timestamptz not null default now()
);
select hrpay_company_table('career_move');
create index career_move_user on career_move (user_id, effective_on desc);
