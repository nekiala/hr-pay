package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The month's payroll as accounting entries in the SYSCOHADA chart of accounts, in the local currency at the
 * payroll's rate: earnings and employer charges as expenses (class 66 and 64), what is withheld and the employer
 * charges as debts to the social security, the tax office and the staff (class 42, 43, 44), the net pay as owed to
 * the staff. Each pay element can name its own accounts; the defaults below are to be checked by the accountant.
 */
@Service
public class Accounting {

    /** Default expense accounts by element (personnel national; expatriates use 662x and 6642). */
    static final Map<String, String> EXPENSE = Map.ofEntries(
            Map.entry("BASIC", "6611"), Map.entry("OVERTIME", "6611"), Map.entry("HOLIDAY_ADJ", "6611"),
            Map.entry("HOLIDAY_ADJ_LESS", "6611"), Map.entry("BACK_PAY", "6611"), Map.entry("BACK_PAY_LESS", "6611"),
            Map.entry("PERF_BONUS", "6612"), Map.entry("OTHER_BONUS", "6612"), Map.entry("THIRTEENTH_MONTH", "6612"),
            Map.entry("LEAVE_PAYOUT", "6613"), Map.entry("LEAVE_ALLOWANCE", "6613"), Map.entry("NOTICE_PAY", "6614"), Map.entry("SEVERANCE", "6614"),
            Map.entry("FAMILY_ALLOWANCE", "6616"), Map.entry("HOUSING", "6631"), Map.entry("TRANSPORT", "6634"),
            Map.entry("CNSS_EMPLOYER", "6641"), Map.entry("CNSS_EMPLOYER_FAMILY", "6641"),
            Map.entry("CNSS_EMPLOYER_ACCIDENT", "6641"), Map.entry("INPP", "6413"), Map.entry("ONEM", "6413"),
            Map.entry("IERE", "6413"), Map.entry("TUS", "6413"), Map.entry("CNAMGS_EMPLOYER", "6641"),
            Map.entry("CFC_EMPLOYER", "6413"), Map.entry("FNE", "6413"), Map.entry("FNH", "6413"), Map.entry("CFP", "6413"));

    /** Default debt accounts by element. */
    static final Map<String, String> LIABILITY = Map.ofEntries(
            Map.entry("CNSS_EMPLOYEE", "4310"), Map.entry("IPR", "4472"), Map.entry("LOAN", "4211"),
            Map.entry("ADVANCE", "4212"), Map.entry("LEAVE_ADVANCE", "4212"), Map.entry("QUINZAINE", "4212"), Map.entry("EARNED_ADVANCE", "4212"), Map.entry("OTHER_DEDUCTION", "4230"), Map.entry("CNSS_EMPLOYER", "4310"),
            Map.entry("CNSS_EMPLOYER_FAMILY", "4310"), Map.entry("CNSS_EMPLOYER_ACCIDENT", "4310"),
            Map.entry("INPP", "4478"), Map.entry("ONEM", "4478"), Map.entry("IERE", "4478"), Map.entry("TUS", "4478"),
            Map.entry("CNAMGS_EMPLOYEE", "4310"), Map.entry("CNAMGS_EMPLOYER", "4310"), Map.entry("CAC", "4472"),
            Map.entry("RAV", "4472"), Map.entry("TDL", "4472"), Map.entry("TCS", "4472"), Map.entry("CFC_EMPLOYEE", "4478"),
            Map.entry("CFC_EMPLOYER", "4478"), Map.entry("FNE", "4478"), Map.entry("FNH", "4478"), Map.entry("CFP", "4478"));

    static final String OTHER_EARNING = "6618";
    static final String OTHER_DEDUCTION = "4230";
    static final String OTHER_CHARGE = "6648";

    public record ElementAccounts(long elementId, String code, String name, String kind, String expenseAccount,
                                  String liabilityAccount, String defaultExpense, String defaultLiability) {}

    public record Entry(String account, String label, String costCentre, BigDecimal debit, BigDecimal credit) {}

