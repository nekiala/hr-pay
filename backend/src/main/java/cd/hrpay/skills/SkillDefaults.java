package cd.hrpay.skills;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A starting skills catalogue and the skills the sample jobs need, once per company. HR renames, adds or puts aside
 * skills to fit the company.
 */
@Component
public class SkillDefaults {

    static final String FLAG = "SKILLS_ADDED";

    private record Sample(String family, String en, String fr, String descEn, String descFr) {}

    private record Need(String job, String skillEn, int level) {}

    private static final String[] LEVELS_EN = {
            "Knows the basics; needs help for the usual tasks.",
            "Does the usual tasks alone.",
            "Handles difficult cases and helps colleagues.",
            "The company's reference; trains others and improves the way of working."};
    private static final String[] LEVELS_FR = {
            "Connaît les bases ; a besoin d'aide pour les tâches courantes.",
            "Réalise seul les tâches courantes.",
            "Traite les cas difficiles et aide ses collègues.",
            "Référence de l'entreprise ; forme les autres et améliore les façons de faire."};

    private static final List<Sample> SAMPLES = List.of(
            new Sample("TECHNICAL", "OHADA accounting", "Comptabilité OHADA",
                    "Records and closes the accounts under the OHADA chart of accounts.", "Enregistre et clôture les comptes selon le plan comptable OHADA."),
            new Sample("TECHNICAL", "Payroll and labour law", "Paie et droit du travail",
                    "Applies the Labour Code and the payroll rules.", "Applique le Code du travail et les règles de paie."),
            new Sample("TECHNICAL", "Budget follow-up", "Suivi budgétaire",
                    "Prepares a budget and follows the spending against it.", "Prépare un budget et suit les dépenses par rapport à lui."),
            new Sample("TECHNICAL", "Project follow-up", "Suivi de projet",
                    "Plans works, follows deadlines and reports.", "Planifie les travaux, suit les délais et rend compte."),
            new Sample("TECHNICAL", "Electrical safety", "Sécurité électrique",
                    "Works on installations under the safety rules.", "Intervient sur les installations dans le respect des règles de sécurité."),
            new Sample("TECHNICAL", "Safe driving", "Conduite et sécurité routière",
                    "Drives safely and follows the highway code.", "Conduit en sécurité et respecte le code de la route."),
            new Sample("TECHNICAL", "Basic mechanics", "Mécanique de base",
                    "Checks a vehicle and fixes small faults.", "Contrôle un véhicule et répare les petites pannes."),
            new Sample("BEHAVIOURAL", "Leadership", "Leadership",
                    "Gives direction, shares the work and develops the team.", "Donne le cap, répartit le travail et fait grandir l'équipe."),
            new Sample("BEHAVIOURAL", "Welcome and courtesy", "Accueil et courtoisie",
                    "Welcomes visitors and callers well.", "Accueille bien les visiteurs et les appels."),
            new Sample("BEHAVIOURAL", "Rigour and confidentiality", "Rigueur et confidentialité",
                    "Works accurately and keeps information private.", "Travaille avec exactitude et garde les informations confidentielles."),
            new Sample("BEHAVIOURAL", "Teamwork", "Travail en équipe",
                    "Works with others towards the team's objectives.", "Travaille avec les autres vers les objectifs de l'équipe."),
            new Sample("LANGUAGE", "French", "Français", "Speaks and writes French at work.", "Parle et écrit le français au travail."),
            new Sample("LANGUAGE", "English", "Anglais", "Speaks and writes English at work.", "Parle et écrit l'anglais au travail."),
            new Sample("LANGUAGE", "Lingala", "Lingala", "Speaks Lingala with colleagues and clients.", "Parle le lingala avec les collègues et les clients."),
            new Sample("LANGUAGE", "Swahili", "Swahili", "Speaks Swahili with colleagues and clients.", "Parle le swahili avec les collègues et les clients."),
            new Sample("TOOL", "Spreadsheets", "Tableur", "Builds and checks tables and formulas.", "Construit et vérifie des tableaux et des formules."),
            new Sample("TOOL", "Office software", "Bureautique", "Writes letters and reports, uses e-mail.", "Rédige courriers et rapports, utilise la messagerie."));

    private static final List<Need> NEEDS = List.of(
            new Need("MANAGER", "Leadership", 3), new Need("MANAGER", "Budget follow-up", 3), new Need("MANAGER", "French", 4),
            new Need("MANAGER", "English", 2), new Need("MANAGER", "Spreadsheets", 2),
            new Need("ACCOUNTANT", "OHADA accounting", 3), new Need("ACCOUNTANT", "Spreadsheets", 3),
            new Need("ACCOUNTANT", "Rigour and confidentiality", 3), new Need("ACCOUNTANT", "French", 3),
            new Need("HR_OFFICER", "Payroll and labour law", 3), new Need("HR_OFFICER", "Rigour and confidentiality", 3),
            new Need("HR_OFFICER", "Office software", 2), new Need("HR_OFFICER", "French", 3),
            new Need("ENGINEER", "Project follow-up", 3), new Need("ENGINEER", "Electrical safety", 3),
            new Need("ENGINEER", "French", 3), new Need("ENGINEER", "English", 2),
            new Need("DRIVER", "Safe driving", 3), new Need("DRIVER", "Basic mechanics", 2), new Need("DRIVER", "French", 2),
            new Need("RECEPTIONIST", "Welcome and courtesy", 3), new Need("RECEPTIONIST", "French", 3),
            new Need("RECEPTIONIST", "English", 2), new Need("RECEPTIONIST", "Office software", 2));

    private final JdbcClient jdbc;

    public SkillDefaults(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void addSamples() {
        if (jdbc.sql("select count(*) from payroll_setting where key = ?").param(FLAG).query(Integer.class).single() > 0) {
            return;
        }
        boolean fr = "fr".equals(jdbc.sql("select language from company where id = current_company_id()").query(String.class)
                .optional().orElse("fr"));
        String[] levels = fr ? LEVELS_FR : LEVELS_EN;
        for (Sample s : SAMPLES) {
            jdbc.sql("""
                    insert into skill (family, name, description, level1, level2, level3, level4) values (?, ?, ?, ?, ?, ?, ?)
                    on conflict do nothing""")
                    .params(s.family(), fr ? s.fr() : s.en(), fr ? s.descFr() : s.descEn(), levels[0], levels[1], levels[2], levels[3])
                    .update();
        }
        for (Need n : NEEDS) {
            String name = SAMPLES.stream().filter(s -> s.en().equals(n.skillEn())).findFirst()
                    .map(s -> fr ? s.fr() : s.en()).orElseThrow();
            jdbc.sql("""
                    insert into occupation_skill (occupation_id, skill_id, level)
                    select o.id, k.id, ? from occupation o, skill k where o.code = ? and upper(k.name) = upper(?)
                    on conflict do nothing""").params(n.level(), n.job(), name).update();
        }
        jdbc.sql("insert into payroll_setting (key, value) values (?, 'true') on conflict do nothing").param(FLAG).update();
    }
}
