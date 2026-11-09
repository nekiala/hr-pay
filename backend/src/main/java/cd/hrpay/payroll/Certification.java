package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.Sheet;
import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.security.CurrentUser;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sign-off of the rates and formulas by a payroll expert. The workbook lists everything the payroll engine
 * uses, as it is set today, with a column to tick and comment each line; the sign-off records who approved them and
 * when, and the Rates page then shows every rate added since.
 */
@Service
public class Certification {

    public record SignOff(long id, String certifiedBy, LocalDate certifiedOn, String note, String createdBy,
                          OffsetDateTime createdAt) {}

    public record Status(SignOff last, int ratesChangedSince, int bracketsChangedSince) {}

    private final JdbcClient jdbc;
    private final StatutoryRates statutory;
    private final Accounting accounting;
    private final Company company;
    private final AuditService audit;
    private final Clock clock;

    public Certification(JdbcClient jdbc, StatutoryRates statutory, Accounting accounting, Company company,
                         AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.statutory = statutory;
        this.accounting = accounting;
        this.company = company;
        this.audit = audit;
        this.clock = clock;
    }

    public Status status() {
        SignOff last = jdbc.sql("""
                select id, certified_by, certified_on, note, created_by, created_at from rate_certification
                order by created_at desc limit 1""").query(SignOff.class).optional().orElse(null);
        if (last == null) {
            return new Status(null, 0, 0);
        }
        int rates = jdbc.sql("select count(*) from statutory_rate where created_at > ?").param(last.createdAt())
                .query(Integer.class).single();
        int brackets = jdbc.sql("select count(distinct effective_from) from ipr_bracket where created_at > ?")
                .param(last.createdAt()).query(Integer.class).optional().orElse(0);
        return new Status(last, rates, brackets);
    }

    @Transactional
    public Status record(String certifiedBy, LocalDate certifiedOn, String note) {
        if (certifiedBy == null || certifiedBy.isBlank() || certifiedOn == null) {
            throw new BusinessRuleException("Give the name of the person who signed off and the date.");
        }
        if (certifiedOn.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("The sign-off date cannot be in the future.");
        }
        jdbc.sql("insert into rate_certification (certified_by, certified_on, note, created_by) values (?, ?, ?, ?)")
                .params(certifiedBy.trim(), certifiedOn, note == null || note.isBlank() ? null : note.trim(), CurrentUser.email())
                .update();
        audit.record("payroll.rates_certified", "rate_certification", certifiedOn.toString(),
                Map.of("by", certifiedBy.trim()));
        return status();
    }

