package cd.hrpay.tenancy;

/**
 * The company the current thread works for. Every database connection the app takes is told this company
 * (app.company_id), and PostgreSQL then shows and accepts only that company's rows. Set by {@link Tenants} for a
 * request, a scheduled job or a sign-in; outside them no company is set and company tables look empty.
 */
public final class TenantContext {

    /** A company, or the platform scope that sees every company (bypass), or nothing. */
    record Scope(Long companyId, boolean bypass) {
        static final Scope NONE = new Scope(null, false);
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    /** Used when nothing is set: off in production; tests set it so their own database calls reach company 1. */
    private static volatile Long fallback;

    /** The provider's own company, whose administrators run the installation. */
    private static volatile Long provider = 1L;

    /** Permissions about the installation itself; they only work in the provider's company. */
    public static final java.util.Set<String> PLATFORM_PERMISSIONS = java.util.Set.of("platform.manage", "system.monitor");

    private TenantContext() {}

    public static boolean isProvider(Long companyId) {
        return companyId != null && companyId.equals(provider);
    }

    public static Long providerCompany() {
        return provider;
    }

    static void provider(Long companyId) {
        provider = companyId;
    }

    /** The current company's id, or null when none is set. */
    public static Long companyId() {
        return scope().companyId();
    }

    public static boolean bypass() {
        return scope().bypass();
    }

    static Scope scope() {
        Scope s = CURRENT.get();
        if (s != null) {
            return s;
        }
        return fallback == null ? Scope.NONE : new Scope(fallback, false);
    }

    static Scope raw() {
        return CURRENT.get();
    }

    static void set(Scope scope) {
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
    }

    static void fallback(Long companyId) {
        fallback = companyId;
    }
}
