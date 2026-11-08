-- Background jobs: long tasks (a payroll of 5,000 people, closing a month, imports, files of every payslip) put in a
-- queue kept in PostgreSQL and run by worker threads, on any server, instead of inside the web request.
-- A worker takes the next waiting job with FOR UPDATE SKIP LOCKED; a running job's server writes a heartbeat, and a
-- job whose server stopped is put back in the queue. No labour-law rule changes.

set local app.bypass = 'on';

create table background_job (
    id            bigserial primary key,
    type          varchar(40) not null,
    -- What the job works on, e.g. "payroll:2026-10": one waiting or running job per key and company at a time.
    job_key       varchar(120),
    params        jsonb not null default '{}',
    status        varchar(10) not null default 'WAITING'
                  check (status in ('WAITING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    progress      smallint not null default 0 check (progress between 0 and 100),
    step          varchar(200),
    -- The permission the job needs: checked again when it runs; holders may also download its file.
    permission    varchar(60),
    language      varchar(5) not null default 'fr',
    requested_by  bigint references app_user (id),
    requested_by_email varchar(320) not null,
    created_at    timestamptz not null default now(),
    started_at    timestamptz,
    heartbeat_at  timestamptz,
    finished_at   timestamptz,
    worker        varchar(120),
    attempts      smallint not null default 0,
    max_attempts  smallint not null default 3,
    -- The answer the page would have had (JSON), or the file made (background_job_file).
    result        jsonb,
    error         varchar(2000),
    error_status  smallint,
    notified_at   timestamptz,
    alerted_at    timestamptz
);
select hrpay_company_table('background_job');
create index background_job_waiting on background_job (created_at) where status = 'WAITING';
create index background_job_running on background_job (heartbeat_at) where status = 'RUNNING';
create index background_job_requester on background_job (company_id, requested_by, created_at desc);
create unique index background_job_one_open on background_job (company_id, job_key)
    where job_key is not null and status in ('WAITING', 'RUNNING');

-- A job's input file (an import) and the file it makes. Result files are deleted after 7 days; the job stays.
create table background_job_file (
    id           bigserial primary key,
    job_id       bigint not null references background_job (id) on delete cascade,
    role         varchar(6) not null check (role in ('INPUT', 'RESULT')),
    file_name    varchar(200) not null,
    content_type varchar(120) not null,
    size_bytes   bigint not null,
    data         bytea not null,
    created_at   timestamptz not null default now()
);
select hrpay_company_table('background_job_file');
create unique index background_job_file_one on background_job_file (job_id, role);

