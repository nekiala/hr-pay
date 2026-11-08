-- Payroll general reports (états généraux de la paie) and the livre de paie.
--   DRC, Code du travail (Loi 015/2002): art. 103 a written statement of each payment; art. 213 the livre de paie,
--   art. 214 its copy serves as the payslip (not changed by Loi 16/010).
--   Arrêté ministériel 12/CAB.MIN/ETPS/042 du 8 août 2008: the livre de paie may be a computerised file; an employer
--   of 10 workers or more follows the annexed model (33 mentions); entries are indelible (art. 4).
--   Republic of the Congo: to verify with the payroll rules certifier.

set local app.bypass = 'on';

insert into permission (code, category, description) values
    ('payroll.report', 'payroll', 'See and export the payroll general reports (états généraux de la paie)');
insert into role_permission (company_id, role_id, permission_code)
select r.company_id, r.id, 'payroll.report' from role r where r.code in ('ADMIN', 'HR', 'FINANCE')
on conflict do nothing;

-- Where each payslip belongs, kept at calculation so a report groups a month as it was paid.
alter table payslip add column site_id bigint references site (id);
alter table payslip add column category_id bigint references grade_category (id);
alter table payslip add column grade_id bigint references grade (id);

-- Payslips made before: the site from the employee's record, the grade from the contract in force that month.
update payslip p set site_id = u.site_id from app_user u where u.id = p.user_id and p.site_id is null;
update payslip p set grade_id = x.grade_id, category_id = x.category_id
from (
    select distinct on (p2.id) p2.id as payslip_id, c.grade_id, g.category_id
    from payslip p2
    join payroll_run r on r.id = p2.run_id
    join employee_contract c on c.user_id = p2.user_id and c.start_date <= (r.month + interval '1 month - 1 day')::date
                             and (c.end_date is null or c.end_date >= r.month)
    join grade g on g.id = c.grade_id
    order by p2.id, c.start_date desc
) x
where x.payslip_id = p.id and p.grade_id is null;

create index payslip_run_site on payslip (run_id, site_id);
create index payslip_run_category on payslip (run_id, category_id);

-- The branch of activity, printed at the top of the livre de paie.
alter table company add column activity varchar(150);

-- Report of differences with the previous month: a net that moved by more than this share is listed.
insert into payroll_setting (company_id, key, value)
select c.id, 'REPORT_VARIANCE_PERCENT', '10' from company c
on conflict do nothing;
