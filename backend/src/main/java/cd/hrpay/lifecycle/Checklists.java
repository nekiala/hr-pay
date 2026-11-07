package cd.hrpay.lifecycle;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hiring and departure checklists, and the equipment lent to each person. Each company keeps its own list of
 * steps; a checklist copies them for one person. A step tied to something HR Pay knows (a document filed, the
 * equipment back, the contract signed, the settlement confirmed, the account closed) ticks itself.
 */
@Service
public class Checklists {

    public static final Set<String> KINDS = Set.of("HIRING", "DEPARTURE");
    public static final Set<String> ACTIONS = Set.of("DOCUMENT", "EQUIPMENT", "CONTRACT_SIGNED", "SETTLEMENT", "ACCOUNT_CLOSED");

    public record TemplateItem(long id, String kind, int position, String label, Long documentTypeId, String action,
                               boolean active) {}

    public record Item(long id, int position, String label, Long documentTypeId, String action, boolean done, boolean auto,
                       OffsetDateTime doneAt, String doneBy, String note) {}

    public record Checklist(long id, long userId, String kind, String status, LocalDate dueOn, String startedBy,
                            OffsetDateTime startedAt, OffsetDateTime closedAt, List<Item> items) {}

    public record Open(long id, long userId, String name, String registrationNo, String kind, LocalDate dueOn,
                       OffsetDateTime startedAt, int done, int total) {}

    public record Equipment(long id, long userId, String kind, String description, String serialNo, LocalDate issuedOn,
                            LocalDate returnedOn, String returnedTo, String note) {}

