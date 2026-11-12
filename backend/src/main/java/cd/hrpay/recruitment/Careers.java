package cd.hrpay.recruitment;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.AppProperties;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.notify.Notifications;
import cd.hrpay.payment.Company;
import cd.hrpay.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company's public careers page (platform.hrpay.cloud/careers/{short name}) and the job offers staff see in the
 * app. A vacancy marked PUBLIC is on the page as soon as it opens; INTERNAL_FIRST only after its internal days; INTERNAL
 * never. Staff see every open vacancy. Applying from the page needs no account and no fee (AM 012/2008 art. 16).
 */
@Service
public class Careers {

    public static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$");
    /** Names that would clash with the app's own pages. */
    private static final Set<String> RESERVED = Set.of("withdraw", "api", "admin", "login", "help", "status");

    public record Settings(String slug, boolean enabled, String link) {}

    public record Offer(long id, String title, String unit, String place, String contractType, LocalDate closesOn,
                        LocalDate openedOn) {}

    public record Page(String company, String language, boolean hasLogo, List<Offer> offers) {}

    public record Ad(Offer offer, String company, String language, boolean hasLogo, String adText) {}

    public record ApplyInput(String firstName, String lastName, String email, String phone, String coverLetter,
                             boolean keepForFuture, boolean consent, String cvName, String cvType, byte[] cv, String channel) {}

    public record Withdrawal(String company, String title, String stage) {}

    /** A job offer as staff see it, with their own application if they made one. */
    public record JobOffer(long id, String title, String unit, String place, String contractType, LocalDate closesOn,
                           LocalDate openedOn, String visibility, Long applicationId, String stage) {}

    public record JobOfferAd(JobOffer offer, String adText) {}

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    private final JdbcClient jdbc;
    private final Recruitment recruitment;
    private final Company company;
    private final Notifications notifications;
    private final NotificationService mail;
    private final AuditService audit;
    private final AppProperties properties;
    private final Clock clock;

