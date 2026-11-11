package cd.hrpay.position;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting job descriptions for the sample occupations, with families, grade ranges and a short ladder, once per
 * company and only for jobs nobody has described yet. HR rewrites them to fit the company.
 */
@Component
public class JobDefaults {

    static final String FLAG = "JOB_SHEETS_ADDED";

    private record Family(String code, String en, String fr) {}

    private record Sheet(String code, String family, String minGrade, String maxGrade, String reportsTo,
                         String purposeEn, String dutiesEn, String educationEn, String experienceEn, String skillsEn,
                         String purposeFr, String dutiesFr, String educationFr, String experienceFr, String skillsFr) {}

    private record Step(String from, String to, double years, double score) {}

    private static final List<Family> FAMILIES = List.of(
            new Family("FIN", "Finance", "Finances"), new Family("HR", "Human resources", "Ressources humaines"),
            new Family("LOG", "Logistics", "Logistique"), new Family("TECH", "Technical", "Technique"),
            new Family("ADM", "Administration", "Administration"), new Family("MGT", "Management", "Direction"));

    private static final List<Sheet> SHEETS = List.of(
            new Sheet("MANAGER", "MGT", "C3", "D1", null,
                    "Leads a unit so that it meets its objectives within its budget.",
                    "Sets the unit's objectives and follows them up\nPlans the work and shares it in the team\nEvaluates the team and proposes promotions and training\nManages the unit's budget\nReports to management every month",
                    "University degree (licence or master)", "5 years, of which 2 leading a team", "Leadership\nPlanning\nBudget follow-up",
                    "Dirige une unité pour qu'elle atteigne ses objectifs dans son budget.",
                    "Fixe les objectifs de l'unité et en assure le suivi\nPlanifie le travail et le répartit dans l'équipe\nÉvalue l'équipe et propose les promotions et formations\nGère le budget de l'unité\nRend compte chaque mois à la direction",
                    "Diplôme universitaire (licence ou master)", "5 ans, dont 2 à la tête d'une équipe", "Leadership\nPlanification\nSuivi budgétaire"),
            new Sheet("ACCOUNTANT", "FIN", "M3", "C2", "MANAGER",
                    "Keeps the company's accounts accurate and on time (OHADA chart of accounts).",
                    "Records the daily operations\nReconciles bank and cash accounts every month\nPrepares the tax and social declarations\nPrepares the monthly and yearly closing",
                    "Degree in accounting or finance", "2 years in accounting", "OHADA accounting\nSpreadsheets\nRigour and confidentiality",
                    "Tient les comptes de l'entreprise exacts et à jour (plan comptable OHADA).",
                    "Enregistre les opérations quotidiennes\nRapproche chaque mois les comptes de banque et de caisse\nPrépare les déclarations fiscales et sociales\nPrépare les clôtures mensuelles et annuelles",
                    "Diplôme en comptabilité ou finances", "2 ans en comptabilité", "Comptabilité OHADA\nTableur\nRigueur et confidentialité"),
            new Sheet("HR_OFFICER", "HR", "M2", "C1", "MANAGER",
                    "Runs the daily personnel administration within the Labour Code.",
                    "Keeps the employee files and contracts up to date\nFollows attendance, leave and absences\nPrepares the payroll inputs\nFollows trials, contract ends and medical visits",
                    "Degree in law, management or HR", "2 years in HR administration", "Labour Code\nPayroll basics\nDiscretion",
                    "Assure la gestion administrative quotidienne du personnel dans le respect du Code du travail.",
                    "Tient à jour les dossiers du personnel et les contrats\nSuit les présences, congés et absences\nPrépare les éléments de paie\nSuit les essais, fins de contrat et visites médicales",
                    "Diplôme en droit, gestion ou RH", "2 ans en administration du personnel", "Code du travail\nNotions de paie\nDiscrétion"),
            new Sheet("ENGINEER", "TECH", "C1", "C4", "MANAGER",
                    "Designs, runs and improves the company's technical installations.",
                    "Plans and follows the technical works\nChecks safety and quality on site\nWrites the technical reports\nSupervises the technicians",
                    "Engineering degree", "3 years", "Project follow-up\nSafety rules\nReport writing",
                    "Conçoit, exploite et améliore les installations techniques de l'entreprise.",
                    "Planifie et suit les travaux techniques\nContrôle la sécurité et la qualité sur site\nRédige les rapports techniques\nEncadre les techniciens",
                    "Diplôme d'ingénieur", "3 ans", "Suivi de projet\nRègles de sécurité\nRédaction de rapports"),
            new Sheet("DRIVER", "LOG", "E3A", "E4B", "MANAGER",
                    "Drives the company's vehicles safely and keeps them in good order.",
                    "Drives staff and goods on the planned trips\nChecks the vehicle before each trip\nKeeps the logbook and fuel records\nReports any accident or breakdown at once",
                    "Driving licence for the vehicle class", "2 years of driving", "Road safety\nBasic mechanics\nPunctuality",
                    "Conduit les véhicules de l'entreprise en sécurité et les garde en bon état.",
                    "Conduit le personnel et les marchandises sur les trajets prévus\nVérifie le véhicule avant chaque départ\nTient le carnet de bord et le suivi du carburant\nSignale aussitôt tout accident ou panne",
                    "Permis de conduire de la catégorie du véhicule", "2 ans de conduite", "Sécurité routière\nMécanique de base\nPonctualité"),
            new Sheet("RECEPTIONIST", "ADM", "E3B", "E5", "MANAGER",
                    "Welcomes visitors and callers and keeps the front office running.",
                    "Welcomes and directs visitors\nAnswers and routes calls\nKeeps the visitor register and the mail\nBooks meeting rooms",
                    "State diploma (diplôme d'État)", "1 year", "Welcome and courtesy\nFrench and English\nOffice software",
                    "Accueille les visiteurs et les appels et assure le bon fonctionnement de l'accueil.",
                    "Accueille et oriente les visiteurs\nRépond aux appels et les transfère\nTient le registre des visiteurs et le courrier\nRéserve les salles de réunion",
                    "Diplôme d'État", "1 an", "Accueil et courtoisie\nFrançais et anglais\nBureautique"));

