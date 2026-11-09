package cd.hrpay.lifecycle;

/** Whole numbers written in words, as contracts state durations: "douze (12) mois", "twelve (12) months". */
final class NumberWords {

    private static final String[] FR = {"zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
            "onze", "douze", "treize", "quatorze", "quinze", "seize"};
    private static final String[] FR_TENS = {"", "dix", "vingt", "trente", "quarante", "cinquante", "soixante"};
    private static final String[] EN = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] EN_TENS = {"", "ten", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    private NumberWords() {
    }

    static String of(long n, boolean french) {
        if (n < 0 || n >= 1_000_000) {
            return Long.toString(n);
        }
        return french ? fr(n) : en(n);
    }

    // ---- French (rectified spelling is not used: "vingt et un", "quatre-vingts", "cent", "mille") ----

    private static String fr(long n) {
        if (n < 1000) {
            return frBelowThousand((int) n, true);
        }
        int thousands = (int) (n / 1000);
        int rest = (int) (n % 1000);
        String head = thousands == 1 ? "mille" : frBelowThousand(thousands, false) + " mille";
        return rest == 0 ? head : head + " " + frBelowThousand(rest, true);
    }

    private static String frBelowThousand(int n, boolean last) {
        if (n < 100) {
            return frBelowHundred(n, last);
        }
        int hundreds = n / 100;
        int rest = n % 100;
        String head = hundreds == 1 ? "cent" : FR[hundreds] + (rest == 0 && last ? " cents" : " cent");
        return rest == 0 ? head : head + " " + frBelowHundred(rest, true);
    }

    private static String frBelowHundred(int n, boolean last) {
        if (n <= 16) {
            return FR[n];
        }
        if (n < 20) {
            return "dix-" + FR[n - 10];
        }
        if (n < 70) {
            int tens = n / 10;
            int unit = n % 10;
            if (unit == 0) {
                return FR_TENS[tens];
            }
            return FR_TENS[tens] + (unit == 1 ? " et un" : "-" + FR[unit]);
        }
        if (n < 80) {
            return n == 71 ? "soixante et onze" : "soixante-" + frBelowHundred(n - 60, true);
        }
        if (n == 80) {
            return last ? "quatre-vingts" : "quatre-vingt";
        }
        return "quatre-vingt-" + frBelowHundred(n - 80, true);
    }

    // ---- English ----

    private static String en(long n) {
        if (n < 1000) {
            return enBelowThousand((int) n);
        }
        int thousands = (int) (n / 1000);
        int rest = (int) (n % 1000);
        String head = enBelowThousand(thousands) + " thousand";
        return rest == 0 ? head : head + " " + enBelowThousand(rest);
    }

    private static String enBelowThousand(int n) {
        if (n < 100) {
            return enBelowHundred(n);
        }
        int rest = n % 100;
        String head = EN[n / 100] + " hundred";
        return rest == 0 ? head : head + " and " + enBelowHundred(rest);
    }

    private static String enBelowHundred(int n) {
        if (n < 20) {
            return EN[n];
        }
        int unit = n % 10;
        return EN_TENS[n / 10] + (unit == 0 ? "" : "-" + EN[unit]);
    }
}