    public record Out(long id, long userId, String name, String kind, String description, String serialNo, LocalDate issuedOn) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public Checklists(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- the company's steps ------------------------------------------------------------------------------------

    public List<TemplateItem> template(String kind) {
        return jdbc.sql("select id, kind, position, label, document_type_id, action, active from checklist_template_item"
                + (kind == null ? "" : " where kind = ?") + " order by kind, position, id")
                .params(kind == null ? List.of() : List.of(kind)).query(TemplateItem.class).list();
    }

    @Transactional
    public TemplateItem saveTemplateItem(Long id, String kind, int position, String label, Long documentTypeId, String action,
                                         boolean active) {
        if (!KINDS.contains(kind)) {
            throw new BusinessRuleException("A checklist is for a hiring or a departure.");
        }
        if (action != null && !ACTIONS.contains(action)) {
            throw new BusinessRuleException("Unknown automatic step %s.".formatted(action));
        }
        if ("DOCUMENT".equals(action) && documentTypeId == null) {
            throw new BusinessRuleException("Choose the document to collect.");
        }
        Long type = "DOCUMENT".equals(action) ? documentTypeId : null;
        long saved;
        if (id == null) {
            saved = jdbc.sql("""
                    insert into checklist_template_item (kind, position, label, document_type_id, action, active)
                    values (?, ?, ?, ?, ?, ?) returning id""").params(kind, position, label.trim(), type, action, active)
                    .query(Long.class).single();
        } else {
            int n = jdbc.sql("""
                    update checklist_template_item set kind = ?, position = ?, label = ?, document_type_id = ?, action = ?, active = ?
                    where id = ?""").params(kind, position, label.trim(), type, action, active, id).update();
            if (n == 0) {
                throw new NotFoundException("Step not found.");
            }
            saved = id;
        }
        audit.record("checklist_step.saved", "checklist_template_item", saved, Map.of("kind", kind, "label", label.trim()));
        return jdbc.sql("select id, kind, position, label, document_type_id, action, active from checklist_template_item where id = ?")
                .param(saved).query(TemplateItem.class).single();
    }

    @Transactional
    public void deleteTemplateItem(long id) {
        jdbc.sql("delete from checklist_template_item where id = ?").param(id).update();
        audit.record("checklist_step.deleted", "checklist_template_item", id, Map.of());
    }

    // ---- a person's checklists ----------------------------------------------------------------------------------

    @Transactional
    public Checklist start(long userId, String kind, LocalDate dueOn) {
        if (!KINDS.contains(kind)) {
            throw new BusinessRuleException("A checklist is for a hiring or a departure.");
        }
        if (openId(userId, kind) != null) {
            throw new ConflictException("This person already has an open checklist of this kind.");
        }
        long id = jdbc.sql("insert into employee_checklist (user_id, kind, due_on, started_by) values (?, ?, ?, ?) returning id")
                .params(userId, kind, dueOn, CurrentUser.email()).query(Long.class).single();
        jdbc.sql("""
                insert into employee_checklist_item (checklist_id, position, label, document_type_id, action)
                select ?, position, label, document_type_id, action from checklist_template_item
                where kind = ? and active order by position, id""").params(id, kind).update();
        audit.record("checklist.started", "user", userId, Map.of("kind", kind, "checklist", id));
        return find(id);
    }

    /** A departure confirmed opens its checklist, unless one is already open. */
    @EventListener
    public void on(AuditService.Recorded r) {
        if (r.action().equals("departure.confirmed") && r.entityId() instanceof Number n && openId(n.longValue(), "DEPARTURE") == null) {
            LocalDate last = r.details() != null && r.details().get("lastDay") != null
                    ? LocalDate.parse(String.valueOf(r.details().get("lastDay"))) : null;
            start(n.longValue(), "DEPARTURE", last);
        }
        // The departure taken back: its checklist goes too.
        if (r.action().equals("departure.cancelled") && r.entityId() instanceof Number n) {
            Long open = openId(n.longValue(), "DEPARTURE");
            if (open != null) {
                close(open, true);
            }
        }
    }

    private Long openId(long userId, String kind) {
        return jdbc.sql("select id from employee_checklist where user_id = ? and kind = ? and status = 'OPEN'")
                .params(userId, kind).query(Long.class).optional().orElse(null);
    }

    public List<Checklist> of(long userId) {
        return jdbc.sql("select id from employee_checklist where user_id = ? and status <> 'CANCELLED' order by started_at desc")
                .param(userId).query(Long.class).list().stream().map(this::find).toList();
    }

    public Checklist find(long id) {
        record Head(long id, long userId, String kind, String status, LocalDate dueOn, String startedBy, OffsetDateTime startedAt,
                    OffsetDateTime closedAt) {}
        Head h = jdbc.sql("select id, user_id, kind, status, due_on, started_by, started_at, closed_at from employee_checklist where id = ?")
                .param(id).query(Head.class).optional().orElseThrow(() -> new NotFoundException("Checklist not found."));
        return new Checklist(h.id(), h.userId(), h.kind(), h.status(), h.dueOn(), h.startedBy(), h.startedAt(), h.closedAt(),
                items(h.id(), h.userId(), h.kind()));
    }

    private List<Item> items(long checklistId, long userId, String kind) {
        record Row(long id, int position, String label, Long documentTypeId, String action, OffsetDateTime doneAt, String doneBy,
                   String note) {}
        return jdbc.sql("""
                select id, position, label, document_type_id, action, done_at, done_by, note from employee_checklist_item
                where checklist_id = ? order by position, id""").param(checklistId).query(Row.class).list().stream()
                .map(r -> {
                    boolean auto = happened(userId, kind, r.action(), r.documentTypeId());
                    return new Item(r.id(), r.position(), r.label(), r.documentTypeId(), r.action(), auto || r.doneAt() != null,
                            auto, r.doneAt(), r.doneBy(), r.note());
                }).toList();
    }

    /** Whether the step's event has happened, as HR Pay knows it. */
    private boolean happened(long userId, String kind, String action, Long documentTypeId) {
        if (action == null) {
            return false;
        }
        String sql = switch (action) {
            case "DOCUMENT" -> "select count(*) from employee_document where user_id = ? and type_id = " + documentTypeId
                    + " and deleted_at is null";
            case "EQUIPMENT" -> kind.equals("HIRING")
                    ? "select count(*) from equipment where user_id = ?"
                    : "select case when count(*) filter (where returned_on is null) = 0 then 1 else 0 end from equipment where user_id = ?";
            case "CONTRACT_SIGNED" -> "select count(*) from employee_letter where user_id = ? and kind = 'CONTRACT' and status = 'SIGNED'";
            case "SETTLEMENT" -> "select count(*) from departure where user_id = ? and status = 'CONFIRMED'";
            case "ACCOUNT_CLOSED" -> "select count(*) from app_user where id = ? and status = 'DISABLED'";
            default -> null;
        };
        return sql != null && documentTypeIdOk(action, documentTypeId)
                && jdbc.sql(sql).param(userId).query(Integer.class).single() > 0;
    }

    private static boolean documentTypeIdOk(String action, Long documentTypeId) {
        return !"DOCUMENT".equals(action) || documentTypeId != null;
    }

    @Transactional
    public Checklist tick(long itemId, boolean done, String note) {
        Long checklistId = jdbc.sql("""
                select c.id from employee_checklist_item i join employee_checklist c on c.id = i.checklist_id
                where i.id = ? and c.status = 'OPEN'""").param(itemId).query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("Step not found in an open checklist."));
        jdbc.sql("update employee_checklist_item set done_at = ?, done_by = ?, note = ? where id = ?")
                .params(done ? OffsetDateTime.now() : null, done ? CurrentUser.email() : null,
                        note == null || note.isBlank() ? null : note.trim(), itemId).update();
        return find(checklistId);
    }

