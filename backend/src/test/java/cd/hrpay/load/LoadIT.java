package cd.hrpay.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.it.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

/**
 * The phase 9 load test, run on its own with {@code ./gradlew loadTest} (it adds 5,000 people to the test
 * database). Targets: the payroll of 5,000 employees in under 5 minutes, and 1,000 people signing in and checking
 * in (code by email included) in under 10 minutes.
 */
@Tag("load")
class LoadIT extends IntegrationTest {

    static final int EMPLOYEES = Integer.getInteger("load.employees", 5000);
    static final int CHECK_INS = Integer.getInteger("load.checkIns", 1000);
    static final String INSIDE = "{\"lat\":-4.3037,\"lng\":15.3108,\"accuracyM\":20}";
    // Tables that describe an employee (not what they did): copied from a sample employee for each new person.
    static final List<String> PROFILE_TABLES = List.of("user_role", "employee_profile", "employee_contract", "employee_salary",
            "employee_pay_element", "dependant", "org_assignment", "user_schedule", "leave_ledger");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    cd.hrpay.payroll.ExchangeRates rates;

    void addPeople(int count) {
        long model = jdbc.sql("select id from app_user where email = ?").param(EMPLOYEE).query(Long.class).single();
        jdbc.sql("""
                insert into app_user (email, registration_no, phone_e164, first_name, last_name, password_hash, status, site_id,
                    must_change_password, manager_id, grade_category_id, hire_date, privacy_accepted_at)
                select 'load' || g || '@load.hrpay.cd', 'LOAD-' || g, '+24391' || lpad(g::text, 7, '0'), 'Load', 'Person ' || g,
                    u.password_hash, 'ACTIVE', u.site_id, false, u.manager_id, u.grade_category_id,
                    u.hire_date - (g % 3000), now()
                from app_user u cross join generate_series(1, ?) g where u.id = ?""").param(count).param(model).update();
        for (String table : PROFILE_TABLES) {
            List<String> columns = jdbc.sql("""
                    select column_name from information_schema.columns where table_schema = 'public' and table_name = ?
                    and column_name <> 'user_id' and not (column_name = 'id' and column_default like 'nextval%')
                    and is_identity = 'NO' order by ordinal_position""").param(table).query(String.class).list();
            String list = String.join(", ", columns);
            String from = columns.stream().map(c -> "t." + c).reduce((a, b) -> a + ", " + b).orElseThrow();
            jdbc.sql("insert into " + table + " (user_id" + (list.isEmpty() ? "" : ", " + list) + ") select n.id"
                    + (list.isEmpty() ? "" : ", " + from) + " from " + table + " t cross join app_user n"
                    + " where t.user_id = ? and n.email like 'load%@load.hrpay.cd'").param(model).update();
        }
    }

    @Test
    void payrollOfFiveThousandAndAThousandCheckIns() throws Exception {
        long start = System.nanoTime();
        addPeople(EMPLOYEES);
        System.out.printf("LOAD: added %d people in %.1f s%n", EMPLOYEES, (System.nanoTime() - start) / 1e9);

        rates.store("""
                {"data":[{"currency_code":"USD","rate_date":"2026-09-30","indicative_rate":"2250"}]}""");
        MockHttpSession hr = login(HR);
        start = System.nanoTime();
        String run = mvc.perform(post("/api/payroll/runs/2026-09/calculate").session(hr)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Duration payroll = Duration.ofNanos(System.nanoTime() - start);
        int slips = JsonPath.<List<Object>>read(run, "$.payslips").size();
        System.out.printf("LOAD: payroll of %d payslips in %.1f s (%.1f ms each)%n", slips, payroll.toMillis() / 1e3,
                (double) payroll.toMillis() / slips);
        assertThat(slips).isGreaterThanOrEqualTo(EMPLOYEES);
        assertThat(payroll).isLessThan(Duration.ofMinutes(5));

        // The morning rush: 1,000 people sign in, ask for a code, type it and are checked in, 25 at a time.
        ExecutorService pool = Executors.newFixedThreadPool(25);
        AtomicInteger done = new AtomicInteger();
        start = System.nanoTime();
        List<Future<?>> all = new java.util.ArrayList<>();
        for (int i = 1; i <= CHECK_INS; i++) {
            String email = "load" + i + "@load.hrpay.cd";
            all.add(pool.submit(() -> {
                MockHttpSession s = login(email);
                String started = mvc.perform(json(post("/api/attendance/check-in").session(s), INSIDE))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                String challenge = JsonPath.read(started, "$.challengeId");
                String body = jdbc.sql("select body from outbox_message where recipient = ? order by id desc limit 1")
                        .param(email).query(String.class).single();
                Matcher m = Pattern.compile("code is (\\d{6})").matcher(body);
                assertThat(m.find()).isTrue();
                mvc.perform(json(post("/api/attendance/check-in/confirm").session(s),
                        "{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(challenge, m.group(1)))).andExpect(status().isOk());
                done.incrementAndGet();
                return null;
            }));
        }
        for (Future<?> f : all) {
            f.get();
        }
        pool.shutdown();
        Duration checkIns = Duration.ofNanos(System.nanoTime() - start);
        System.out.printf("LOAD: %d check-ins in %.1f s (%.0f per minute)%n", done.get(), checkIns.toMillis() / 1e3,
                done.get() * 60_000.0 / checkIns.toMillis());
        assertThat(done).hasValue(CHECK_INS);
        assertThat(checkIns).isLessThan(Duration.ofMinutes(10));
    }
}
