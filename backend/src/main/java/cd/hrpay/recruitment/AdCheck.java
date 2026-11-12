package cd.hrpay.recruitment;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words in a job ad that may exclude people on a protected ground: sex, age, marital status, religion, ethnicity,
 * nationality, pregnancy or disability (DRC AM 012/CAB.MIN/ETPS/062/08 art. 15; Code du travail art. 1, 62, 128, 135).
 * A flag is a warning for HR to read, not a refusal: "age" can be fine in "working age", a nationality can be required
 * by law for a reserved job.
 */
public final class AdCheck {

    private AdCheck() {}

    public record Flag(String ground, String words) {}

    private record Rule(String ground, Pattern pattern) {}

    private static Rule rule(String ground, String regex) {
        return new Rule(ground, Pattern.compile("(?<![\\p{L}])(" + regex + ")(?![\\p{L}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    // Accents are removed before matching, so the French words are written without them.
    private static final List<Rule> RULES = List.of(
            rule("SEX", "homme|hommes|femme|femmes|masculin|masculine|feminin|feminine|de sexe|male|female|man only|woman only|men only|women only|gentleman|lady|ladies"),
            rule("AGE", "age maximum|age minimum|age requis|age limite|ans au plus|ans au maximum|moins de \\d+ ans|plus de \\d+ ans|entre \\d+ et \\d+ ans|age \\d+|aged? \\d+|under \\d+|over \\d+|years old|jeune|jeunes|young|youthful"),
            rule("MARITAL", "celibataire|marie|mariee|sans enfant|sans enfants|single|married|unmarried|no children"),
            rule("PREGNANCY", "enceinte|grossesse|test de grossesse|pregnant|pregnancy"),
            rule("RELIGION", "chretien|chretienne|catholique|protestant|protestante|musulman|musulmane|kimbanguiste|religion|christian|muslim|catholic"),
            rule("ETHNICITY", "ethnie|tribu|tribal|originaire de|originaire du|race|ethnic|tribe"),
            rule("NATIONALITY", "nationalite|de nationalite|nationality|congolais uniquement|congolaise uniquement|nationals only"),
            rule("DISABILITY", "sans handicap|non handicape|valide physiquement|able-bodied|no disability"),
            rule("PHOTO", "photo|photograph|photographie"));

    /** The protected grounds found in a text, each with the words that triggered it. */
    public static List<Flag> check(String... texts) {
        StringBuilder all = new StringBuilder();
        for (String t : texts) {
            if (t != null) {
                all.append(t).append('\n');
            }
        }
        String plain = Normalizer.normalize(all.toString(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        // The equal-treatment sentence ("regardless of sex, age...") names the grounds to protect them, not to exclude.
        plain = plain.replaceAll("[^.\\n]*(sans distinction|regardless of|without regard to)[^.\\n]*", " ");
        List<Flag> out = new ArrayList<>();
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(plain);
            Set<String> found = new LinkedHashSet<>();
            while (m.find() && found.size() < 5) {
                found.add(m.group(1));
            }
            if (!found.isEmpty()) {
                out.add(new Flag(r.ground(), String.join(", ", found)));
            }
        }
        return out;
    }
}
