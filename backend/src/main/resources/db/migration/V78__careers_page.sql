-- Batch C, PR 2: a public careers page per company and job offers staff can apply to from the app.
set local app.bypass = 'on';

-- The short public name in the careers link (platform.hrpay.cloud/careers/<name>), and whether the page is on.
alter table company
    add column careers_slug    varchar(40),
    add column careers_enabled boolean not null default false;
create unique index company_careers_slug_ux on company (lower(careers_slug));
alter table company add constraint company_careers_slug_check check (careers_slug ~ '^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$');

alter table application
    -- Where a careers-page applicant came from: the link HR shared (whatsapp, linkedin, a job board name...).
    add column channel         varchar(30),
    -- The link in the confirmation email that lets the candidate withdraw and delete their data.
    add column withdraw_token  uuid unique,
    -- An internal applicant's manager is told once the application reaches the shortlist.
    add column manager_told_at timestamptz,
    -- An internal hire becomes a promotion or a transfer: the move it made.
    add column career_move_id  bigint references career_move (id);