    private static final List<Step> LADDER = List.of(
            new Step("RECEPTIONIST", "HR_OFFICER", 3, 70), new Step("HR_OFFICER", "MANAGER", 5, 75),
            new Step("ACCOUNTANT", "MANAGER", 5, 75), new Step("ENGINEER", "MANAGER", 4, 75));

    private final JdbcClient jdbc;

    public JobDefaults(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void addSampleJobs() {
        if (jdbc.sql("select count(*) from payroll_setting where key = ?").param(FLAG).query(Integer.class).single() > 0) {
            return;
        }
        boolean fr = "fr".equals(jdbc.sql("select language from company where id = current_company_id()").query(String.class)
                .optional().orElse("fr"));
        for (Family f : FAMILIES) {
            jdbc.sql("insert into job_family (code, name) values (?, ?) on conflict do nothing").params(f.code(), fr ? f.fr() : f.en())
                    .update();
        }
        for (Sheet s : SHEETS) {
            jdbc.sql("""
                    update occupation set family_id = (select id from job_family where upper(code) = ?),
                           purpose = ?, duties = ?, education = ?, experience = ?, skills = ?,
                           min_grade_id = (select id from grade where code = ? and active limit 1),
                           max_grade_id = (select id from grade where code = ? and active limit 1),
                           reports_to_id = (select id from occupation where code = ? limit 1),
                           version = 1, updated_by = 'system', updated_at = now()
                    where code = ? and version = 0""")
                    .params(s.family(), fr ? s.purposeFr() : s.purposeEn(), fr ? s.dutiesFr() : s.dutiesEn(),
                            fr ? s.educationFr() : s.educationEn(), fr ? s.experienceFr() : s.experienceEn(),
                            fr ? s.skillsFr() : s.skillsEn(), s.minGrade(), s.maxGrade(), s.reportsTo(), s.code())
                    .update();
            jdbc.sql("update occupation set reports_to_id = null where code = ? and reports_to_id = id").param(s.code()).update();
        }
        jdbc.sql("""
                insert into occupation_version (occupation_id, version, name, purpose, duties, education, experience, skills,
                                                conditions, min_grade, max_grade, unskilled, saved_by)
                select o.id, o.version, o.name, o.purpose, o.duties, o.education, o.experience, o.skills, o.conditions,
                       mn.code || ' · ' || mn.name, mx.code || ' · ' || mx.name, o.unskilled, 'system'
                from occupation o left join grade mn on mn.id = o.min_grade_id left join grade mx on mx.id = o.max_grade_id
                where o.version = 1 and o.updated_by = 'system'
                on conflict do nothing""").update();
        for (Step st : LADDER) {
            jdbc.sql("""
                    insert into career_path (from_occupation_id, to_occupation_id, min_years, min_score)
                    select f.id, t.id, ?, ? from occupation f, occupation t where f.code = ? and t.code = ?
                    on conflict do nothing""").params(st.years(), st.score(), st.from(), st.to()).update();
        }
        jdbc.sql("insert into payroll_setting (key, value) values (?, 'true') on conflict do nothing").param(FLAG).update();
    }
}
