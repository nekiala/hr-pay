package cd.hrpay.lifecycle;

import cd.hrpay.tenancy.Tenants;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The starting document types, checklist steps and letter templates of a company, in its language, made once:
 * at start-up for the companies that have none yet, and when a company is created. HR changes them afterwards.
 * The letter texts are drafts; each company must have them checked by its labour lawyer.
 */
@Component
public class LifecycleDefaults {

    private static final Logger log = LoggerFactory.getLogger(LifecycleDefaults.class);

    private record DocType(String code, String en, String fr, boolean expiry, boolean employeeUploads) {}

    private static final List<DocType> TYPES = List.of(
            new DocType("ID_CARD", "National ID or passport", "Carte d'identité ou passeport", true, true),
            new DocType("PHOTO", "Photo", "Photo d'identité", false, true),
            new DocType("CV", "CV", "CV", false, true),
            new DocType("DIPLOMA", "Diplomas", "Diplômes", false, true),
            new DocType("CRIMINAL_RECORD", "Criminal record extract", "Extrait de casier judiciaire", false, true),
            new DocType("MEDICAL", "Medical fitness certificate", "Certificat d'aptitude médicale", true, true),
            new DocType("WORK_PERMIT", "Work permit (expatriates)", "Carte de travail pour étranger", true, false),
            new DocType("BANK_DETAILS", "Bank details", "Relevé d'identité bancaire", false, true),
            new DocType("CONTRACT", "Contracts", "Contrats", false, false),
            new DocType("LETTER", "Letters", "Lettres", false, false),
            new DocType("CERTIFICATE", "Certificates", "Attestations", false, false),
            new DocType("OTHER", "Other", "Autres", false, true));

    private record Step(String kind, String en, String fr, String action, String documentType) {}

    private static final List<Step> STEPS = List.of(
            new Step("HIRING", "ID or passport collected", "Pièce d'identité reçue", "DOCUMENT", "ID_CARD"),
            new Step("HIRING", "Photo collected", "Photo reçue", "DOCUMENT", "PHOTO"),
            new Step("HIRING", "Diplomas collected", "Diplômes reçus", "DOCUMENT", "DIPLOMA"),
            new Step("HIRING", "Criminal record extract collected", "Extrait de casier judiciaire reçu", "DOCUMENT", "CRIMINAL_RECORD"),
            new Step("HIRING", "Medical fitness certificate collected", "Certificat d'aptitude médicale reçu", "DOCUMENT", "MEDICAL"),
            new Step("HIRING", "Bank details collected", "Relevé d'identité bancaire reçu", "DOCUMENT", "BANK_DETAILS"),
            new Step("HIRING", "Contract signed", "Contrat signé", "CONTRACT_SIGNED", null),
            new Step("HIRING", "Registered with the CNSS", "Immatriculation à la CNSS", null, null),
            new Step("HIRING", "Badge and equipment handed over", "Badge et matériel remis", "EQUIPMENT", null),
            new Step("HIRING", "Enrolled on the time clock", "Enregistrement sur la pointeuse", null, null),
            new Step("HIRING", "Welcomed: team, rules and safety", "Accueil : équipe, règlement intérieur et sécurité", null, null),
            new Step("DEPARTURE", "All equipment returned", "Tout le matériel rendu", "EQUIPMENT", null),
            new Step("DEPARTURE", "Final settlement confirmed", "Décompte final confirmé", "SETTLEMENT", null),
            new Step("DEPARTURE", "Certificate of employment handed over", "Certificat de travail remis", null, null),
            new Step("DEPARTURE", "Exit interview held", "Entretien de départ réalisé", null, null),
            new Step("DEPARTURE", "Access and accounts closed", "Accès et comptes fermés", "ACCOUNT_CLOSED", null));

    private record Letter(String code, String kind, String en, String fr, String bodyEn, String bodyFr, String documentType) {}

