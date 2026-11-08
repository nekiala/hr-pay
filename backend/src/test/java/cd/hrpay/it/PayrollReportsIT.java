package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** The payroll general reports add up to the payroll run, and the livre de paie follows the 33 mentions. */
class PayrollReportsIT extends IntegrationTest {

    private static final String MONTH = "2026-02";

    @Autowired
    JdbcClient jdbc;

    private static BigDecimal dec(Object v) {
        return v == null || v.toString().isEmpty() ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private String report(MockHttpSession s, String path) throws Exception {
        return mvc.perform(get("/api/payroll/reports/" + MONTH + "/" + path).session(s)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The cells of the TOTAL rows of a table, for one currency column value. */
    private static List<Object> total(String json, int table, int currencyColumn, String currency) {
        List<Map<String, Object>> rows = JsonPath.read(json, "$.tables[" + table + "].rows");
        return rows.stream().filter(r -> "TOTAL".equals(r.get("style")))
                .map(r -> (List<Object>) r.get("cells"))
                .filter(c -> currency.equals(c.get(currencyColumn))).findFirst().orElseThrow();
    }

    private static int column(String json, int table, String label) {
        List<String> labels = JsonPath.read(json, "$.tables[" + table + "].columns[*].label");
        int i = labels.indexOf(label);
        assertThat(i).as(label).isNotNegative();
        return i;
    }

    @Test
    void reportsAddUpToTheRunAndTheLivreFollowsTheModel() throws Exception {
        usdRate("2026-02-27", 2800);
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        // Bonuses, so the payslips carry earnings, contributions and tax even without check-ins.
        String elementList = mvc.perform(get("/api/pay-elements").session(hr)).andReturn().getResponse().getContentAsString();
        long bonus = JsonPath.<List<Number>>read(elementList, "$[?(@.code=='PERF_BONUS')].id").getFirst().longValue();
        for (var e : Map.of("employee1@demo.hrpay.cd", 600, "employee2@demo.hrpay.cd", 250, "manager@demo.hrpay.cd", 900).entrySet()) {
            long user = jdbc.sql("select id from app_user where email = ?").param(e.getKey()).query(Long.class).single();
            mvc.perform(json(post("/api/pay-entries").session(hr), """
                            {"userId":%d,"elementId":%d,"month":"%s","amount":%d,"currency":"USD"}""".formatted(user, bonus, MONTH, e.getValue())))
                    .andExpect(status().isCreated());
        }
        String run = mvc.perform(post("/api/payroll/runs/" + MONTH + "/calculate").session(hr).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        BigDecimal gross = dec(JsonPath.<List<Object>>read(run, "$.totals[?(@.currency=='USD')].gross").getFirst());
        BigDecimal net = dec(JsonPath.<List<Object>>read(run, "$.totals[?(@.currency=='USD')].net").getFirst());
        BigDecimal deductions = dec(JsonPath.<List<Object>>read(run, "$.totals[?(@.currency=='USD')].employeeDeductions").getFirst());
        int payslips = JsonPath.<Integer>read(run, "$.run.employeeCount");
        assertThat(payslips).isGreaterThanOrEqualTo(5);
        assertThat(gross).isGreaterThan(BigDecimal.valueOf(1700));
        assertThat(deductions).isPositive();

        // Payslips keep their site, grade category and grade.
        assertThat(jdbc.sql("""
                select count(*) from payslip p join payroll_run r on r.id = p.run_id
                where r.month = '2026-02-01' and (p.grade_id is null or p.category_id is null)""").query(Integer.class).single()).isZero();

        // The summary: provisional before closing, totals equal to the run's.
        String summary = report(hr, "summary");
        assertThat(JsonPath.<Boolean>read(summary, "$.provisional")).isTrue();
        List<List<Object>> glance = JsonPath.read(summary, "$.tables[0].rows[*].cells");
        int usd = column(summary, 0, "USD");
        assertThat(dec(glance.get(1).get(usd))).isEqualByComparingTo(gross);
        assertThat(dec(glance.get(5).get(usd))).isEqualByComparingTo(net);

        // By grade category then grade: the grand total is the run's.
        String by = report(hr, "by?dim=CATEGORY&then=GRADE");
        int cur = column(by, 0, "Currency");
        List<Object> t = total(by, 0, cur, "USD");
        assertThat(dec(t.get(column(by, 0, "Gross")))).isEqualByComparingTo(gross);
        assertThat(dec(t.get(column(by, 0, "Net")))).isEqualByComparingTo(net);
        assertThat(((Number) t.get(column(by, 0, "Employees"))).intValue()).isEqualTo(payslips);

        // Every other grouping adds up the same.
        for (String dim : List.of("SITE", "UNIT", "PAYMENT_METHOD", "NATIONALITY", "CONTRACT_TYPE", "OCCUPATION", "COST_CENTRE", "CURRENCY")) {
            String r = report(hr, "by?dim=" + dim + "&level=2");
            assertThat(dec(total(r, 0, column(r, 0, "Currency"), "USD").get(column(r, 0, "Net")))).as(dim).isEqualByComparingTo(net);
        }
        String tree = report(hr, "tree");
        assertThat(dec(total(tree, 0, column(tree, 0, "Currency"), "USD").get(column(tree, 0, "Gross")))).isEqualByComparingTo(gross);

        // By pay element: earnings add up to the gross, deductions to the employee deductions.
        String elements = report(hr, "elements");
        assertThat(dec(total(elements, 0, 2, "USD").get(5))).isEqualByComparingTo(gross);
        assertThat(dec(total(elements, 1, 2, "USD").get(5))).isEqualByComparingTo(deductions);

        String cross = report(hr, "cross?dim=UNIT&level=2");
        assertThat(dec(total(cross, 0, 1, "USD").get(column(cross, 0, "Net")))).isEqualByComparingTo(net);

        // In the local currency at the month's rate.
        String local = report(hr, "by?dim=CATEGORY&currency=LOCAL");
        List<Map<String, Object>> rows = JsonPath.read(local, "$.tables[0].rows");
        BigDecimal localNet = dec(((List<Object>) rows.getLast().get("cells")).get(column(local, 0, "Net")));
        assertThat(localNet.subtract(net.multiply(BigDecimal.valueOf(2800))).abs()).isLessThan(BigDecimal.valueOf(payslips));

        // The employees of one group.
        long category = jdbc.sql("select id from grade_category where code = 'EXEC'").query(Long.class).single();
        String people = report(hr, "employees?dim=CATEGORY&key=" + category);
        assertThat(JsonPath.<List<Object>>read(people, "$.tables[0].rows").size()).isGreaterThanOrEqualTo(2);
        long top = jdbc.sql("select id from org_unit where parent_id is null order by id limit 1").query(Long.class).single();
        String branch = report(hr, "employees?dim=UNIT_BRANCH&key=" + top);
        assertThat(JsonPath.<List<Object>>read(branch, "$.tables[0].rows").size()).isGreaterThanOrEqualTo(2);
        String element = report(hr, "employees?element=PERF_BONUS");
        assertThat(JsonPath.<List<Object>>read(element, "$.tables[0].rows")).hasSize(4);  // three people and the total

        // Payment methods, differences with the month before.
        report(hr, "payment");
        report(hr, "variance");

        // The livre de paie waits for the month to be closed.
        mvc.perform(get("/api/payroll/reports/" + MONTH + "/livre").session(hr)).andExpect(status().is(422));
        mvc.perform(post("/api/payroll/runs/" + MONTH + "/approve").session(finance).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/payroll/runs/" + MONTH + "/close").session(hr).with(csrf())).andExpect(status().isOk());

        String livre = report(hr, "livre");
        assertThat(JsonPath.<Boolean>read(livre, "$.provisional")).isFalse();
        List<String> labels = JsonPath.read(livre, "$.tables[0].columns[*].label");
        assertThat(labels).hasSize(34);
        assertThat(labels.get(19)).startsWith("20 ");
        assertThat(labels.get(32)).startsWith("33 ");
        List<List<Object>> lines = JsonPath.read(livre, "$.tables[0].rows[*].cells");
        assertThat(lines).hasSize(payslips);
        BigDecimal netCdf = BigDecimal.ZERO;
        for (List<Object> l : lines) {
            // 20 = 7 + 10 + 11 + 12 + 13 + 16 + 19; 26 = 21 to 25; 31 = 20 - 26 + 30.
            assertThat(dec(l.get(6)).add(dec(l.get(9))).add(dec(l.get(10))).add(dec(l.get(11))).add(dec(l.get(12)))
                    .add(dec(l.get(15))).add(dec(l.get(18)))).isEqualByComparingTo(dec(l.get(19)));
            assertThat(dec(l.get(20)).add(dec(l.get(21))).add(dec(l.get(22))).add(dec(l.get(23))).add(dec(l.get(24))))
                    .isEqualByComparingTo(dec(l.get(25)));
            assertThat(dec(l.get(19)).subtract(dec(l.get(25))).add(dec(l.get(29)))).isEqualByComparingTo(dec(l.get(30)));
            assertThat(l.get(33)).isEqualTo("CDF");
            netCdf = netCdf.add(dec(l.get(30)));
        }
        assertThat(netCdf.subtract(net.multiply(BigDecimal.valueOf(2800))).abs()).isLessThan(BigDecimal.valueOf(payslips));
        assertThat(JsonPath.<List<String>>read(livre, "$.notes").stream().anyMatch(n -> n.contains("fingerprint"))).isTrue();

        // Downloads: PDF and Excel, written to the audit log.
        byte[] pdf = mvc.perform(get("/api/payroll/reports/" + MONTH + "/livre?format=pdf").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        byte[] summaryPdf = mvc.perform(get("/api/payroll/reports/" + MONTH + "/by?dim=SITE&format=pdf").session(finance))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(summaryPdf, 0, 5)).isEqualTo("%PDF-");
        byte[] book = mvc.perform(get("/api/payroll/reports/" + MONTH + "/all?format=xlsx").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(book, 0, 2)).isEqualTo("PK");
        String out = System.getenv("REPORT_OUT");  // to look at the files by hand
        if (out != null) {
            java.nio.file.Files.write(java.nio.file.Path.of(out, "livre.pdf"), pdf);
            java.nio.file.Files.write(java.nio.file.Path.of(out, "by-site.pdf"), summaryPdf);
            java.nio.file.Files.write(java.nio.file.Path.of(out, "all.xlsx"), book);
            for (String r : List.of("summary", "by?dim=CATEGORY%26then=GRADE", "tree", "cross?dim=UNIT%26level=2", "variance", "payment")) {
                byte[] b = mvc.perform(get("/api/payroll/reports/" + MONTH + "/" + r.replace("%26", "&") + (r.contains("?") ? "&" : "?") + "format=pdf")
                        .session(hr)).andReturn().getResponse().getContentAsByteArray();
                java.nio.file.Files.write(java.nio.file.Path.of(out, r.replaceAll("[^a-z]", "-") + ".pdf"), b);
            }
        }
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'payroll_report.exported'").query(Integer.class).single())
                .isGreaterThanOrEqualTo(3);

        // Employees do not see the reports.
        mvc.perform(get("/api/payroll/reports/" + MONTH + "/summary").session(login(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/payroll/reports/options").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions[0].code").value("CATEGORY"));
    }
}
