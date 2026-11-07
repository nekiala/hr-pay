package cd.hrpay.org;

import cd.hrpay.common.BusinessRuleException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The structure as a spreadsheet: one row per unit with code, name, level code, parent code, cost centre and the
 * head's registration number. Import adds new units and updates existing ones by code, all or nothing.
 */
@Service
public class OrgCsv {

    public static final List<String> HEADER = List.of("code", "name", "level", "parent", "cost_centre", "head_registration_no");

    public record Result(int created, int updated, int headsSet) {}

    private final OrgStructure org;
    private final JdbcClient jdbc;

    public OrgCsv(OrgStructure org, JdbcClient jdbc) {
        this.org = org;
        this.jdbc = jdbc;
    }

    public List<List<String>> export() {
        Map<Long, OrgStructure.Unit> byId = new HashMap<>();
        List<OrgStructure.Unit> units = org.units(false, false);
        units.forEach(u -> byId.put(u.id(), u));
        Map<Long, String> levelCodes = new HashMap<>();
        org.levels().forEach(l -> levelCodes.put(l.id(), l.code()));
        Map<Long, String> regNos = new HashMap<>();
        jdbc.sql("select id, registration_no from app_user").query((rs, i) -> regNos.put(rs.getLong(1), rs.getString(2))).list();
        List<List<String>> rows = new ArrayList<>();
        for (OrgStructure.Unit u : units) {
            rows.add(List.of(u.code(), u.name(), levelCodes.get(u.levelId()),
                    u.parentId() == null ? "" : byId.get(u.parentId()).code(), u.costCentre() == null ? "" : u.costCentre(),
                    u.head() == null ? "" : Objects.toString(regNos.get(u.head().userId()), "")));
        }
        return rows;
    }

    /** Splits CSV text (comma or semicolon, quotes allowed) into rows of cells. */
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        char sep = body.lines().findFirst().map(l -> l.contains(";") && !l.contains(",") ? ';' : ',').orElse(',');
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < body.length() && body.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == sep) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < body.length() && body.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().trim());
                cell.setLength(0);
                if (row.stream().anyMatch(s -> !s.isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        row.add(cell.toString().trim());
        if (row.stream().anyMatch(s -> !s.isEmpty())) {
            rows.add(row);
        }
        return rows;
    }

    private record Line(int number, String code, String name, String level, String parent, String costCentre, String head) {}

    @Transactional
    public Result importCsv(String text) {
        List<List<String>> rows = parse(text);
        if (rows.isEmpty() || !rows.getFirst().getFirst().equalsIgnoreCase("code")) {
            throw new BusinessRuleException("The first line must be the header: " + String.join(",", HEADER) + ".");
        }
        List<Line> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = new ArrayList<>(rows.get(i));
            while (r.size() < HEADER.size()) {
                r.add("");
            }
            if (r.get(0).isEmpty() || r.get(1).isEmpty() || r.get(2).isEmpty()) {
                errors.add("Line " + (i + 1) + ": code, name and level are required.");
                continue;
            }
            lines.add(new Line(i + 1, r.get(0).toUpperCase(), r.get(1), r.get(2).toUpperCase(), r.get(3).toUpperCase(),
                    r.get(4), r.get(5)));
        }
        Map<String, Long> levelIds = new HashMap<>();
        org.levels().forEach(l -> levelIds.put(l.code(), l.id()));
        Map<String, Long> unitIds = new HashMap<>();
        Map<Long, OrgStructure.Unit> existing = new HashMap<>();
        org.units(false, false).forEach(u -> {
            unitIds.put(u.code(), u.id());
            existing.put(u.id(), u);
        });
        lines.stream().filter(l -> !levelIds.containsKey(l.level()))
                .forEach(l -> errors.add("Line " + l.number() + ": unknown level " + l.level() + "."));
        if (!errors.isEmpty()) {
            throw new BusinessRuleException(String.join(" ", errors));
        }
        int created = 0;
        int updated = 0;
        int heads = 0;
        // Parents first: keep passing over the lines until each one's parent exists.
        List<Line> pending = new ArrayList<>(lines);
        while (!pending.isEmpty()) {
            List<Line> next = new ArrayList<>();
            for (Line l : pending) {
                if (!l.parent().isEmpty() && !unitIds.containsKey(l.parent())) {
                    next.add(l);
                    continue;
                }
                Long parentId = l.parent().isEmpty() ? null : unitIds.get(l.parent());
                long levelId = levelIds.get(l.level());
                try {
                    Long id = unitIds.get(l.code());
                    if (id == null) {
                        id = org.createUnit(l.code(), l.name(), levelId, parentId, l.costCentre(), null).id();
                        unitIds.put(l.code(), id);
                        created++;
                    } else {
                        OrgStructure.Unit u = existing.get(id);
                        if (u == null || !u.name().equals(l.name()) || u.levelId() != levelId
                                || !Objects.equals(u.parentId(), parentId)
                                || !Objects.equals(Objects.toString(u.costCentre(), ""), l.costCentre())) {
                            org.updateUnit(id, l.name(), levelId, parentId, l.costCentre());
                            updated++;
                        }
                    }
                    if (!l.head().isEmpty()) {
                        Long userId = jdbc.sql("select id from app_user where lower(registration_no) = lower(?)").param(l.head())
                                .query(Long.class).optional().orElse(null);
                        if (userId == null) {
                            throw new BusinessRuleException("no employee with registration number " + l.head() + ".");
                        }
                        OrgStructure.Unit now = org.unit(id);
                        if (now.head() == null || now.head().userId() != userId) {
                            org.setHead(id, userId, "HEAD", false, null, null);
                            heads++;
                        }
                    }
                } catch (BusinessRuleException | cd.hrpay.common.ConflictException | cd.hrpay.common.NotFoundException e) {
                    throw new BusinessRuleException("Line " + l.number() + " (" + l.code() + "): " + e.getMessage());
                }
            }
            if (next.size() == pending.size()) {
                throw new BusinessRuleException(next.stream()
                        .map(l -> "Line " + l.number() + ": unknown parent " + l.parent() + ".").reduce((a, b) -> a + " " + b).orElse(""));
            }
            pending = next;
        }
        return new Result(created, updated, heads);
    }
}