    @Transactional
    public Checklist close(long id, boolean cancel) {
        Checklist c = find(id);
        if (!c.status().equals("OPEN")) {
            throw new BusinessRuleException("This checklist is already closed.");
        }
        if (!cancel && c.items().stream().anyMatch(i -> !i.done())) {
            throw new BusinessRuleException("Some steps are not done yet.");
        }
        jdbc.sql("update employee_checklist set status = ?, closed_at = now(), closed_by = ? where id = ?")
                .params(cancel ? "CANCELLED" : "DONE", CurrentUser.email(), id).update();
        audit.record(cancel ? "checklist.cancelled" : "checklist.done", "user", c.userId(), Map.of("kind", c.kind(), "checklist", id));
        return find(id);
    }

    public List<Open> open() {
        return jdbc.sql("""
                select c.id, c.user_id, u.first_name || ' ' || u.last_name as name, u.registration_no, c.kind, c.due_on, c.started_at,
                       0 as done, 0 as total
                from employee_checklist c join app_user u on u.id = c.user_id where c.status = 'OPEN' order by c.due_on nulls last, c.started_at""")
                .query(Open.class).list().stream().map(o -> {
                    List<Item> items = find(o.id()).items();
                    return new Open(o.id(), o.userId(), o.name(), o.registrationNo(), o.kind(), o.dueOn(), o.startedAt(),
                            (int) items.stream().filter(Item::done).count(), items.size());
                }).toList();
    }

    // ---- equipment ----------------------------------------------------------------------------------------------

    public List<Equipment> equipment(long userId) {
        return jdbc.sql("""
                select id, user_id, kind, description, serial_no, issued_on, returned_on, returned_to, note from equipment
                where user_id = ? order by returned_on nulls first, issued_on desc""").param(userId).query(Equipment.class).list();
    }

    /** Everything lent and not back, for the people leaving or gone. */
    public List<Out> outstandingForLeavers() {
        return jdbc.sql("""
                select e.id, e.user_id, u.first_name || ' ' || u.last_name as name, e.kind, e.description, e.serial_no, e.issued_on
                from equipment e join app_user u on u.id = e.user_id
                where e.returned_on is null and (u.status = 'DISABLED'
                    or exists (select 1 from departure d where d.user_id = e.user_id and d.status = 'CONFIRMED'))
                order by name""").query(Out.class).list();
    }

    @Transactional
    public Equipment issue(long userId, String kind, String description, String serialNo, LocalDate issuedOn, String note) {
        long id = jdbc.sql("""
                insert into equipment (user_id, kind, description, serial_no, issued_on, note, created_by)
                values (?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, kind.trim(), blank(description), blank(serialNo), issuedOn, blank(note), CurrentUser.email())
                .query(Long.class).single();
        audit.record("equipment.issued", "user", userId, Map.of("equipment", id, "kind", kind.trim()));
        return one(id);
    }

    @Transactional
    public Equipment returned(long id, LocalDate on, String note) {
        Equipment e = one(id);
        if (e.returnedOn() != null) {
            throw new BusinessRuleException("This equipment is already back.");
        }
        if (on.isBefore(e.issuedOn())) {
            throw new BusinessRuleException("It cannot come back before it was lent.");
        }
        jdbc.sql("update equipment set returned_on = ?, returned_to = ?, note = coalesce(?, note) where id = ?")
                .params(on, CurrentUser.email(), blank(note), id).update();
        audit.record("equipment.returned", "user", e.userId(), Map.of("equipment", id, "on", on.toString()));
        return one(id);
    }

    @Transactional
    public void deleteEquipment(long id) {
        Equipment e = one(id);
        jdbc.sql("delete from equipment where id = ?").param(id).update();
        audit.record("equipment.deleted", "user", e.userId(), Map.of("equipment", id, "kind", e.kind()));
    }

    private Equipment one(long id) {
        return jdbc.sql("select id, user_id, kind, description, serial_no, issued_on, returned_on, returned_to, note from equipment where id = ?")
                .param(id).query(Equipment.class).optional().orElseThrow(() -> new NotFoundException("Equipment not found."));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