    public record Journal(String month, String runStatus, String journal, LocalDate date, String reference,
                          String currency, BigDecimal rate, List<Entry> entries, BigDecimal debit, BigDecimal credit,
                          int payslips, List<String> warnings) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public Accounting(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    static String defaultExpense(String code, String kind) {
        if (kind.equals("DEDUCTION")) {
            return null;
        }
        return EXPENSE.getOrDefault(code, kind.equals("EARNING") ? OTHER_EARNING : OTHER_CHARGE);
    }

    static String defaultLiability(String code, String kind) {
        if (kind.equals("EARNING")) {
            return null;
        }
        return LIABILITY.getOrDefault(code, kind.equals("DEDUCTION") ? OTHER_DEDUCTION : "4380");
    }

    public List<ElementAccounts> accounts() {
        return jdbc.sql("""
                select id, code, name, kind, expense_account, liability_account from pay_element order by display_order, code""")
                .query((rs, i) -> new ElementAccounts(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("kind"), rs.getString("expense_account"), rs.getString("liability_account"),
                        defaultExpense(rs.getString("code"), rs.getString("kind")),
                        defaultLiability(rs.getString("code"), rs.getString("kind"))))
                .list();
    }

    @Transactional
    public ElementAccounts saveAccounts(long elementId, String expense, String liability) {
        int n = jdbc.sql("update pay_element set expense_account = ?, liability_account = ? where id = ?")
                .params(blank(expense), blank(liability), elementId).update();
        if (n == 0) {
            throw new NotFoundException("Pay element not found.");
        }
        audit.record("accounting.accounts_changed", "pay_element", elementId,
                Map.of("expense", blank(expense) == null ? "" : expense.trim(), "liability", blank(liability) == null ? "" : liability.trim()));
        return accounts().stream().filter(a -> a.elementId() == elementId).findFirst().orElseThrow();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private record Line(long payslipId, String code, String label, String kind, BigDecimal amount, String currency,
                        boolean expatriate, String costCentre) {}

    /** The journal of a month; by cost centre, the expenses are split by the unit's cost centre. */
    public Journal journal(YearMonth month, boolean byCostCentre) {
        var run = jdbc.sql("select id, status, usd_cdf from payroll_run where month = ?").param(month.atDay(1))
                .query((rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)}).optional()
                .orElseThrow(() -> new NotFoundException("No payroll for %s yet.".formatted(month)));
        long runId = (Long) run[0];
        BigDecimal rate = (BigDecimal) run[2];
        String local = jdbc.sql("select local_currency from company where id = current_company_id()").query(String.class).single();
        Map<String, String[]> accounts = new HashMap<>();
        for (ElementAccounts a : accounts()) {
            accounts.put(a.code(), new String[] {a.expenseAccount() != null ? a.expenseAccount() : a.defaultExpense(),
                    a.liabilityAccount() != null ? a.liabilityAccount() : a.defaultLiability(), a.name()});
        }
        String netAccount = setting("ACCOUNT_NET_PAY", "4220");
        String journalCode = setting("ACCOUNTING_JOURNAL", "PAIE");
        List<Line> lines = jdbc.sql("""
                select l.payslip_id, l.element_code as code, l.label, l.kind, l.amount, trim(p.currency) as currency,
                       coalesce((p.snapshot ->> 'expatriate')::boolean, false) as expatriate, p.snapshot ->> 'costCentre' as cost_centre
                from payslip_line l join payslip p on p.id = l.payslip_id
                where p.run_id = ? and (p.kind = 'REGULAR' or p.number is not null) order by p.id, l.display_order""")
                .param(runId).query(Line.class).list();
        int payslips = (int) lines.stream().mapToLong(Line::payslipId).distinct().count();

        // Debit minus credit, by account, label and cost centre.
        Map<String, BigDecimal> balance = new TreeMap<>();
        Map<String, String[]> keys = new LinkedHashMap<>();
        Map<Long, BigDecimal> net = new LinkedHashMap<>();
        Map<Long, String> centreOf = new HashMap<>();
        for (Line l : lines) {
            BigDecimal v = local(l.amount(), l.currency(), local, rate);
            String[] acc = accounts.getOrDefault(l.code(), new String[] {defaultExpense(l.code(), l.kind()),
                    defaultLiability(l.code(), l.kind()), l.label()});
            String centre = byCostCentre ? l.costCentre() : null;
            centreOf.put(l.payslipId(), centre);
            switch (l.kind()) {
                case "EARNING" -> {
                    post(balance, keys, expat(acc[0], l.expatriate()), acc[2], centre, v);
                    net.merge(l.payslipId(), v, BigDecimal::add);
                }
                case "DEDUCTION" -> {
                    post(balance, keys, acc[1], acc[2], null, v.negate());
                    net.merge(l.payslipId(), v.negate(), BigDecimal::add);
                }
                default -> {
                    post(balance, keys, expat(acc[0], l.expatriate()), acc[2], centre, v);
                    post(balance, keys, acc[1], acc[2], null, v.negate());
                }
            }
        }
        BigDecimal netTotal = net.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        post(balance, keys, netAccount, Texts.t("Net pay owed to staff"), null, netTotal.negate());

        List<Entry> entries = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : balance.entrySet()) {
            BigDecimal v = e.getValue();
            if (v.signum() == 0) {
                continue;
            }
            String[] k = keys.get(e.getKey());
            entries.add(new Entry(k[0], k[1], k[2], v.signum() > 0 ? v : null, v.signum() < 0 ? v.negate() : null));
            if (v.signum() > 0) {
                debit = debit.add(v);
            } else {
                credit = credit.add(v.negate());
            }
        }
        // Expenses first (class 6), then the debts, as accountants read a payroll entry.
        entries.sort(java.util.Comparator.comparing((Entry x) -> x.account().startsWith("6") ? 0 : 1)
                .thenComparing(Entry::account).thenComparing(x -> x.costCentre() == null ? "" : x.costCentre()));
        List<String> warnings = new ArrayList<>();
        if (!"CLOSED".equals(run[1])) {
            warnings.add(Texts.t("The %s payroll is not closed yet: these amounts can still change.", month));
        }
        if (debit.compareTo(credit) != 0) {
            warnings.add(Texts.t("The entries do not balance: debit %s, credit %s.", debit.toPlainString(), credit.toPlainString()));
        }
        if (byCostCentre && centreOf.values().stream().anyMatch(java.util.Objects::isNull)) {
            warnings.add(Texts.t("Some people's units have no cost centre: their expenses have none."));
        }
        warnings.add(Texts.t("Default SYSCOHADA accounts, to check with your accountant before importing."));
        return new Journal(month.toString(), (String) run[1], journalCode, month.atEndOfMonth(),
                journalCode + "-" + month, local, rate, entries, debit, credit, payslips, warnings);
    }

