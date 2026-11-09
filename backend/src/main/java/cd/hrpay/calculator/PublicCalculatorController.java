package cd.hrpay.calculator;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.notify.Notifications;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The website's salary calculator, open to anyone without signing in, and the visitors who ask for their result by
 * email (leads the provider sees in the console).
 */
@RestController
public class PublicCalculatorController {

    public record Countries(List<String> countries) {}

    public record LeadIn(@NotBlank @Email @Size(max = 320) String email, @Size(max = 150) String companyName,
                         @Size(max = 40) String phone, @Pattern(regexp = "1-50|51-300|301-1000|1000\\+") String employees,
                         @Pattern(regexp = "fr|en") String language, @NotNull @Valid Calculation calculation,
                         /** Left empty by people; filled by robots that fill every field. */ String website) {}

    /** The calculator's inputs, as the website sends them. */
    public record Calculation(@NotBlank @Pattern(regexp = "CD|CG") String country,
                              @NotBlank @Pattern(regexp = "GROSS|NET") String mode, @NotNull BigDecimal amount,
                              @NotBlank @Pattern(regexp = "CDF|XAF|USD") String currency, BigDecimal housing,
                              BigDecimal transport, BigDecimal familyAllowances, BigDecimal bonus, Integer children,
                              @Pattern(regexp = "SINGLE|MARRIED|DIVORCED|WIDOWED|") String maritalStatus,
                              Boolean spouseInCharge, Integer employees, Boolean expatriate) {
        PublicCalculator.Ask ask() {
            return new PublicCalculator.Ask(country, PublicCalculator.Mode.valueOf(mode), amount, currency, housing,
                    transport, familyAllowances, bonus, children == null ? 0 : children, maritalStatus,
                    Boolean.TRUE.equals(spouseInCharge), employees == null ? 1 : employees, Boolean.TRUE.equals(expatriate));
        }
    }

    public record Lead(long id, String email, String companyName, String phone, String countryCode, String employees,
                       String language, String summary, String status, String contactedBy, OffsetDateTime contactedAt,
                       OffsetDateTime createdAt) {}

    /** Calculations per address and minute; leads per address and hour. */
    static final int CALCULATIONS_PER_MINUTE = 60;
    static final int LEADS_PER_HOUR = 5;

    private final PublicCalculator calculator;
    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final NotificationService messages;
    private final Notifications notifications;
    private final AuditService audit;
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public PublicCalculatorController(PublicCalculator calculator, JdbcClient jdbc, Tenants tenants,
                                      NotificationService messages, Notifications notifications,
                                      AuditService audit) {
        this.calculator = calculator;
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.messages = messages;
        this.notifications = notifications;
        this.audit = audit;
    }

    @GetMapping("/api/public/calculator/countries")
    public Countries countries() {
        return new Countries(calculator.countries().stream().sorted().toList());
    }

    @GetMapping("/api/public/calculator")
    public PublicCalculator.Answer calculate(@RequestParam String country, @RequestParam(defaultValue = "GROSS") String mode,
                                             @RequestParam BigDecimal amount, @RequestParam String currency,
                                             @RequestParam(required = false) BigDecimal housing,
                                             @RequestParam(required = false) BigDecimal transport,
                                             @RequestParam(required = false) BigDecimal familyAllowances,
                                             @RequestParam(required = false) BigDecimal bonus,
                                             @RequestParam(defaultValue = "0") int children,
                                             @RequestParam(required = false) String maritalStatus,
                                             @RequestParam(defaultValue = "false") boolean spouseInCharge,
                                             @RequestParam(defaultValue = "1") int employees,
                                             @RequestParam(defaultValue = "false") boolean expatriate,
                                             HttpServletRequest request) {
        limit("c:" + request.getRemoteAddr(), CALCULATIONS_PER_MINUTE, 60_000L);
        if (!List.of("GROSS", "NET").contains(mode)) {
            throw new BusinessRuleException("Choose GROSS or NET.");
        }
        if (maritalStatus != null && !maritalStatus.isBlank()
                && !List.of("SINGLE", "MARRIED", "DIVORCED", "WIDOWED").contains(maritalStatus)) {
            throw new BusinessRuleException("Unknown marital status.");
        }
        return calculator.calculate(new PublicCalculator.Ask(country, PublicCalculator.Mode.valueOf(mode), amount, currency,
                housing, transport, familyAllowances, bonus, children, maritalStatus, spouseInCharge, employees, expatriate));
    }

