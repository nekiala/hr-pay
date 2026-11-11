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

                    Vous pouvez y répondre par écrit dans le délai prévu par le règlement intérieur. Votre signature atteste la réception de la présente, non votre accord.""", "LETTER"),
            new Letter("DISCIPLINE_HEARING", "LETTER", "Invitation to a disciplinary hearing", "Convocation à un entretien disciplinaire", """
                    Dear {{employee.name}},

                    The following facts have been reported to us (case {{discipline.number}}), which may lead to a disciplinary sanction:

                    {{discipline.facts}}

                    Before any decision, so that you can explain yourself and present your defence (Labour Code art. 62), we invite you to a hearing on {{discipline.hearing_date}}, at {{discipline.hearing_place}}.

                    You may come with a member of the staff of your choice. If you cannot attend, tell us beforehand; you may also answer in writing before the hearing.

                    Your signature confirms that you received this letter.""", """
                    Madame, Monsieur {{employee.name}},

                    Les faits suivants nous ont été signalés (dossier {{discipline.number}}) et sont susceptibles d'entraîner une sanction disciplinaire :

                    {{discipline.facts}}

                    Avant toute décision, et afin de vous permettre de vous expliquer et de présenter votre défense (Code du travail, art. 62), nous vous convoquons à un entretien le {{discipline.hearing_date}}, à {{discipline.hearing_place}}.

                    Vous pouvez vous faire accompagner d'un membre du personnel de votre choix. En cas d'empêchement, prévenez-nous à l'avance ; vous pouvez aussi répondre par écrit avant l'entretien.

                    Votre signature atteste la réception de la présente.""", "LETTER"),
            new Letter("DISCIPLINE_WARNING", "LETTER", "Disciplinary warning", "Avertissement disciplinaire", """
                    Dear {{employee.name}},

                    On {{discipline.facts_date}}, the following facts were noted (case {{discipline.number}}):

                    {{discipline.facts}}

                    You were heard on {{discipline.heard_on}}. Having considered your explanations, and in accordance with our company rules, we impose on you the following sanction: a written warning. This letter is kept in your file. Should such facts happen again, a heavier sanction may follow.

                    You may contest this sanction in writing. Your signature confirms that you received this letter, not that you agree with it.""", """
                    Madame, Monsieur {{employee.name}},

                    Le {{discipline.facts_date}}, les faits suivants ont été constatés (dossier {{discipline.number}}) :

                    {{discipline.facts}}

                    Vous avez été entendu(e) le {{discipline.heard_on}}. Après examen de vos explications, et conformément au règlement d'entreprise, nous vous infligeons la sanction suivante : un avertissement écrit. La présente est versée à votre dossier. En cas de récidive, une sanction plus lourde pourra être prise.

                    Vous pouvez contester cette sanction par écrit. Votre signature atteste la réception de la présente, non votre accord.""", "LETTER"),
            new Letter("DISCIPLINE_REPRIMAND", "LETTER", "Reprimand", "Réprimande", """
                    Dear {{employee.name}},

                    On {{discipline.facts_date}}, the following facts were noted (case {{discipline.number}}):

                    {{discipline.facts}}

                    You were heard on {{discipline.heard_on}}. Having considered your explanations, and in accordance with our company rules, we impose on you the following sanction: a reprimand. This letter is kept in your file. Should such facts happen again, a heavier sanction may follow.

                    You may contest this sanction in writing. Your signature confirms that you received this letter, not that you agree with it.""", """
                    Madame, Monsieur {{employee.name}},

                    Le {{discipline.facts_date}}, les faits suivants ont été constatés (dossier {{discipline.number}}) :

                    {{discipline.facts}}

                    Vous avez été entendu(e) le {{discipline.heard_on}}. Après examen de vos explications, et conformément au règlement d'entreprise, nous vous infligeons la sanction suivante : une réprimande. La présente est versée à votre dossier. En cas de récidive, une sanction plus lourde pourra être prise.

                    Vous pouvez contester cette sanction par écrit. Votre signature atteste la réception de la présente, non votre accord.""", "LETTER"),
            new Letter("DISCIPLINE_BLAME", "LETTER", "Blame", "Blâme", """
                    Dear {{employee.name}},

                    On {{discipline.facts_date}}, the following facts were noted (case {{discipline.number}}):

                    {{discipline.facts}}

                    You were heard on {{discipline.heard_on}}. Having considered your explanations, and in accordance with our company rules, we impose on you the following sanction: a blame. This letter is kept in your file. Should such facts happen again, a heavier sanction may follow.

                    You may contest this sanction in writing. Your signature confirms that you received this letter, not that you agree with it.""", """
                    Madame, Monsieur {{employee.name}},

                    Le {{discipline.facts_date}}, les faits suivants ont été constatés (dossier {{discipline.number}}) :

                    {{discipline.facts}}

                    Vous avez été entendu(e) le {{discipline.heard_on}}. Après examen de vos explications, et conformément au règlement d'entreprise, nous vous infligeons la sanction suivante : un blâme. La présente est versée à votre dossier. En cas de récidive, une sanction plus lourde pourra être prise.

                    Vous pouvez contester cette sanction par écrit. Votre signature atteste la réception de la présente, non votre accord.""", "LETTER"),
            new Letter("DISCIPLINE_SUSPENSION", "LETTER", "Disciplinary suspension (mise à pied)", "Mise à pied disciplinaire", """
                    Dear {{employee.name}},

                    On {{discipline.facts_date}}, the following facts were noted (case {{discipline.number}}):

                    {{discipline.facts}}

                    You were heard on {{discipline.heard_on}}. Having considered your explanations, and as our company rules provide (Labour Code art. 57), we impose on you a disciplinary suspension (mise à pied) of {{discipline.suspension_days}} days, from {{discipline.suspension_from}} to {{discipline.suspension_to}} inclusive. During these days your contract is suspended: you do not come to work and these days are not paid. You resume work on {{discipline.return_date}}.

                    You may contest this sanction in writing. Your signature confirms that you received this letter, not that you agree with it.""", """
                    Madame, Monsieur {{employee.name}},

                    Le {{discipline.facts_date}}, les faits suivants ont été constatés (dossier {{discipline.number}}) :

                    {{discipline.facts}}

                    Vous avez été entendu(e) le {{discipline.heard_on}}. Après examen de vos explications, et comme le prévoit le règlement d'entreprise (Code du travail, art. 57), nous vous infligeons une mise à pied disciplinaire de {{discipline.suspension_days}} jours, du {{discipline.suspension_from}} au {{discipline.suspension_to}} inclus. Pendant ces jours, votre contrat est suspendu : vous ne vous présentez pas au travail et ces jours ne sont pas rémunérés. Vous reprendrez le travail le {{discipline.return_date}}.

                    Vous pouvez contester cette sanction par écrit. Votre signature atteste la réception de la présente, non votre accord.""", "LETTER"),
            new Letter("DISCIPLINE_DISMISSAL", "LETTER", "Dismissal for serious misconduct", "Licenciement pour faute lourde", """
                    Dear {{employee.name}},

                    On {{discipline.facts_date}}, the following facts were noted (case {{discipline.number}}):

                    {{discipline.facts}}

                    You were heard on {{discipline.heard_on}}. Having considered your explanations, we hold that these facts are serious misconduct (faute lourde, Labour Code art. 72). We therefore end your contract without notice, from {{discipline.decided_on}}.

                    Your final pay, your work certificate and your final documents will be given to you. You may contest this decision in writing. Your signature confirms that you received this letter, not that you agree with it.""", """
                    Madame, Monsieur {{employee.name}},

                    Le {{discipline.facts_date}}, les faits suivants ont été constatés (dossier {{discipline.number}}) :

                    {{discipline.facts}}

                    Vous avez été entendu(e) le {{discipline.heard_on}}. Après examen de vos explications, nous considérons que ces faits constituent une faute lourde (Code du travail, art. 72). Nous mettons donc fin à votre contrat sans préavis, à compter du {{discipline.decided_on}}.

                    Votre décompte final, votre certificat de travail et vos documents de fin de contrat vous seront remis. Vous pouvez contester cette décision par écrit. Votre signature atteste la réception de la présente, non votre accord.""", "LETTER"),
            new Letter("CAREER_PROMOTION", "LETTER", "Amendment to the employment contract: promotion", "Avenant au contrat de travail : promotion", """
                    Between {{company.name}}, represented by {{company.representative}}, {{company.representative_title}}, and {{employee.civility}} {{employee.name}}, registration no. {{employee.registration_no}}, the following is agreed as an amendment to the employment contract in force.

                    Article 1. From {{career.effective_on}}, {{employee.civility}} {{employee.name}} is promoted:
                    {{career.change}}

                    Article 2. The basic salary is that of the grade: {{career.new_basic}}. {{career.pay_note}}

                    Article 3. {{career.trial}}

                    Article 4. {{career.housing}} The seniority acquired is kept, and all the other clauses of the employment contract remain unchanged.

                    Done at {{contract.signed_place}}, on {{today}}, in two copies. The employee's signature confirms the agreement to this amendment.""", """
                    Entre {{company.name}}, représentée par {{company.representative}}, {{company.representative_title}}, et {{employee.civility}} {{employee.name}}, matricule {{employee.registration_no}}, il est convenu ce qui suit, en avenant au contrat de travail en cours.

                    Article 1. À compter du {{career.effective_on}}, {{employee.civility}} {{employee.name}} est promu(e) :
                    {{career.change}}

                    Article 2. Le salaire de base est celui du grade : {{career.new_basic}}. {{career.pay_note}}

                    Article 3. {{career.trial}}

                    Article 4. {{career.housing}} L'ancienneté acquise est conservée et toutes les autres clauses du contrat de travail restent inchangées.

                    Fait à {{contract.signed_place}}, le {{today}}, en deux exemplaires. La signature du travailleur atteste son accord avec le présent avenant.""", "CONTRACT"),
            new Letter("CAREER_TRANSFER", "LETTER", "Transfer note", "Note de mutation", """
                    Dear {{employee.name}}, registration no. {{employee.registration_no}},

                    We inform you that from {{career.effective_on}} you are transferred:
                    {{career.change}}

                    {{career.housing}}

                    Your grade, your salary, your seniority and the other clauses of your employment contract do not change.

                    Your signature confirms that you agree to this transfer.""", """
                    Madame, Monsieur {{employee.name}}, matricule {{employee.registration_no}},

                    Nous vous informons qu'à compter du {{career.effective_on}}, vous êtes muté(e) :
                    {{career.change}}

                    {{career.housing}}

                    Votre grade, votre salaire, votre ancienneté et les autres clauses de votre contrat de travail restent inchangés.

                    Votre signature atteste votre accord avec cette mutation.""", "CONTRACT"));

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
        List<Long> all = tenants.callAsPlatform(() -> jdbc.sql("select id from company where lifecycle_seeded").query(Long.class).list());
        for (Long id : all) {
            try {
                tenants.runAs(id, this::addContractModels);
                tenants.runAs(id, this::addDisciplineLetters);
                tenants.runAs(id, this::addCareerLetters);
            } catch (RuntimeException e) {
                log.error("The contract models for company {} could not be added", id, e);
            }
        }
    }

    /** The full contract models (from NTOPROG's model, 32 articles, with an annex), once per company. */
    public static final List<String> FULL_MODELS = List.of("CONTRACT_CDD_FULL", "CONTRACT_CDI_FULL");

    /**
     * Adds the full CDD and CDI models when the company does not have them; the short samples nobody changed are
     * then put aside (still there, inactive).
     */
    @Transactional
    public void addContractModels() {
        boolean fr = "fr".equals(jdbc.sql("select language from company where id = current_company_id()").query(String.class)
                .optional().orElse("fr"));
        Long type = jdbc.sql("select id from document_type where code = 'CONTRACT'").query(Long.class).optional().orElse(null);
        int added = 0;
        for (String code : FULL_MODELS) {
            boolean cdd = code.contains("CDD");
            String name = fr ? (cdd ? "Contrat de travail à durée déterminée (CDD)" : "Contrat de travail à durée indéterminée (CDI)")
                    : (cdd ? "Fixed-term employment contract (CDD)" : "Permanent employment contract (CDI)");
            String body = model((cdd ? "contract-cdd." : "contract-cdi.") + (fr ? "fr" : "en") + ".txt");
            added += jdbc.sql("""
                    insert into letter_template (code, name, kind, body, requires_signature, document_type_id, updated_by)
                    values (?, ?, 'CONTRACT', ?, true, ?, 'system') on conflict do nothing""")
                    .params(code, name, body, type).update();
        }
        if (added > 0) {
            jdbc.sql("update letter_template set active = false where code in ('CONTRACT_CDI', 'CONTRACT_CDD') and updated_by = 'system'")
                    .update();
        }
    }

    private static final String DISCIPLINE_LETTERS_ADDED = "DISCIPLINE_LETTERS_ADDED";

    /** The discipline register's letters, once per company that was set up before the register existed. */
    @Transactional
    public void addDisciplineLetters() {
        addLetters("DISCIPLINE_", DISCIPLINE_LETTERS_ADDED);
    }

    private static final String CAREER_LETTERS_ADDED = "CAREER_LETTERS_ADDED";

    /** The avenant de promotion and the note de mutation, once per company that was set up before them. */
    @Transactional
    public void addCareerLetters() {
        addLetters("CAREER_", CAREER_LETTERS_ADDED);
    }

    private void addLetters(String prefix, String flag) {
        if (jdbc.sql("select count(*) from payroll_setting where key = ?").param(flag)
                .query(Integer.class).single() > 0) {
            return;
        }
        boolean fr = "fr".equals(jdbc.sql("select language from company where id = current_company_id()").query(String.class)
                .optional().orElse("fr"));
        for (Letter l : LETTERS) {
            if (l.code().startsWith(prefix)) {
                Long type = jdbc.sql("select id from document_type where code = ?").param(l.documentType()).query(Long.class)
                        .optional().orElse(null);
                jdbc.sql("""
                        insert into letter_template (code, name, kind, body, requires_signature, document_type_id, updated_by)
                        values (?, ?, ?, ?, true, ?, 'system') on conflict do nothing""")
                        .params(l.code(), fr ? l.fr() : l.en(), l.kind(), (fr ? l.bodyFr() : l.bodyEn()).strip(), type).update();
            }
        }
        jdbc.sql("insert into payroll_setting (key, value) values (?, 'true') on conflict do nothing")
                .param(flag).update();
    }

    static String model(String file) {
        try (var in = LifecycleDefaults.class.getResourceAsStream("/lifecycle/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing contract model " + file);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Contract model " + file + " cannot be read", e);
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
        addContractModels();
        addDisciplineLetters();
        addCareerLetters();
        jdbc.sql("update company set lifecycle_seeded = true where id = current_company_id()").update();
    }
}
