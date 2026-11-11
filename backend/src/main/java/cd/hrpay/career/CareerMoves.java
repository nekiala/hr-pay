package cd.hrpay.career;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.lifecycle.Letters;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.EmployeeFile;
import cd.hrpay.payroll.MasterData;
import cd.hrpay.payroll.NegotiatedPay;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotions and transfers (mutations): one move changes, from a date, any of the grade, the occupation, the unit and
 * the site. The grade and occupation go into an avenant (a new contract record), the unit into the structure, and the
 * employee gets a document to sign: an avenant de promotion or a note de mutation.
 *
 * <p>DRC Code du travail art. 36, 44, 50, 55 and 61 ter: the job and the place of work belong to the written contract,
 * and changing them alone is the same as ending it, hence the signature. Art. 43: a trial clause is written, at most
 * six months. Art. 138: a mutation outside the place of employment owes housing or an allowance. Seniority never
 * changes (art. 140-141): the contract goes on.
 */
@Service
public class CareerMoves {

    public static final Set<String> KINDS = Set.of("PROMOTION", "TRANSFER", "PROMOTION_TRANSFER", "RECLASSIFICATION");
    public static final String PROMOTION_LETTER = "CAREER_PROMOTION";
    public static final String TRANSFER_LETTER = "CAREER_TRANSFER";
    public static final int MAX_TRIAL_MONTHS = 6;

    /** What HR asks: null keeps what the employee has now. */
    public record Input(String kind, LocalDate effectiveOn, Long gradeId, Long occupationId, Long unitId, Long siteId,
                        Integer trialMonths, boolean keepNet, String note) {}

    public record Move(long id, long userId, String kind, LocalDate effectiveOn, Long fromGradeId, String fromGrade,
                       Long toGradeId, String toGrade, Long fromOccupationId, String fromOccupation, Long toOccupationId,
                       String toOccupation, Long fromUnitId, String fromUnit, Long toUnitId, String toUnit, Long fromSiteId,
                       String fromSite, Long toSiteId, String toSite, Long contractId, Integer trialMonths, boolean keepNet,
                       String note, Long letterId, String letterStatus, String createdBy, OffsetDateTime createdAt) {}

    /** The move saved, and what HR should know about it. */
    public record Result(Move move, List<String> warnings) {}

    private final JdbcClient jdbc;
    private final EmployeeFile files;
    private final NegotiatedPay negotiated;
    private final MasterData masterData;
    private final OrgStructure org;
    private final Letters letters;
    private final Company company;
    private final AuditService audit;

    private cd.hrpay.representation.StaffRepresentation representation;

    @org.springframework.beans.factory.annotation.Autowired
    void setRepresentation(@org.springframework.context.annotation.Lazy cd.hrpay.representation.StaffRepresentation representation) {
        this.representation = representation;
    }

    private cd.hrpay.position.JobCatalogue jobs;
    private cd.hrpay.position.Positions positions;

    @org.springframework.beans.factory.annotation.Autowired
    void setJobs(@org.springframework.context.annotation.Lazy cd.hrpay.position.JobCatalogue jobs,
                 @org.springframework.context.annotation.Lazy cd.hrpay.position.Positions positions) {
        this.jobs = jobs;
        this.positions = positions;
    }

    public CareerMoves(JdbcClient jdbc, EmployeeFile files, NegotiatedPay negotiated, MasterData masterData,
                       OrgStructure org, Letters letters, Company company, AuditService audit) {
        this.jdbc = jdbc;
        this.files = files;
        this.negotiated = negotiated;
        this.masterData = masterData;
        this.org = org;
        this.letters = letters;
        this.company = company;
        this.audit = audit;
    }