    /** Emails the visitor the detail of the result and keeps the lead; always the same answer. */
    @PostMapping("/api/public/calculator/leads")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lead(@Valid @RequestBody LeadIn in, HttpServletRequest request) {
        if (in.website() != null && !in.website().isBlank()) {
            return;
        }
        String ip = request.getRemoteAddr();
        PublicCalculator.Answer answer = calculator.calculate(in.calculation().ask());
        String language = in.language() == null ? "fr" : in.language();
        String email = in.email().trim().toLowerCase(Locale.ROOT);
        String summary = summary(answer, in.calculation().mode(), "fr");
        Long id = tenants.callAsPlatform(() -> {
            int fromIp = jdbc.sql("select count(*) from calculator_lead where ip = ? and created_at > now() - interval '1 hour'")
                    .param(ip).query(Integer.class).single();
            if (fromIp >= LEADS_PER_HOUR) {
                return null;
            }
            return jdbc.sql("""
                    insert into calculator_lead (email, company_name, phone, country_code, employees, language, summary, ip)
                    values (?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(email, blank(in.companyName()), blank(in.phone()), answer.country(), in.employees(), language,
                            summary.length() > 2000 ? summary.substring(0, 2000) : summary, ip)
                    .query(Long.class).single();
        });
        if (id == null) {
            return;
        }
        tenants.runAs(TenantContext.providerCompany(), () -> {
            messages.emailAddress(email, subject(answer, language), body(answer, in.calculation().mode(), language));
            audit.recordAs(email, "calculator.lead", "calculator_lead", id, Map.of("country", answer.country()));
            for (Long holder : notifications.holders("platform.manage")) {
                notifications.send(holder, "calculator.lead", () -> Texts.t("Salary calculator: %s asked for the result", email),
                        () -> (in.companyName() == null ? "" : in.companyName().trim() + " · ") + answer.country()
                                + (in.employees() == null ? "" : " · " + in.employees()), "/platform/trials");
            }
        });
    }

    @GetMapping("/api/platform/calculator-leads")
    @PreAuthorize("hasAuthority('platform.manage')")
    public List<Lead> leads() {
        requireProvider();
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select id, email, company_name, phone, country_code, employees, language, summary, status, contacted_by,
                       contacted_at, created_at
                from calculator_lead order by case status when 'NEW' then 0 else 1 end, created_at desc limit 300""")
                .query(Lead.class).list());
    }

    @PostMapping("/api/platform/calculator-leads/{id}/contacted")
    @PreAuthorize("hasAuthority('platform.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void contacted(@PathVariable long id) {
        requireProvider();
        String by = CurrentUser.email();
        int n = tenants.callAsPlatform(() -> jdbc.sql("""
                update calculator_lead set status = 'CONTACTED', contacted_by = ?, contacted_at = now()
                where id = ? and status = 'NEW'""").params(by, id).update());
        if (n == 0) {
            throw new NotFoundException("No new lead with this number.");
        }
        audit.record("calculator.lead_contacted", "calculator_lead", id, Map.of());
    }

    // ---- the email ------------------------------------------------------------------------------------------------

    static String subject(PublicCalculator.Answer a, String language) {
        boolean fr = !"en".equals(language);
        return (fr ? "Votre calcul de salaire " : "Your salary calculation ") + country(a.country(), fr) + " · HR Pay";
    }

    static String body(PublicCalculator.Answer a, String mode, String language) {
        boolean fr = !"en".equals(language);
        StringBuilder b = new StringBuilder();
        b.append(fr ? "Bonjour,\n\nVoici le détail de votre calcul (" : "Hello,\n\nHere is the detail of your calculation (")
                .append(country(a.country(), fr)).append(fr ? ", taux en vigueur le " : ", rates in force on ")
                .append(a.ratesOn()).append(").\n\n");
        b.append(summary(a, mode, language)).append("\n\n");
        b.append(fr ? "Ce calcul est indicatif : il suppose un mois complet travaillé et n'engage pas HR Pay. "
                        + "Pour la paie de tout votre personnel, avec pointage et déclarations, demandez un essai gratuit."
                : "This calculation is a guide: it assumes a full month worked and does not bind HR Pay. "
                        + "For the payroll of all your staff, with attendance and declarations, ask for a free trial.");
        b.append("\n\nHR Pay\n");
        return b.toString();
    }

    static String summary(PublicCalculator.Answer a, String mode, String language) {
        boolean fr = !"en".equals(language);
        StringBuilder b = new StringBuilder();
        b.append(fr ? "Calcul : " : "Calculation: ").append("NET".equals(mode)
                ? (fr ? "du net au brut" : "net to gross") : (fr ? "du brut au net" : "gross to net")).append('\n');
        for (PublicCalculator.Line l : a.lines()) {
            String sign = switch (l.kind()) {
                case "DEDUCTION" -> "- ";
                case "EMPLOYER_CHARGE" -> "  (" + (fr ? "employeur" : "employer") + ") ";
                default -> "+ ";
            };
            b.append(sign).append(label(l.code(), fr)).append(" : ").append(money(l.amount())).append(' ')
                    .append(a.currency()).append('\n');
        }
        b.append(fr ? "Brut : " : "Gross: ").append(money(a.gross())).append(' ').append(a.currency()).append('\n');
        b.append(fr ? "Net à payer : " : "Net pay: ").append(money(a.net())).append(' ').append(a.currency()).append('\n');
        b.append(fr ? "Coût employeur : " : "Employer cost: ").append(money(a.employerCost())).append(' ')
                .append(a.currency());
        if (a.minimum() != null && a.minimum().below()) {
            b.append('\n').append(fr ? "Attention : le salaire de base est sous le minimum légal (" : "Note: the basic salary is below the legal minimum (")
                    .append(money(a.minimum().monthly())).append(' ').append(a.minimum().currency()).append(").");
        }
        return b.toString();
    }

    /** The line names shown to visitors, by code. */
    static String label(String code, boolean fr) {
        String[] l = switch (code) {
            case "BASIC" -> new String[] {"Salaire de base", "Basic salary"};
            case "HOUSING" -> new String[] {"Indemnité de logement", "Housing allowance"};
            case "TRANSPORT" -> new String[] {"Indemnité de transport", "Transport allowance"};
            case "FAMILY_ALLOWANCE" -> new String[] {"Allocations familiales", "Family allowances"};
            case "OTHER_BONUS" -> new String[] {"Primes", "Bonuses"};
            case "CNSS_EMPLOYEE" -> new String[] {"CNSS (part salariale)", "CNSS (employee share)"};
            case "IPR" -> new String[] {"Impôt sur le salaire (IPR / ITS)", "Income tax (IPR / ITS)"};
            case "CNSS_EMPLOYER" -> new String[] {"CNSS (part patronale)", "CNSS (employer share)"};
            case "CNSS_EMPLOYER_FAMILY" -> new String[] {"CNSS prestations familiales", "CNSS family benefits"};
            case "CNSS_EMPLOYER_ACCIDENT" -> new String[] {"CNSS accidents du travail", "CNSS work accidents"};
            case "INPP" -> new String[] {"INPP (formation)", "INPP (training)"};
            case "ONEM" -> new String[] {"ONEM", "ONEM"};
            case "IERE" -> new String[] {"IERE (expatriés)", "IERE (expatriates)"};
            case "TUS" -> new String[] {"TUS (taxe unique sur les salaires)", "TUS (single tax on salaries)"};
            default -> new String[] {code, code};
        };
        return fr ? l[0] : l[1];
    }

    private static String country(String code, boolean fr) {
        return "CG".equals(code) ? (fr ? "République du Congo" : "Republic of the Congo") : (fr ? "RDC" : "DR Congo");
    }

    private static String money(BigDecimal v) {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.FRANCE);
        s.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.00", s).format(v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void limit(String key, int max, long windowMillis) {
        long now = System.currentTimeMillis();
        Deque<Long> times = recent.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many calculations; wait a minute.");
            }
            times.addLast(now);
        }
        if (recent.size() > 10_000) {   // forget idle addresses now and then
            recent.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
    }

    private static void requireProvider() {
        if (!TenantContext.isProvider(TenantContext.companyId())) {
            throw new AccessDeniedException("Only the provider sees the calculator's leads.");
        }
    }
}
