-- Phase 6: the structure is used across the app.

-- A manager set by hand now overrides the structure. Where it only repeats what the structure gives (the head of the
-- person's unit, or of the nearest headed unit above), clear it so that later changes of head take effect.
with recursive up(user_id, unit_id, depth) as (
    select a.user_id, a.unit_id, 0 from org_assignment a where a.valid_to is null
    union all
    select up.user_id, u.parent_id, up.depth + 1
    from up join org_unit u on u.id = up.unit_id where u.parent_id is not null and up.depth < 50
), boss as (
    select distinct on (up.user_id) up.user_id, h.user_id as head_id
    from up join org_head h on h.unit_id = up.unit_id and h.role = 'HEAD' and h.valid_to is null and h.user_id <> up.user_id
    order by up.user_id, up.depth
)
update app_user u set manager_id = null from boss b where b.user_id = u.id and u.manager_id = b.head_id;

-- The unit a payslip was made for, kept with the payslip (as the employee's unit at the end of the month).
alter table payslip add column unit_id bigint references org_unit (id);

update payslip p set unit_id = a.unit_id
from payroll_run r, org_assignment a
where r.id = p.run_id and a.user_id = p.user_id
  and a.valid_from <= (r.month + interval '1 month' - interval '1 day')::date
  and (a.valid_to is null or a.valid_to >= (r.month + interval '1 month' - interval '1 day')::date);

create index payslip_unit_idx on payslip (unit_id);