    private static final String SELECT = """
            select m.id, m.user_id, m.kind, m.effective_on,
                   m.from_grade_id, fg.code || ' · ' || fg.name as from_grade, m.to_grade_id, tg.code || ' · ' || tg.name as to_grade,
                   m.from_occupation_id, fo.name as from_occupation, m.to_occupation_id, tox.name as to_occupation,
                   m.from_unit_id, fu.name as from_unit, m.to_unit_id, tu.name as to_unit,
                   m.from_site_id, fs.name as from_site, m.to_site_id, ts.name as to_site,
                   m.contract_id, m.trial_months, m.keep_net, m.note, m.letter_id, l.status as letter_status,
                   m.created_by, m.created_at
            from career_move m
            left join grade fg on fg.id = m.from_grade_id left join grade tg on tg.id = m.to_grade_id
            left join occupation fo on fo.id = m.from_occupation_id left join occupation tox on tox.id = m.to_occupation_id
            left join org_unit fu on fu.id = m.from_unit_id left join org_unit tu on tu.id = m.to_unit_id
            left join site fs on fs.id = m.from_site_id left join site ts on ts.id = m.to_site_id
            left join employee_letter l on l.id = m.letter_id
            """;

    public List<Move> of(long userId) {
        return jdbc.sql(SELECT + " where m.user_id = ? order by m.effective_on desc, m.id desc").param(userId)
                .query(Move.class).list();
    }

    public Move find(long id) {
        return jdbc.sql(SELECT + " where m.id = ?").param(id).query(Move.class).optional()
                .orElseThrow(() -> new cd.hrpay.common.NotFoundException("Move not found."));
    }

