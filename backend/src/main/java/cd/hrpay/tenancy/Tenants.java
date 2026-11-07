package cd.hrpay.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs code for one company, or for the whole platform. The company stays set for the code's duration on this
 * thread, including a database transaction already open: its connection is told the new company and told the
 * previous one again afterwards.
 */
@Service
public class Tenants {

    private static final Logger log = LoggerFactory.getLogger(Tenants.class);

    private final DataSource dataSource;
    private final JdbcClient jdbc;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public Tenants(DataSource dataSource, JdbcClient jdbc,
                   @Value("${app.tenancy.default-company:}") String defaultCompany,
                   @Value("${app.tenancy.provider-company:1}") Long providerCompany) {
        TenantContext.provider(providerCompany);
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        TenantContext.fallback(defaultCompany == null || defaultCompany.isBlank() ? null : Long.valueOf(defaultCompany));
    }

    public <T> T callAs(Long companyId, Supplier<T> work) {
        return within(new TenantContext.Scope(companyId, false), work);
    }

    public void runAs(Long companyId, Runnable work) {
        callAs(companyId, () -> {
            work.run();
            return null;
        });
    }

    /** For the few platform tasks that span companies: sees every company's rows. */
    public <T> T callAsPlatform(Supplier<T> work) {
        return within(new TenantContext.Scope(null, true), work);
    }

    /** The company an email signs in to, if any. Emails are unique across companies. */
    public Long companyOfEmail(String email) {
        if (email == null) {
            return null;
        }
        return callAsPlatform(() -> jdbc.sql("select company_id from app_user where lower(email) = lower(?)")
                .param(email.trim()).query(Long.class).optional().orElse(null));
    }

    public List<Long> activeCompanies() {
        return callAsPlatform(() -> jdbc.sql("select id from company where status = 'ACTIVE' order by id")
                .query(Long.class).list());
    }

    /**
     * Runs a scheduled job once for each active company. A failure in one company does not stop the others; the
     * first failure is thrown at the end so the job is reported as failed.
     */
    public void forEachCompany(String job, Consumer<Long> work) {
        List<RuntimeException> failures = new ArrayList<>();
        for (Long id : activeCompanies()) {
            try {
                runAs(id, () -> work.accept(id));
            } catch (RuntimeException e) {
                log.error("Job {} failed for company {}", job, id, e);
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            RuntimeException first = failures.getFirst();
            throw new IllegalStateException(failures.size() + " company(ies) failed: " + first.getMessage(), first);
        }
    }

    private <T> T within(TenantContext.Scope scope, Supplier<T> work) {
        TenantContext.Scope before = TenantContext.raw();
        // Changes JPA still holds belong to the company they were made in: write them before switching.
        flush();
        TenantContext.set(scope);
        applyToOpenTransaction(scope);
        try {
            T result = work.get();
            flush();
            return result;
        } finally {
            TenantContext.set(before);
            applyToOpenTransaction(TenantContext.scope());
        }
    }

    private void flush() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && entityManager.isJoinedToTransaction()) {
            entityManager.flush();
        }
    }

    private void applyToOpenTransaction(TenantContext.Scope scope) {
        if (!(dataSource instanceof TenantDataSource tenantDataSource)
                || !(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder)
                || holder.getConnectionHandle() == null) {
            return;
        }
        try {
            Connection connection = holder.getConnection();
            tenantDataSource.apply(connection, scope);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not switch the database connection to the company", e);
        }
    }
}