    /** Every rate, tax band, pay element, setting and formula, to be checked line by line. */
    public void workbook(Sheet sheet) throws IOException {
        String country = company.country();
        LocalDate today = LocalDate.now(clock);
        sheet.row(Texts.t("HR Pay: rates and formulas to check"), company.get().name(),
                Texts.t(cd.hrpay.platform.CountryPacks.COUNTRIES.getOrDefault(country, "DR Congo")), Texts.day(today));
        sheet.row(Texts.t("Tick each line in the Checked column, write the correct value in Comment when it is wrong, then sign at the end."));
        sheet.row();

        sheet.row(Texts.t("1. Rates"));
        sheet.row(Texts.t("Code"), Texts.t("Rate"), Texts.t("Value"), Texts.t("Unit"), Texts.t("Since"), Texts.t("Description"),
                Texts.t("Checked"), Texts.t("Comment"));
        for (StatutoryRates.RateType r : statutory.types(today)) {
            sheet.row(r.code(), r.name(), r.value(), r.unit(), r.effectiveFrom() == null ? "" : r.effectiveFrom().toString(),
                    r.description(), "", "");
        }
        sheet.row();

        var brackets = statutory.brackets(today);
        CountryRules rules = statutory.countryRules(today);
        sheet.row("CG".equals(country) || rules != null && rules.familyQuotient()
                        ? Texts.t("2. %s bands (yearly, per part)", cd.hrpay.platform.CountryPacks.taxName(country))
                        : Texts.t("2. %s bands (yearly)", cd.hrpay.platform.CountryPacks.taxName(country)),
                Texts.t("since %s", brackets.effectiveFrom()));
        sheet.row(Texts.t("From"), Texts.t("To"), Texts.t("Rate %"), "", "", "", Texts.t("Checked"), Texts.t("Comment"));
        for (IprCalculator.Bracket b : brackets.brackets()) {
            sheet.row(b.lowerBound(), b.upperBound() == null ? Texts.t("and above") : b.upperBound(), b.ratePercent(), "", "", "", "", "");
        }
        sheet.row();

        if (rules != null) {
            sheet.row(Texts.t("2b. Contributions and taxes"));
            sheet.row(Texts.t("Code"), Texts.t("Paid by"), Texts.t("Worked out on"), Texts.t("Rate"), Texts.t("Ceiling"),
                    Texts.t("Not charged"), Texts.t("Scale"), Texts.t("Before tax"), Texts.t("Checked"), Texts.t("Comment"));
            for (CountryRules.Contribution c : rules.contributions()) {
                sheet.row(c.code(), Texts.t(c.employee() ? "Employee" : "Employer"), Texts.t(baseLabel(c.base())),
                        c.rate() == null ? "" : c.rate(), c.ceiling() == null ? "" : c.ceiling(),
                        c.exempt() == null ? "" : c.exempt(), c.scale() == null ? "" : c.scale(), yes(c.beforeTax()), "", "");
            }
            for (Map.Entry<String, java.util.List<CountryRules.Band>> s : new java.util.TreeMap<>(rules.scales()).entrySet()) {
                sheet.row(Texts.t("Scale %s (a month)", s.getKey()));
                sheet.row(Texts.t("From"), Texts.t("To"), Texts.t("Amount"), "", "", "", "", "", Texts.t("Checked"), Texts.t("Comment"));
                for (CountryRules.Band b : s.getValue()) {
                    sheet.row(b.lower(), b.upper() == null ? Texts.t("and above") : b.upper(), b.amount(), "", "", "", "", "", "", "");
                }
            }
            sheet.row();
        }

        sheet.row(Texts.t("3. Pay elements"));
        sheet.row(Texts.t("Code"), Texts.t("Name"), Texts.t("Kind"), Texts.t("Method"), Texts.t("Subject to CNSS"),
                Texts.t("Taxable"), Texts.t("Tax-free share (% of basic)"), Texts.t("Prorated"), Texts.t("Expense account"),
                Texts.t("Debt account"), Texts.t("Checked"), Texts.t("Comment"));
        Map<Long, Accounting.ElementAccounts> accounts = new java.util.HashMap<>();
        accounting.accounts().forEach(a -> accounts.put(a.elementId(), a));
        jdbc.sql("""
                select id, code, name, kind, method, subject_to_cnss, taxable_ipr, ipr_exempt_cap_percent, prorated
                from pay_element where active order by display_order, code""")
                .query((rs, i) -> {
                    var a = accounts.get(rs.getLong("id"));
                    sheet.row(rs.getString("code"), rs.getString("name"), Texts.t(label(rs.getString("kind"))),
                            Texts.t(label(rs.getString("method"))), yes(rs.getBoolean("subject_to_cnss")),
                            yes(rs.getBoolean("taxable_ipr")), rs.getBigDecimal("ipr_exempt_cap_percent"),
                            yes(rs.getBoolean("prorated")),
                            a == null ? "" : a.expenseAccount() != null ? a.expenseAccount() : a.defaultExpense(),
                            a == null ? "" : a.liabilityAccount() != null ? a.liabilityAccount() : a.defaultLiability(), "", "");
                    return null;
                }).list();
        sheet.row();

        sheet.row(Texts.t("4. Settings"));
        for (Map.Entry<String, String> s : statutory.settings().entrySet()) {
            sheet.row(s.getKey(), s.getValue(), "", "", "", "", "", "");
        }
        sheet.row();

        sheet.row(Texts.t("5. How a payslip is worked out"));
        int n = 0;
        for (String f : formulas(country)) {
            sheet.row(++n, Texts.t(f), "", "", "", "", "", "");
        }
        sheet.row();
        sheet.row(Texts.t("6. Sign-off"));
        sheet.row(Texts.t("Checked and approved by (name, title)"), "", "", Texts.t("Date"), "", Texts.t("Signature"));
        sheet.finish();
        audit.record("payroll.certification_workbook", "company", country, Map.of());
    }

    private static String baseLabel(String base) {
        return switch (base) {
            case "CNSS" -> "Earnings subject to social security";
            case "GROSS" -> "Gross pay";
            case "TAXABLE" -> "Taxable earnings";
            case "NET_TAXABLE" -> "Taxable earnings less contributions before tax";
            case "TAX" -> "Income tax";
            case "BASIC" -> "Basic salary";
            default -> base;
        };
    }

