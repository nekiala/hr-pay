package cd.hrpay.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.i18n.LocaleContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The app's texts in French. The English text is its own key: {@code Texts.t("Choose a month.")} gives the French
 * when the current language is French, the English otherwise, and a text missing from the catalogue stays English.
 * Templates take {@link String#format} arguments ({@code %s}, {@code %d}); a message already built from one (an
 * error raised deep in a service) is matched back to its template and translated with the same values.
 *
 * <p>The language is the request's (the app sends the person's language in {@code Accept-Language}); documents and
 * emails set theirs with {@link #in(String, Supplier)}.
 */
public final class Texts {

    public static final String FR = "fr";
    public static final String EN = "en";

    private static final Pattern ARG = Pattern.compile("(?<!%)%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdf]");  // before the templates it builds
    private static final Map<String, String> FRENCH = load();
    private static final List<Template> TEMPLATES = templates();
    private static final ThreadLocal<String> FORCED = new ThreadLocal<>();

    private record Template(Pattern english, String french, List<Integer> order) {}

    private Texts() {}

    /** The language now in effect: a document's or email's when one is being made, else the request's. */
    public static String current() {
        String forced = FORCED.get();
        if (forced != null) {
            return forced;
        }
        Locale l = LocaleContextHolder.getLocale();
        return l != null && FR.equals(l.getLanguage()) ? FR : EN;
    }

    public static boolean french() {
        return FR.equals(current());
    }

    /** Runs work (a PDF, an email) in the given language. */
    public static <T> T in(String language, Supplier<T> work) {
        String before = FORCED.get();
        FORCED.set(FR.equals(language) ? FR : EN);
        try {
            return work.get();
        } finally {
            if (before == null) {
                FORCED.remove();
            } else {
                FORCED.set(before);
            }
        }
    }

    public static void run(String language, Runnable work) {
        in(language, () -> {
            work.run();
            return null;
        });
    }

    /** The text in the current language, formatted with the arguments. */
    public static String t(String english, Object... args) {
        String template = french() ? FRENCH.getOrDefault(english, english) : english;
        return args.length == 0 ? template : String.format(french() ? Locale.FRANCE : Locale.UK, template, args);
    }

    /** The locale for dates and numbers in the current language. */
    public static Locale locale() {
        return french() ? Locale.FRANCE : Locale.UK;
    }

    /** A date written out, such as 7 novembre 2026 or 7 November 2026. */
    public static String day(java.time.LocalDate date) {
        return date == null ? "" : date.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", locale()));
    }

    /** A month and year, such as novembre 2026 or November 2026. */
    public static String month(java.time.temporal.TemporalAccessor month) {
        return java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", locale()).format(month);
    }

    /** A message already built in English, in the current language: matched whole, then against the templates. */
    public static String translate(String text) {
        if (text == null || !french()) {
            return text;
        }
        String exact = FRENCH.get(text);
        if (exact != null) {
            return exact;
        }
        for (Template t : TEMPLATES) {
            Matcher m = t.english().matcher(text);
            if (m.matches()) {
                Object[] values = new Object[m.groupCount()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = m.group(t.order().get(i) + 1);
                }
                return String.format(Locale.FRANCE, t.french(), values);
            }
        }
        return text;
    }

    /** French entries for tests: how many, and whether one is there. */
    static Map<String, String> catalogue() {
        return FRENCH;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> load() {
        try (InputStream in = Texts.class.getResourceAsStream("/i18n/fr.json")) {
            if (in == null) {
                return Map.of();
            }
            return new LinkedHashMap<>(JsonMapper.builder().build().readValue(in, Map.class));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the French texts", e);
        }
    }

    /** Templates with arguments, as patterns matching a message built from them, longest first. */
    private static List<Template> templates() {
        List<Template> out = new ArrayList<>();
        for (Map.Entry<String, String> e : FRENCH.entrySet()) {
            String english = e.getKey();
            Matcher m = ARG.matcher(english);
            StringBuilder regex = new StringBuilder();
            int last = 0;
            int n = 0;
            while (m.find()) {
                regex.append(Pattern.quote(english.substring(last, m.start()).replace("%%", "%"))).append("(.+?)");
                last = m.end();
                n++;
            }
            if (n == 0) {
                continue;
            }
            regex.append(Pattern.quote(english.substring(last).replace("%%", "%")));
            // The French uses plain %s in the same order as the English.
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            out.add(new Template(Pattern.compile(regex.toString(), Pattern.DOTALL),
                    ARG.matcher(e.getValue()).replaceAll(r -> r.group(1) == null ? "%s" : "%" + r.group(1) + "s"), order));
        }
        out.sort((a, b) -> b.english().pattern().length() - a.english().pattern().length());
        return out;
    }
}