    private static String expat(String account, boolean expatriate) {
        if (!expatriate || account == null) {
            return account;
        }
        if (account.startsWith("661")) {
            return "662" + account.substring(3);
        }
        return account.equals("6641") ? "6642" : account;
    }

    private static void post(Map<String, BigDecimal> balance, Map<String, String[]> keys, String account, String label,
                             String centre, BigDecimal amount) {
        if (account == null) {
            throw new BusinessRuleException("A pay element has no account: set it on the accounting page.");
        }
        String key = account + "|" + (centre == null ? "" : centre) + "|" + label;
        // One line per account (and cost centre): the first element's name labels it.
        String existing = keys.keySet().stream().filter(k -> k.startsWith(account + "|" + (centre == null ? "" : centre) + "|"))
                .findFirst().orElse(null);
        if (existing != null) {
            key = existing;
        } else {
            keys.put(key, new String[] {account, label, centre});
        }
        balance.merge(key, amount, BigDecimal::add);
    }

    private static BigDecimal local(BigDecimal amount, String currency, String local, BigDecimal usdLocal) {
        BigDecimal v = currency.equals(local) ? amount : ExchangeRates.convert(amount, currency, local, usdLocal);
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private String setting(String key, String fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional().orElse(fallback);
    }

    public void exported(YearMonth month) {
        audit.record("accounting.exported", "payroll_run", month.toString(), Map.of("by", String.valueOf(CurrentUser.email())));
    }
}
