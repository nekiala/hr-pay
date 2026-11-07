package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BCC exchange rates, fetched from the configured API (a JSON {data: [{currency_code, rate_date, indicative_rate,
 * source_name, source_url}]}) and stored per currency and day. HR can enter a rate by hand when the API is down.
 */
@Service
public class ExchangeRates {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRates.class);

    public record Rate(long id, String currency, LocalDate rateDate, BigDecimal cdfPerUnit, String source,
                       String sourceUrl, String enteredBy, OffsetDateTime fetchedAt) {}

    public record FetchResult(int stored, int alreadyKnown, String error) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final RestClient http;
    private final boolean fetchEnabled;

    public ExchangeRates(JdbcClient jdbc, AuditService audit, Clock clock,
                         @Value("${app.exchange-rates.url:https://exchange-api.ntoprog.org/api/v1/exchange-rates/current}") String url,
                         @Value("${app.exchange-rates.fetch-enabled:true}") boolean fetchEnabled) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("User-Agent", "hr-pay").build();
        this.fetchEnabled = fetchEnabled;
    }

    public List<Rate> recent(String currency, int days) {
        return jdbc.sql("""
                select id, trim(currency) as currency, rate_date, cdf_per_unit, source, source_url, entered_by, fetched_at
                from exchange_rate where currency = ? order by rate_date desc limit ?""")
                .params(currency, days).query(Rate.class).list();
    }

    /** The latest rate on or before the date. */
    public Optional<Rate> on(String currency, LocalDate date) {
        return jdbc.sql("""
                select id, trim(currency) as currency, rate_date, cdf_per_unit, source, source_url, entered_by, fetched_at
                from exchange_rate where currency = ? and rate_date <= ? order by rate_date desc limit 1""")
                .params(currency, date).query(Rate.class).optional();
    }

    /** Converts with the CDF-per-unit rate given; CDF is the pivot. Rounded to 2 decimals. */
    public static BigDecimal convert(BigDecimal amount, String from, String to, BigDecimal usdCdf) {
        if (from.equals(to)) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
        if (from.equals("USD") && to.equals("CDF")) {
            return amount.multiply(usdCdf).setScale(2, RoundingMode.HALF_UP);
        }
        if (from.equals("CDF") && to.equals("USD")) {
            return amount.divide(usdCdf, 2, RoundingMode.HALF_UP);
        }
        throw new BusinessRuleException("Cannot convert " + from + " to " + to + ".");
    }

    @Transactional
    public Rate enter(String currency, LocalDate date, BigDecimal cdfPerUnit) {
        if (jdbc.sql("select count(*) from exchange_rate where currency = ? and rate_date = ?").params(currency, date)
                .query(Integer.class).single() > 0) {
            throw new ConflictException("A " + currency + " rate for " + date + " is already stored.");
        }
        jdbc.sql("""
                insert into exchange_rate (currency, rate_date, cdf_per_unit, source, entered_by)
                values (?, ?, ?, 'Entered by HR', ?)""").params(currency, date, cdfPerUnit, CurrentUser.email()).update();
        audit.record("exchange_rate.entered", "exchange_rate", null, Map.of("currency", currency,
                "date", date.toString(), "cdfPerUnit", cdfPerUnit.toPlainString()));
        return on(currency, date).orElseThrow();
    }

    /** Fetches today's rates; keeps rates already stored for a day (the API repeats the latest one). */
    public FetchResult fetch() {
        String body;
        try {
            body = http.get().retrieve().body(String.class);
        } catch (RuntimeException e) {
            log.warn("Exchange rate fetch failed: {}", e.getMessage());
            return new FetchResult(0, 0, "The exchange rate service did not answer: " + e.getMessage());
        }
        return store(body);
    }

    @Transactional
    public FetchResult store(String body) {
        JsonNode data;
        try {
            data = json.readTree(body).path("data");
        } catch (RuntimeException e) {
            return new FetchResult(0, 0, "The exchange rate service sent an unreadable answer.");
        }
        int stored = 0;
        int known = 0;
        for (JsonNode row : data) {
            String currency = row.path("currency_code").asString("").trim().toUpperCase();
            String date = row.path("rate_date").asString("");
            String rate = row.path("indicative_rate").asString("");
            if (!currency.matches("[A-Z]{3}") || currency.equals("CDF") || date.isBlank() || rate.isBlank()) {
                continue;
            }
            BigDecimal value;
            LocalDate day;
            try {
                value = new BigDecimal(rate);
                day = LocalDate.parse(date.substring(0, 10));
            } catch (RuntimeException e) {
                continue;
            }
            if (value.signum() <= 0) {
                continue;
            }
            int inserted = jdbc.sql("""
                    insert into exchange_rate (currency, rate_date, cdf_per_unit, source, source_url)
                    values (?, ?, ?, ?, ?) on conflict (currency, rate_date) do nothing""")
                    .params(currency, day, value, trim(row.path("source_name").asString("BCC"), 100),
                            trim(row.path("source_url").asString(null), 500))
                    .update();
            if (inserted > 0) {
                stored++;
            } else {
                known++;
            }
        }
        if (stored > 0) {
            audit.recordAs("system", "exchange_rate.fetched", "exchange_rate", null, Map.of("stored", stored));
        }
        return new FetchResult(stored, known, stored + known == 0 ? "The answer held no usable rate." : null);
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    /** Twice a day on working hours, Kinshasa time. */
    @Scheduled(cron = "${app.exchange-rates.cron:0 15 9,16 * * *}", zone = "Africa/Kinshasa")
    public void scheduledFetch() {
        if (fetchEnabled) {
            FetchResult r = fetch();
            log.info("Exchange rates: {} stored, {} already known{}", r.stored(), r.alreadyKnown(),
                    r.error() == null ? "" : ", " + r.error());
        }
    }

    /** Fetches once in the background when the app starts, so a fresh install has a rate. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void fetchAtStartup() {
        if (fetchEnabled) {
            Thread.ofVirtual().name("exchange-rates").start(this::scheduledFetch);
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
