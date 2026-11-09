-- In the Congos a person's name is Nom, Postnom and Prénom. The postnom was kept with the personal details of the
-- employee file; it is part of the name, so it moves to the person.
alter table app_user add column middle_name varchar(100);

update app_user u set middle_name = nullif(trim(p.postnom), '')
from employee_profile p where p.user_id = u.id and p.postnom is not null;

alter table employee_profile drop column postnom;

-- The name as documents print it: NOM POSTNOM Prénom (the postnom only when there is one).
create or replace function person_name(first_name text, middle_name text, last_name text) returns text
    language sql immutable parallel safe as
$$ select upper(last_name) || coalesce(' ' || upper(nullif(trim(middle_name), '')), '') || ' ' || first_name $$;