    public Careers(JdbcClient jdbc, Recruitment recruitment, Company company, Notifications notifications,
                   NotificationService mail, AuditService audit, AppProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.recruitment = recruitment;
        this.company = company;
        this.notifications = notifications;
        this.mail = mail;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    private String base() {
        return properties.publicUrl() == null ? "" : properties.publicUrl().replaceAll("/+$", "");
    }

    // ---- settings ----------------------------------------------------------------------------------------------

    public Settings settings() {
        record Row(String careersSlug, boolean careersEnabled) {}
        Row r = jdbc.sql("select careers_slug, careers_enabled from company where id = current_company_id()").query(Row.class).single();
        return new Settings(r.careersSlug(), r.careersEnabled(), r.careersSlug() == null ? null : base() + "/careers/" + r.careersSlug());
    }

    @Transactional
    public Settings saveSettings(String slug, boolean enabled) {
        if (!Recruitment.isHr()) {
            throw new AccessDeniedException("Only HR can change the careers page.");
        }
        String s = slug == null || slug.isBlank() ? null : slug.strip().toLowerCase();
        if (s != null && (!SLUG.matcher(s).matches() || RESERVED.contains(s))) {
            throw new BusinessRuleException("The short name takes 3 to 40 lowercase letters, digits or dashes, without a dash at either end.");
        }
        if (enabled && s == null) {
            throw new BusinessRuleException("Choose a short name before turning the careers page on.");
        }
        if (s != null && jdbc.sql("select count(*) from company where lower(careers_slug) = ? and id <> current_company_id()")
                .param(s).query(Integer.class).single() > 0) {
            throw new ConflictException("Another company already uses this short name.");
        }
        jdbc.sql("update company set careers_slug = ?, careers_enabled = ?, updated_by = ?, updated_at = now() where id = current_company_id()")
                .params(s, enabled, CurrentUser.email()).update();
        audit.record("careers.settings_changed", "company", cd.hrpay.tenancy.TenantContext.companyId(),
                Map.of("slug", s == null ? "" : s, "enabled", enabled));
        return settings();
    }

    /** The company behind a short name, if its page is on (read across companies: the company table is shared). */
    public Long companyOf(String slug) {
        if (slug == null || !SLUG.matcher(slug.toLowerCase()).matches()) {
            return null;
        }
        return jdbc.sql("select id from company where lower(careers_slug) = ? and careers_enabled and status = 'ACTIVE'")
                .param(slug.toLowerCase()).query(Long.class).optional().orElse(null);
    }

    // ---- the public page ---------------------------------------------------------------------------------------

    private static final String PUBLIC_WHERE = """
             where v.status = 'OPEN' and (v.closes_on is null or v.closes_on >= :today)
               and (v.visibility = 'PUBLIC' or (v.visibility = 'INTERNAL_FIRST' and v.opened_on + v.internal_days <= :today))""";

    private static final String OFFER_SELECT = """
            select v.id, v.title, u.name as unit, v.place, v.contract_type, v.closes_on, v.opened_on
            from vacancy v join org_unit u on u.id = v.unit_id""";

    public Page page() {
        List<Offer> offers = jdbc.sql(OFFER_SELECT + " " + PUBLIC_WHERE + " order by v.opened_on desc, v.id desc")
                .param("today", today()).query(Offer.class).list();
        return new Page(company.get().name(), company.language(), company.logo().isPresent(), offers);
    }

    public Ad ad(long vacancyId) {
        Offer o = jdbc.sql(OFFER_SELECT + " " + PUBLIC_WHERE + " and v.id = :id").param("today", today()).param("id", vacancyId)
                .query(Offer.class).optional().orElseThrow(() -> new NotFoundException("This offer is closed or does not exist."));
        String text = jdbc.sql("select ad_text from vacancy where id = ?").param(vacancyId).query(String.class).single();
        return new Ad(o, company.get().name(), company.language(), company.logo().isPresent(), text);
    }

    /** Someone applies from the careers page: their file, the application, a note to HR and an email to them. */
    @Transactional
    public void apply(long vacancyId, ApplyInput in) {
        Ad ad = ad(vacancyId);
        if (!in.consent()) {
            throw new BusinessRuleException("Tick the box to agree that the company keeps your application.");
        }
        if (in.cv() == null) {
            throw new BusinessRuleException("Attach your CV.");
        }
        long candidateId = recruitment.saveCandidate(new Recruitment.CandidateInput(in.firstName(), null, in.lastName(),
                in.email(), in.phone(), null, null, null, null, null, in.cvName(), in.cvType(), in.cv(), in.keepForFuture(),
                null), true, false);
        if (jdbc.sql("select count(*) from application where vacancy_id = ? and candidate_id = ?").params(vacancyId, candidateId)
                .query(Integer.class).single() > 0) {
            throw new ConflictException("You have already applied to this offer.");
        }
        UUID token = UUID.randomUUID();
        String channel = channel(in.channel());
        long id = recruitment.applyId(vacancyId, candidateId, "CAREERS", in.coverLetter());
        jdbc.sql("update application set channel = ?, withdraw_token = ? where id = ?").params(channel, token, id).update();
        String name = (in.firstName().strip() + " " + in.lastName().strip()).strip();
        notifications.sendToHolders("recruitment.manage", "recruitment.applied",
                () -> Texts.t("New application: %s", ad.offer().title()),
                () -> Texts.t("%s applied from the careers page.", name),
                "/recruitment?application=" + id);
        String link = base() + "/careers/withdraw/" + token;
        String company = ad.company();
        Texts.run(ad.language(), () -> mail.emailAddress(in.email().strip(),
                Texts.t("Your application: %s", ad.offer().title()),
                Texts.t("Dear %s,\n\nWe have received your application for the post of %s at %s. We will contact you if your profile matches.\n\nTo withdraw your application and delete your data, open this link: %s\n\n%s",
                        in.firstName().strip(), ad.offer().title(), company, link, company)));
    }

    /** The source the shared link carries (?src=whatsapp): letters, digits and dashes, cut short. */
    static String channel(String raw) {
        if (raw == null) {
            return null;
        }
        String c = raw.toLowerCase().replaceAll("[^a-z0-9-]", "");
        return c.isEmpty() ? null : c.substring(0, Math.min(30, c.length()));
    }

    /** The company of a withdrawal link (read across companies). */
    public Long companyOfToken(UUID token) {
        return jdbc.sql("select company_id from application where withdraw_token = ?").param(token).query(Long.class)
                .optional().orElse(null);
    }

    public Withdrawal withdrawal(UUID token) {
        record Row(String title, String stage) {}
        Row r = jdbc.sql("""
                select v.title, a.stage from application a join vacancy v on v.id = a.vacancy_id where a.withdraw_token = ?""")
                .param(token).query(Row.class).optional().orElseThrow(() -> new NotFoundException("This link is no longer valid."));
        return new Withdrawal(company.get().name(), r.title(), r.stage());
    }

    /**
     * The candidate withdraws: the application is deleted, and their whole file too when nothing else is left in it.
     * A person already hired keeps their file, which is now the employee's.
     */
    @Transactional
    public void withdraw(UUID token) {
        record Row(long id, long candidateId, String stage, Long userId) {}
        Row r = jdbc.sql("""
                select a.id, a.candidate_id, a.stage, c.user_id from application a join candidate c on c.id = a.candidate_id
                where a.withdraw_token = ?""").param(token).query(Row.class).optional()
                .orElseThrow(() -> new NotFoundException("This link is no longer valid."));
        if ("HIRED".equals(r.stage())) {
            throw new ConflictException("You have been hired: contact HR to change your file.");
        }
        jdbc.sql("delete from application where id = ?").param(r.id()).update();
        int left = jdbc.sql("select count(*) from application where candidate_id = ?").param(r.candidateId()).query(Integer.class).single();
        if (left == 0 && r.userId() == null) {
            jdbc.sql("delete from candidate where id = ?").param(r.candidateId()).update();
        }
        audit.record("careers.withdrawn", "application", r.id(), Map.of("fileDeleted", left == 0 && r.userId() == null));
    }

    // ---- job offers for staff ----------------------------------------------------------------------------------

    private static final String JOB_OFFER_SELECT = """
            select v.id, v.title, u.name as unit, v.place, v.contract_type, v.closes_on, v.opened_on, v.visibility,
                   a.id as application_id, a.stage
            from vacancy v join org_unit u on u.id = v.unit_id
            left join application a on a.vacancy_id = v.id
                 and a.candidate_id = (select c.id from candidate c where c.user_id = :me order by c.id limit 1)
            where v.status = 'OPEN' and (v.closes_on is null or v.closes_on >= :today)""";

    private static long meId() {
        Long id = CurrentUser.id();
        if (id == null) {
            throw new AccessDeniedException("Sign in to see job offers.");
        }
        return id;
    }

    public List<JobOffer> jobOffers() {
        return jdbc.sql(JOB_OFFER_SELECT + " order by v.opened_on desc, v.id desc").param("me", meId()).param("today", today())
                .query(JobOffer.class).list();
    }

    public JobOfferAd jobOffer(long id) {
        JobOffer o = jdbc.sql(JOB_OFFER_SELECT + " and v.id = :id").param("me", meId()).param("today", today()).param("id", id)
                .query(JobOffer.class).optional().orElseThrow(() -> new NotFoundException("This offer is closed or does not exist."));
        String text = jdbc.sql("select ad_text from vacancy where id = ?").param(id).query(String.class).single();
        return new JobOfferAd(o, text);
    }

    /** An employee applies in one click. Their manager is not told now, only once HR shortlists them. */
    @Transactional
    public JobOfferAd applyInternal(long vacancyId, String note) {
        long me = meId();
        JobOfferAd o = jobOffer(vacancyId);
        if (o.offer().applicationId() != null && !"WITHDRAWN".equals(o.offer().stage())) {
            throw new ConflictException("You have already applied to this offer.");
        }
        long candidateId = staffCandidate(me);
        if (o.offer().applicationId() != null) {
            jdbc.sql("update application set stage = 'APPLIED', stage_at = now(), cover_letter = ? where id = ?")
                    .params(Recruitment.blank(note), o.offer().applicationId()).update();
            recruitment.event(o.offer().applicationId(), "APPLIED", null);
        } else {
            recruitment.applyId(vacancyId, candidateId, "INTERNAL", note);
        }
        String name = jdbc.sql("select person_name(first_name, middle_name, last_name) from app_user where id = ?").param(me)
                .query(String.class).single();
        long applicationId = jdbc.sql("select id from application where vacancy_id = ? and candidate_id = ?")
                .params(vacancyId, candidateId).query(Long.class).single();
        notifications.sendToHolders("recruitment.manage", "recruitment.applied",
                () -> Texts.t("New application: %s", o.offer().title()),
                () -> Texts.t("%s (staff) applied to the job offer.", name),
                "/recruitment?application=" + applicationId);
        return jobOffer(vacancyId);
    }

    /** The employee takes back their application while HR has not decided yet. */
    @Transactional
    public JobOfferAd withdrawInternal(long vacancyId) {
        JobOfferAd o = jobOffer(vacancyId);
        String stage = o.offer().stage();
        if (o.offer().applicationId() == null || !Set.of("APPLIED", "SCREENED", "INTERVIEW", "OFFER", "ON_HOLD").contains(stage)) {
            throw new ConflictException("There is no application to withdraw.");
        }
        jdbc.sql("update application set stage = 'WITHDRAWN', stage_at = now() where id = ?").param(o.offer().applicationId()).update();
        recruitment.event(o.offer().applicationId(), "WITHDRAWN", Texts.t("Withdrawn by the employee."));
        return jobOffer(vacancyId);
    }

    /** The employee's candidate file, made from their user and profile the first time they apply. */
    long staffCandidate(long userId) {
        Long id = jdbc.sql("select id from candidate where user_id = ? order by id limit 1").param(userId).query(Long.class)
                .optional().orElse(null);
        if (id != null) {
            return id;
        }
        return jdbc.sql("""
                insert into candidate (first_name, middle_name, last_name, email, phone, birth_date, gender, nationality_code,
                                       user_id, consent_at)
                select u.first_name, u.middle_name, u.last_name, lower(u.email), u.phone_e164, p.birth_date, p.gender,
                       p.nationality_code, u.id, ?
                from app_user u left join employee_profile p on p.user_id = u.id where u.id = ? returning id""")
                .params(OffsetDateTime.now(clock), userId).query(Long.class).single();
    }
}