    /** Saves the move: the avenant, the structure, the site, and the document to sign (a draft HR sends). */
    @Transactional
    public Result move(long userId, Input in) {
        if (in.kind() == null || !KINDS.contains(in.kind())) {
            throw new BusinessRuleException("Choose the kind of move.");
        }
        if (in.effectiveOn() == null) {
            throw new BusinessRuleException("Give the date the move takes effect.");
        }
        boolean promotion = in.kind().startsWith("PROMOTION");
        Integer trial = in.trialMonths() == null || in.trialMonths() == 0 ? null : in.trialMonths();
        if (trial != null && (!promotion || trial < 0 || trial > MAX_TRIAL_MONTHS)) {
            throw new BusinessRuleException(Texts.t("A trial period goes only with a promotion, %d months at most (Labour Code art. 43).",
                    MAX_TRIAL_MONTHS));
        }
        EmployeeFile.Contract current = files.contractOn(userId, in.effectiveOn()).orElse(null);
        Long fromUnit = org.currentAssignment(userId).map(OrgStructure.Assignment::unitId).orElse(null);
        Long fromSite = jdbc.sql("select site_id from app_user where id = ?").param(userId).query(Long.class).optional().orElse(null);

        Long toGrade = in.gradeId() != null && (current == null || in.gradeId() != current.gradeId()) ? in.gradeId() : null;
        Long toOccupation = in.occupationId() != null && (current == null || !in.occupationId().equals(current.occupationId()))
                ? in.occupationId() : null;
        Long toUnit = in.unitId() != null && !in.unitId().equals(fromUnit) ? in.unitId() : null;
        Long toSite = in.siteId() != null && !in.siteId().equals(fromSite) ? in.siteId() : null;
        if (toGrade == null && toOccupation == null && toUnit == null && toSite == null) {
            throw new BusinessRuleException("Nothing changes: choose a new grade, occupation, unit or site.");
        }
        if ((toGrade != null || toOccupation != null) && current == null) {
            throw new BusinessRuleException(Texts.t("There is no contract on %s: start one first, its grade gives the basic salary.",
                    in.effectiveOn()));
        }
        if (in.kind().equals("TRANSFER") && (toGrade != null || toOccupation != null)) {
            throw new BusinessRuleException("A transfer keeps the grade and the occupation: choose Promotion with transfer.");
        }
        if (in.kind().equals("PROMOTION") && (toUnit != null || toSite != null)) {
            throw new BusinessRuleException("A promotion keeps the unit and the site: choose Promotion with transfer.");
        }
        if (promotion && toGrade == null && toOccupation == null) {
            throw new BusinessRuleException("A promotion changes the grade or the occupation.");
        }
        if (promotion && toGrade != null) {
            MasterData.Grade before = masterData.grade(current.gradeId());
            MasterData.Grade after = masterData.grade(toGrade);
            if (before.currency().equals(after.currency()) && after.baseSalary().compareTo(before.baseSalary()) < 0) {
                throw new BusinessRuleException("A promotion goes to a better paid grade: a lower grade is a reclassification.");
            }
        }
        if (in.kind().equals("PROMOTION_TRANSFER") && toUnit == null && toSite == null) {
            throw new BusinessRuleException("Promotion with transfer: choose the new unit or site.");
        }

        if ((toUnit != null || toSite != null) && representation != null) {
            representation.requireAuthorisation(userId, "TRANSFER", in.effectiveOn());
        }

        List<String> warnings = new ArrayList<>();
        if (jobs != null && (toUnit != null || toOccupation != null)) {
            Long job = toOccupation != null ? toOccupation : current == null ? null : current.occupationId();
            jobs.requireTrialAllowed(job, trial == null ? 0 : trial);
            positions.check(userId, toUnit != null ? toUnit : fromUnit, job, in.effectiveOn()).ifPresent(warnings::add);
        }
        if (jobs != null && (toGrade != null || toOccupation != null)) {
            jobs.gradeOutside(toOccupation != null ? toOccupation : current.occupationId(),
                    toGrade != null ? toGrade : current.gradeId()).ifPresent(warnings::add);
        }

        Long contractId = null;
        if (toGrade != null || toOccupation != null) {
            long grade = toGrade != null ? toGrade : current.gradeId();
            Long occupation = toOccupation != null ? toOccupation : current.occupationId();
            contractId = negotiated.changeJob(userId, grade, occupation, in.effectiveOn(), in.keepNet(), noteOr(in)).id();
        }
        if (toUnit != null) {
            org.assign(userId, toUnit, in.effectiveOn(), promotion ? "PROMOTION" : "TRANSFER", clean(in.note()));
        }
        if (toSite != null) {
            if (jdbc.sql("select count(*) from site where id = ?").param(toSite).query(Integer.class).single() == 0) {
                throw new BusinessRuleException("Unknown site.");
            }
            jdbc.sql("update app_user set site_id = ?, updated_at = now() where id = ?").params(toSite, userId).update();
        }
        long id = jdbc.sql("""
                insert into career_move (user_id, kind, effective_on, from_grade_id, to_grade_id, from_occupation_id,
                    to_occupation_id, from_unit_id, to_unit_id, from_site_id, to_site_id, contract_id, trial_months, keep_net,
                    note, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, in.kind(), in.effectiveOn(), toGrade == null ? null : current.gradeId(), toGrade,
                        toOccupation == null ? null : current.occupationId(), toOccupation, toUnit == null ? null : fromUnit,
                        toUnit, toSite == null ? null : fromSite, toSite, contractId, trial, in.keepNet() && toGrade != null,
                        clean(in.note()), CurrentUser.email())
                .query(Long.class).single();
        audit.record("career_move.created", "user", userId, Map.of("move", id, "kind", in.kind(), "from", in.effectiveOn().toString()));

        Move saved = find(id);
        if (!in.kind().equals("RECLASSIFICATION")) {
            Long letter = draftLetter(saved, current);
            if (letter != null) {
                jdbc.sql("update career_move set letter_id = ? where id = ?").params(letter, id).update();
                saved = find(id);
            }
        }
        if (toSite != null) {
            warnings.add(Texts.t("If the new site is outside the place of employment, the company owes decent housing for the worker and the family, or an allowance (Labour Code art. 138)."));
        }
        if (!in.kind().equals("RECLASSIFICATION")) {
            warnings.add(saved.letterId() != null
                    ? Texts.t("The document to sign is ready as a draft: read it and send it from Documents and letters.")
                    : Texts.t("No document model for this move is active: write the letter on Documents and letters."));
        }
        return new Result(saved, warnings);
    }

    private static String noteOr(Input in) {
        String note = clean(in.note());
        if (note != null) {
            return note;
        }
        return switch (in.kind()) {
            case "PROMOTION", "PROMOTION_TRANSFER" -> Texts.t("Promotion");
            case "RECLASSIFICATION" -> Texts.t("Reclassification");
            default -> null;
        };
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    // ---- the document to sign ----------------------------------------------------------------------------------

    /** The move's own fields for the letters, written {{career.…}}. */
    Map<String, String> values(Move m, EmployeeFile.Contract before) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("career.effective_on", Texts.day(m.effectiveOn()));
        List<String> lines = new ArrayList<>();
        if (m.toGradeId() != null) {
            lines.add(Texts.t("grade: %s, instead of %s", m.toGrade(), m.fromGrade()));
        }
        if (m.toOccupationId() != null) {
            lines.add(m.fromOccupation() == null ? Texts.t("occupation: %s", m.toOccupation())
                    : Texts.t("occupation: %s, instead of %s", m.toOccupation(), m.fromOccupation()));
        }
        if (m.toUnitId() != null) {
            lines.add(m.fromUnit() == null ? Texts.t("unit: %s", m.toUnit())
                    : Texts.t("unit: %s, instead of %s", m.toUnit(), m.fromUnit()));
        }
        if (m.toSiteId() != null) {
            lines.add(m.fromSite() == null ? Texts.t("place of work: %s", m.toSite())
                    : Texts.t("place of work: %s, instead of %s", m.toSite(), m.fromSite()));
        }
        v.put("career.change", lines.stream().map(l -> "- " + l).reduce((a, b) -> a + "\n" + b).orElse(""));
        if (m.toGradeId() != null) {
            MasterData.Grade g = masterData.grade(m.toGradeId());
            v.put("career.new_basic", Texts.t("%s %s a month", amount(g.baseSalary()), g.currency()));
            v.put("career.pay_note", m.keepNet()
                    ? Texts.t("The net pay stays the same: the salary complement is adjusted to the new basic salary.")
                    : Texts.t("The salary complement, if any, stays as it is."));
        } else {
            v.put("career.new_basic", Texts.t("unchanged"));
            v.put("career.pay_note", Texts.t("The salary does not change."));
        }
        v.put("career.trial", m.trialMonths() == null
                ? Texts.t("This promotion has no trial period.")
                : Texts.t("This promotion comes with a trial period of %d months, until %s. If it is not conclusive, the employee returns to the previous post and grade.",
                        m.trialMonths(), Texts.day(m.effectiveOn().plusMonths(m.trialMonths()).minusDays(1))));
        v.put("career.housing", m.toSiteId() == null
                ? Texts.t("The place of work does not change.")
                : Texts.t("If the new place of work is outside your place of employment, the company provides decent housing for you and your family or, failing that, a housing allowance (Labour Code art. 138)."));
        return v;
    }

    private static String amount(BigDecimal v) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(Texts.locale());
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(v);
    }

    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*(career\\.[a-z_]+)\\s*}}");

    /** The company's model for the move, with the move's fields written in; HR reads it before sending. */
    private Long draftLetter(Move m, EmployeeFile.Contract before) {
        String code = m.kind().startsWith("PROMOTION") ? PROMOTION_LETTER : TRANSFER_LETTER;
        Letters.Template t = letters.templates().stream().filter(x -> x.code().equals(code) && x.active()).findFirst()
                .orElse(null);
        if (t == null) {
            return null;
        }
        Map<String, String> v = Texts.in(company.language(), () -> values(m, before));
        String body = fill(t.body(), v);
        String title = fill(t.name(), v);
        return letters.create(m.userId(), t.id(), title, body, "LETTER", true).id();
    }

    private static String fill(String text, Map<String, String> v) {
        Matcher matcher = FIELD.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(Objects.requireNonNullElse(v.get(matcher.group(1)), "…")));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