    private static final List<Letter> LETTERS = List.of(
            new Letter("CONTRACT_CDI", "CONTRACT", "Permanent employment contract", "Contrat de travail à durée indéterminée", """
                    Between {{company.name}}, {{company.address}}, hereafter the employer,

                    and {{employee.name}}, born on {{employee.birth_date}}, of {{employee.nationality}} nationality, hereafter the employee,

                    ## 1. Engagement
                    The employer engages the employee from {{contract.start}} as {{job.title}}, grade {{job.grade}}, under a permanent contract.

                    ## 2. Place of work
                    The employee works at {{site.name}}, in the unit {{unit.name}}.

                    ## 3. Pay
                    The employee receives a gross monthly salary of {{salary.gross}}, plus the allowances and benefits provided by law, the collective agreement and the company's rules.

                    ## 4. Working time and leave
                    Working time and leave follow the Labour Code, the collective agreement and the company's rules, which the employee declares to have received.

                    ## 5. Trial period
                    The contract starts with a trial period as provided by the Labour Code.

                    ## 6. Other terms
                    Anything this contract does not settle is governed by the Labour Code and the collective agreement in force.""", """
                    Entre {{company.name}}, {{company.address}}, ci-après l'employeur,

                    et {{employee.name}}, né(e) le {{employee.birth_date}}, de nationalité {{employee.nationality}}, ci-après le travailleur,

                    ## Article 1. Engagement
                    L'employeur engage le travailleur à compter du {{contract.start}} en qualité de {{job.title}}, grade {{job.grade}}, pour une durée indéterminée.

                    ## Article 2. Lieu de travail
                    Le travailleur exerce ses fonctions à {{site.name}}, au sein de {{unit.name}}.

                    ## Article 3. Rémunération
                    Le travailleur perçoit un salaire mensuel brut de {{salary.gross}}, auquel s'ajoutent les allocations et avantages prévus par la loi, la convention collective et le règlement de l'entreprise.

                    ## Article 4. Durée du travail et congés
                    La durée du travail et les congés suivent le Code du travail, la convention collective et le règlement intérieur, dont le travailleur reconnaît avoir reçu copie.

                    ## Article 5. Période d'essai
                    Le contrat commence par une période d'essai dans les limites prévues par le Code du travail.

                    ## Article 6. Dispositions finales
                    Pour tout ce qui n'est pas prévu au présent contrat, les parties s'en réfèrent au Code du travail et à la convention collective en vigueur.""", "CONTRACT"),
            new Letter("CONTRACT_CDD", "CONTRACT", "Fixed-term employment contract", "Contrat de travail à durée déterminée", """
                    Between {{company.name}}, {{company.address}}, hereafter the employer,

                    and {{employee.name}}, born on {{employee.birth_date}}, of {{employee.nationality}} nationality, hereafter the employee,

                    ## 1. Engagement
                    The employer engages the employee as {{job.title}}, grade {{job.grade}}, from {{contract.start}} to {{contract.end}}.

                    ## 2. Place of work
                    The employee works at {{site.name}}, in the unit {{unit.name}}.

                    ## 3. Pay
                    The employee receives a gross monthly salary of {{salary.gross}}, plus the allowances provided by law and the collective agreement.

                    ## 4. End of the contract
                    The contract ends on {{contract.end}} without notice. It can be renewed within the limits of the Labour Code.

                    ## 5. Other terms
                    Anything this contract does not settle is governed by the Labour Code and the collective agreement in force.""", """
                    Entre {{company.name}}, {{company.address}}, ci-après l'employeur,

                    et {{employee.name}}, né(e) le {{employee.birth_date}}, de nationalité {{employee.nationality}}, ci-après le travailleur,

                    ## Article 1. Engagement
                    L'employeur engage le travailleur en qualité de {{job.title}}, grade {{job.grade}}, du {{contract.start}} au {{contract.end}}.

                    ## Article 2. Lieu de travail
                    Le travailleur exerce ses fonctions à {{site.name}}, au sein de {{unit.name}}.

                    ## Article 3. Rémunération
                    Le travailleur perçoit un salaire mensuel brut de {{salary.gross}}, auquel s'ajoutent les allocations prévues par la loi et la convention collective.

                    ## Article 4. Fin du contrat
                    Le contrat prend fin le {{contract.end}} sans préavis. Il peut être renouvelé dans les limites prévues par le Code du travail.

                    ## Article 5. Dispositions finales
                    Pour tout ce qui n'est pas prévu au présent contrat, les parties s'en réfèrent au Code du travail et à la convention collective en vigueur.""", "CONTRACT"),
            new Letter("CONFIRMATION", "LETTER", "Confirmation after the trial period", "Confirmation à l'issue de la période d'essai", """
                    Dear {{employee.name}},

                    We are pleased to confirm that you have completed your trial period as {{job.title}}. Your engagement is confirmed under the terms of your contract.

                    We thank you for your work and wish you every success in your duties.""", """
                    Madame, Monsieur {{employee.name}},

                    Nous avons le plaisir de vous confirmer que vous avez satisfait à votre période d'essai en qualité de {{job.title}}. Votre engagement est confirmé aux conditions de votre contrat.

                    Nous vous remercions pour votre travail et vous souhaitons plein succès dans vos fonctions.""", "LETTER"),
            new Letter("PROMOTION", "LETTER", "Promotion letter", "Lettre de promotion", """
                    Dear {{employee.name}},

                    We are pleased to inform you that you are promoted to {{job.title}}, grade {{job.grade}}, from {{contract.start}}. Your gross monthly salary becomes {{salary.gross}}.

                    The other terms of your contract stay unchanged. Please sign this letter to confirm your agreement.""", """
                    Madame, Monsieur {{employee.name}},

                    Nous avons le plaisir de vous informer de votre promotion au poste de {{job.title}}, grade {{job.grade}}, à compter du {{contract.start}}. Votre salaire mensuel brut est porté à {{salary.gross}}.

                    Les autres conditions de votre contrat restent inchangées. Merci de signer la présente pour accord.""", "LETTER"),
            new Letter("WARNING", "LETTER", "Written warning", "Avertissement écrit", """
                    Dear {{employee.name}},

                    We note the following facts: [describe the facts, the date and the rule not followed].

                    These facts go against your duties. This letter is a written warning, kept in your file. Should they happen again, a heavier sanction may follow.

                    You may answer in writing within the time the company's rules allow. Your signature confirms that you received this letter, not that you agree with it.""", """
                    Madame, Monsieur {{employee.name}},

                    Nous avons constaté les faits suivants : [décrire les faits, la date et la règle non respectée].

                    Ces faits sont contraires à vos obligations. La présente constitue un avertissement écrit versé à votre dossier. En cas de récidive, une sanction plus lourde pourra être prise.

                    Vous pouvez y répondre par écrit dans le délai prévu par le règlement intérieur. Votre signature atteste la réception de la présente, non votre accord.""", "LETTER"));