    private static String yes(boolean v) {
        return v ? Texts.t("Yes") : Texts.t("No");
    }

    private static String label(String code) {
        return switch (code) {
            case "EARNING" -> "Earning";
            case "DEDUCTION" -> "Deduction";
            case "EMPLOYER_CHARGE" -> "Employer charge";
            case "COMPUTED" -> "Calculated by payroll";
            case "FIXED" -> "Fixed amount per month";
            case "PERCENT_OF_BASIC" -> "% of basic salary";
            case "PER_DAY_WORKED" -> "Per day worked";
            case "PER_CHILD" -> "Per child in charge";
            case "MANUAL" -> "Entered each month";
            default -> code;
        };
    }

    /** The steps of the payroll engine, in the order it applies them. */
    static List<String> formulas(String country) {
        List<String> common = List.of(
                "Basic salary = monthly basic × paid hours ÷ scheduled hours of the month (absences without pay, late arrivals and early departures beyond the grace period are not paid).",
                "Overtime = approved overtime hours × hourly rate × the rate of its tier (weekday, rest day, public holiday); the hourly rate is the monthly basic ÷ scheduled hours.",
                "Allowances and bonuses: a fixed amount, a % of basic, an amount per day worked or per child in charge, or an amount entered for the month; prorated ones follow the paid hours.",
                "13th month (when on): basic × the year's average evaluation score (or the fallback %), prorated by the months worked.");
        List<String> tail = List.of(
                "Loans and advances: each month's installment, taken only within the allowed share of the net pay; the rest waits.",
                "Net pay = gross − employee deductions. Amounts in US dollars are converted at the central bank rate of the payroll (last of the month, or of the day it is calculated).",
                "Final settlement: daily rate = basic ÷ days per month; notice pay (when not worked) = (base days + days per year × full years) × daily rate; leave paid out = days left × daily rate; severance = days per year × years × daily rate; the last payslip also takes what is left of loans.",
                "Corrections of a closed month: the payslip is worked out again and the difference is a complementary payslip of that month, or back pay in the next payroll. Closed payslips are never changed.");
        List<String> taxes = cd.hrpay.platform.CountryPacks.RULES_AS_DATA.contains(country) ? List.of(
                "Employee contributions (table 2b): rate × their base, up to their monthly ceiling, less the part not charged; those marked before tax are taken off the taxable pay.",
                "IRPP: yearly taxable income = (monthly taxable earnings − employee contributions before tax) × 12; less the professional deduction % (up to its cap) and the yearly allowance; divided by the family parts when the country uses them; the bands apply to one part, the result is multiplied by the parts and divided by 12. No IRPP below the monthly threshold.",
                "Taxes on the IRPP (such as the CAC) and flat monthly taxes by band (such as the RAV and the TDL) follow table 2b.",
                "Employer charges (table 2b): rate × their base, up to their ceiling.",
                "A basic salary below the monthly SMIG shows a warning.")
                : "CG".equals(country) ? List.of(
                "CNSS employee share = rate × earnings subject to CNSS, up to the monthly pension ceiling.",
                "ITS: yearly taxable income = (monthly taxable earnings − employee CNSS) × 12; less the professional deduction %; divided by the family parts (1, +1 for a spouse, +0.5 per child, up to the maximum); the bands apply to one part, the result is multiplied by the parts and divided by 12.",
                "Employer charges: CNSS pensions up to the pension ceiling; family benefits and work accidents up to their ceiling; the tax on salaries (TUS) on the gross.",
                "A basic salary below the monthly SMIG shows a warning.")
                : List.of(
                "CNSS employee share = rate × earnings subject to CNSS.",
                "IPR: monthly taxable income = taxable earnings − employee CNSS, converted to CDF; the yearly bands apply to it × 12, the result ÷ 12; less the reduction per dependant (up to the maximum number); at most the cap % of the taxable income.",
                "Employer charges: CNSS employer share on earnings subject to CNSS; INPP at the rate of the company's size band; ONEM; IERE on expatriates' pay.",
                "A basic salary below the daily SMIG times the days of the month shows a warning.");
        List<String> all = new java.util.ArrayList<>(common);
        all.addAll(taxes);
        all.addAll(tail);
        return all;
    }
}