    private final JdbcClient jdbc;
    private final Tenants tenants;

    public LifecycleDefaults(JdbcClient jdbc, Tenants tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        List<Long> companies = tenants.callAsPlatform(() -> jdbc.sql("select id from company where not lifecycle_seeded")
                .query(Long.class).list());
        for (Long id : companies) {
            try {
                tenants.runAs(id, this::seed);
            } catch (RuntimeException e) {
                log.error("Starting documents and checklists for company {} could not be made", id, e);
            }
        }
    }

    /** Fills the current company once; does nothing when it was done before. */
    @Transactional
    public void seed() {
        boolean done = jdbc.sql("select lifecycle_seeded from company where id = current_company_id()").query(Boolean.class)
                .optional().orElse(true);
        if (done) {
            return;
        }
        boolean fr = "fr".equals(jdbc.sql("select language from company where id = current_company_id()").query(String.class)
                .optional().orElse("fr"));
        int position = 0;
        for (DocType t : TYPES) {
            jdbc.sql("""
                    insert into document_type (code, name, has_expiry, employee_can_upload, position) values (?, ?, ?, ?, ?)
                    on conflict do nothing""").params(t.code(), fr ? t.fr() : t.en(), t.expiry(), t.employeeUploads(), position += 10).update();
        }
        position = 0;
        for (Step s : STEPS) {
            Long type = s.documentType() == null ? null
                    : jdbc.sql("select id from document_type where code = ?").param(s.documentType()).query(Long.class).optional().orElse(null);
            jdbc.sql("insert into checklist_template_item (kind, position, label, document_type_id, action) values (?, ?, ?, ?, ?)")
                    .params(s.kind(), position += 10, fr ? s.fr() : s.en(), type, s.documentType() != null && type == null ? null : s.action())
                    .update();
        }
        for (Letter l : LETTERS) {
            Long type = jdbc.sql("select id from document_type where code = ?").param(l.documentType()).query(Long.class).optional().orElse(null);
            jdbc.sql("""
                    insert into letter_template (code, name, kind, body, requires_signature, document_type_id, updated_by)
                    values (?, ?, ?, ?, true, ?, 'system') on conflict do nothing""")
                    .params(l.code(), fr ? l.fr() : l.en(), l.kind(), (fr ? l.bodyFr() : l.bodyEn()).strip(), type).update();
        }
        jdbc.sql("update company set lifecycle_seeded = true where id = current_company_id()").update();
    }
}
