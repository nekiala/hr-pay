package cd.hrpay.payment;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The DRC CNSS forms of employers outside the guichet unique (arrêté 146/CAB/MINETAT/MTEPS/01/2018): the MOD. DC,
 * monthly declaration of the contributions paid, and the MOD. DCC, complementary declaration correcting a MOD. DC once
 * complementary payslips were approved after it. Filled from the month's payslips, in CDF at the payroll's rate, in
 * French as the forms are. The rates are the form's: pensions 10 %, occupational risks 1.5 %, family benefits 6.5 %.
 */
@Component
public class CnssForms {

    static final BigDecimal PENSIONS = new BigDecimal("10");
    static final BigDecimal RISKS = new BigDecimal("1.5");
    static final BigDecimal FAMILY = new BigDecimal("6.5");

    private static final Font TITLE = new Font(Font.HELVETICA, 12, Font.BOLD);
    private static final Font HEAD = new Font(Font.HELVETICA, 9.5f, Font.BOLD);
    private static final Font NORMAL = new Font(Font.HELVETICA, 9, Font.NORMAL);
    private static final Font BOLD = new Font(Font.HELVETICA, 9, Font.BOLD);
    private static final Font SMALL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL);
    private static final Map<String, String> MODES = Map.of("CASH", "espèces", "OV", "OV", "OP", "OP", "CHEQUE", "chèques");

    record Slip(long userId, String number, String kind, String currency, BigDecimal gross, BigDecimal cnssBase,
                OffsetDateTime approvedAt, Integer children) {}

    record Filing(LocalDate filedOn, OffsetDateTime filedAt, LocalDate paidOn, String paidMode) {}

    /** The figures of the form, in CDF. */
    public record Figures(BigDecimal gross, BigDecimal base, int workers, int children, BigDecimal pensions,
                          BigDecimal risks, BigDecimal family, BigDecimal total) {
        static Figures of(List<Slip> slips, BigDecimal rate, int workers, int children) {
            BigDecimal gross = slips.stream().map(s -> cdf(s.gross(), s.currency(), rate)).reduce(zero(), BigDecimal::add);
            BigDecimal base = slips.stream().map(s -> cdf(s.cnssBase(), s.currency(), rate)).reduce(zero(), BigDecimal::add);
            BigDecimal p = pct(base, PENSIONS);
            BigDecimal r = pct(base, RISKS);
            BigDecimal f = pct(base, FAMILY);
            return new Figures(gross, base, workers, children, p, r, f, p.add(r).add(f));
        }
    }

    private final JdbcClient jdbc;
    private final Company company;

    public CnssForms(JdbcClient jdbc, Company company) {
        this.jdbc = jdbc;
        this.company = company;
    }

    private record Run(long id, String status, BigDecimal usdCdf) {}

    private Run run(YearMonth month) {
        if (!"CD".equals(jdbc.sql("select country_code from company where id = current_company_id()").query(String.class).single())) {
            throw new BusinessRuleException("The MOD. DC and MOD. DCC forms are DR Congo CNSS forms.");
        }
        return jdbc.sql("select id, status, usd_cdf from payroll_run where month = ?").param(month.atDay(1))
                .query(Run.class).optional().orElseThrow(() -> new NotFoundException("No payroll for " + month + " yet."));
    }

    private List<Slip> slips(long runId) {
        return jdbc.sql("""
                select p.user_id, p.number, p.kind, trim(p.currency) as currency, p.gross, p.cnss_base, p.approved_at,
                       case when p.kind = 'REGULAR' then (p.snapshot ->> 'childrenInCharge')::int end as children
                from payslip p where p.run_id = ? and (p.kind = 'REGULAR' or p.number is not null)
                order by p.number nulls last, p.id""").param(runId).query(Slip.class).list();
    }

    private Filing filing(YearMonth month) {
        return jdbc.sql("select filed_on, filed_at, paid_on, paid_mode from declaration where month = ? and kind = 'CNSS'")
                .param(month.atDay(1)).query(Filing.class).optional().orElse(null);
    }

    /** People in service on the month's last day: paid this month, not gone before its end. */
    private int workers(long runId, YearMonth month) {
        return jdbc.sql("""
                select count(distinct p.user_id) from payslip p
                where p.run_id = ? and p.kind = 'REGULAR'
                  and not exists (select 1 from departure d where d.user_id = p.user_id and d.status = 'CONFIRMED'
                                  and d.last_day < ?)""").params(runId, month.atEndOfMonth()).query(Integer.class).single();
    }

    public Figures dc(YearMonth month) {
        Run run = run(month);
        Filing f = filing(month);
        List<Slip> slips = original(slips(run.id()), f);
        return Figures.of(slips, run.usdCdf(), workers(run.id(), month), children(slips));
    }

    /** The slips declared on the MOD. DC: all of them, but complementary ones approved after it was filed. */
    private static List<Slip> original(List<Slip> slips, Filing f) {
        return f == null ? slips : slips.stream()
                .filter(s -> s.kind().equals("REGULAR") || s.approvedAt() == null || !s.approvedAt().isAfter(f.filedAt())).toList();
    }

    private static List<Slip> later(List<Slip> slips, Filing f) {
        return slips.stream().filter(s -> !s.kind().equals("REGULAR") && s.approvedAt() != null && s.approvedAt().isAfter(f.filedAt())).toList();
    }

    private static int children(List<Slip> slips) {
        return slips.stream().map(Slip::children).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
    }

    // ---- the PDFs ----------------------------------------------------------------------------------------

    public byte[] modDc(YearMonth month) {
        Run run = run(month);
        Filing f = filing(month);
        List<Slip> slips = original(slips(run.id()), f);
        Figures fig = Figures.of(slips, run.usdCdf(), workers(run.id(), month), children(slips));
        return Texts.in("fr", () -> draw(month, "MOD. DC : DECLARATION DE VERSEMENT DES COTISATIONS", null, fig, null, f, slips, run.usdCdf()));
    }

    public byte[] modDcc(YearMonth month) {
        Run run = run(month);
        Filing f = filing(month);
        if (f == null) {
            throw new BusinessRuleException("Record the MOD. DC as filed before its complementary declaration.");
        }
        List<Slip> all = slips(run.id());
        List<Slip> added = later(all, f);
        if (added.isEmpty()) {
            throw new BusinessRuleException("No complementary payslip was approved after the MOD. DC: nothing to correct.");
        }
        List<Slip> before = original(all, f);
        Figures dc = Figures.of(before, run.usdCdf(), 0, 0);
        Figures dcc = Figures.of(all, run.usdCdf(), workers(run.id(), month), children(all));
        String reason = "Bulletins de paie complémentaires approuvés après la déclaration : "
                + String.join(", ", added.stream().map(s -> s.number() == null ? "—" : s.number()).toList()) + ".";
        return Texts.in("fr", () -> draw(month, "MOD. DCC : DECLARATION COMPLEMENTAIRE DES COTISATIONS POUR LE MOIS DE "
                + period(month).toUpperCase(Locale.FRENCH), reason, dcc, dc, f, added, run.usdCdf()));
    }

    private byte[] draw(YearMonth month, String title, String reason, Figures fig, Figures dc, Filing f, List<Slip> annex,
                        BigDecimal rate) {
        Company.Details d = company.get();
        Company.PayslipSettings s = company.payslipSettings();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 42, 42, 36, 36);
        PdfWriter.getInstance(doc, bytes);
        doc.addTitle(dc == null ? "MOD. DC " + month : "MOD. DCC " + month);
        doc.addCreator("HR Pay");
        doc.open();

        Paragraph centre = new Paragraph("Centre de Gestion : ………………………\nDate : ………………………\nN° Enregistrement : ………………………", SMALL);
        centre.setAlignment(Element.ALIGN_RIGHT);
        doc.add(centre);
        Paragraph t = new Paragraph(title, TITLE);
        t.setAlignment(Element.ALIGN_CENTER);
        t.setSpacingBefore(12);
        doc.add(t);
        if (dc != null) {
            Paragraph sub = new Paragraph("MODIFIANT LA DECLARATION DES COTISATIONS (MOD. DC) DU " + day(f.filedOn()), HEAD);
            sub.setAlignment(Element.ALIGN_CENTER);
            doc.add(sub);
        } else {
            Paragraph sub = new Paragraph("(Pour les employeurs ne relevant pas du Guichet Unique : Arrêté ministériel "
                    + "n°146/CAB/MINETAT/MTEPS/01/2018 du 10/11/2018, Article 3 et suivants)", SMALL);
            sub.setAlignment(Element.ALIGN_CENTER);
            doc.add(sub);
            doc.add(spaced(new Paragraph("PERIODE : " + period(month), HEAD), 10));
        }

        doc.add(spaced(new Paragraph("I. RENSEIGNEMENTS CONCERNANT L'IDENTITE DE L'EMPLOYEUR", HEAD), 10));
        PdfPTable who = table(new float[] {3, 4});
        row(who, "N° d'affiliation à la CNSS", or(d.cnssNumber()));
        row(who, "Dénomination ou raison sociale", or(d.name()));
        row(who, "Adresse(s) du (des) siège(s) d'exploitation", or(d.address()));
        row(who, "N° de téléphone", or(s.phone()));
        doc.add(who);

        if (dc != null) {
            doc.add(spaced(new Paragraph("La déclaration MOD. DC d'un import de (total des cotisations) " + amount(dc.total())
                    + " CDF du mois de " + period(month) + " doit être rectifiée pour la (les) raison(s) suivante(s) : " + reason, NORMAL), 8));
            doc.add(spaced(new Paragraph("II. RENSEIGNEMENTS COMPTABLES", HEAD), 10));
            PdfPTable acc = table(new float[] {5, 2});
            row(acc, "Montant total des rémunérations qui aurait dû être déclaré", amount(fig.base()));
            row(acc, "Montant total des revenus qui aurait dû être déclaré (travailleurs assimilés)", amount(BigDecimal.ZERO));
            doc.add(acc);
        } else {
            doc.add(spaced(new Paragraph("II. RENSEIGNEMENTS SUR LES ELEMENTS DES COTISATIONS SOCIALES DUES", HEAD), 10));
            PdfPTable el = table(new float[] {5, 2});
            row(el, "1) Montant total brut des sommes payées aux travailleurs", amount(fig.gross()));
            row(el, "2) Montant total pris en considération pour le calcul des cotisations (R)", amount(fig.base()));
            row(el, "3) Nombre de travailleurs occupés au dernier jour du mois", String.valueOf(fig.workers()));
            row(el, "4) Nombre de travailleurs assimilés occupés au dernier jour du mois", "0");
            row(el, "5) Montant total des revenus pour les travailleurs assimilés (Rev)", amount(BigDecimal.ZERO));
            row(el, "6) Nombre d'enfants bénéficiaires d'allocations familiales au dernier jour du mois", String.valueOf(fig.children()));
            doc.add(el);
            doc.add(spaced(new Paragraph("III. CALCUL DES COTISATIONS", HEAD), 10));
        }
        PdfPTable calc = table(new float[] {5, 2});
        row(calc, "1. Branche des pensions : R × 10 / 100", amount(fig.pensions()));
        row(calc, "2.1. Branche des risques professionnels : R × 1,5 / 100", amount(fig.risks()));
        row(calc, "2.2. Risques professionnels, travailleurs assimilés : Rev × 1,5 / 100", amount(BigDecimal.ZERO));
        row(calc, "3. Branche des prestations aux familles : R × 6,5 / 100", amount(fig.family()));
        boldRow(calc, dc == null ? "Total des cotisations = (1) + (2) + (3)" : "Total des cotisations au MOD. DCC (TDCC) = (1) + (2) + (3)",
                amount(fig.total()));
        if (dc != null) {
            BigDecimal diff = fig.total().subtract(dc.total());
            row(calc, "Total des cotisations au MOD. DC (TDC)", amount(dc.total()));
            boldRow(calc, diff.signum() >= 0 ? "Différence en faveur de la Caisse : TDCC − TDC" : "Différence en faveur de l'employeur : TDC − TDCC",
                    amount(diff.abs()));
        }
        doc.add(calc);
        doc.add(spaced(new Paragraph("Montants en francs congolais (CDF) au taux de la paie : 1 USD = " + amount(rate) + " CDF.", SMALL), 2));

        doc.add(spaced(new Paragraph((dc == null ? "IV" : "III") + ". DATE ET MODE DE PAIEMENT DU MONTANT DES COTISATIONS (espèces, OV, OP, chèques)", HEAD), 10));
        PdfPTable pay = table(new float[] {3, 4});
        row(pay, "Date", f == null || f.paidOn() == null || dc != null ? "……………………… (jj/mm/aaaa)" : day(f.paidOn()));
        row(pay, "Mode de paiement", f == null || f.paidMode() == null || dc != null ? "espèces · OV · OP · chèques" : MODES.get(f.paidMode()));
        doc.add(pay);

        doc.add(spaced(new Paragraph((dc == null ? "V" : "IV") + ". CERTIFICATION", HEAD), 10));
        doc.add(new Paragraph("Je (nous) soussigné(s), ……………………………………………, certifie (ons) sincère et exacte la présente "
                + "déclaration à laquelle est jointe une copie de chaque feuille de paie établie au cours du mois, et conforme au modèle de la CNSS.", NORMAL));
        Paragraph sign = new Paragraph("Fait à ……………………, le ……………………\n\nNOM, SIGNATURE ET SCEAU DE L'EMPLOYEUR", NORMAL);
        sign.setAlignment(Element.ALIGN_RIGHT);
        sign.setSpacingBefore(10);
        doc.add(sign);

        doc.newPage();
        Paragraph a = new Paragraph("RELEVE DES FEUILLES DE PAIE ANNEXEES", HEAD);
        a.setAlignment(Element.ALIGN_CENTER);
        a.setSpacingAfter(8);
        doc.add(a);
        PdfPTable list = table(new float[] {2, 3, 2, 3});
        for (String h : List.of("N° DE LA FEUILLE DE PAIE", "TOTAL DES REMUNERATIONS MENTIONNEES SUR LA FEUILLE DE PAIE",
                "N° DE LA FEUILLE DE PAIE", "TOTAL DES REMUNERATIONS MENTIONNEES SUR LA FEUILLE DE PAIE")) {
            list.addCell(cell(h, SMALL));
        }
        list.setHeaderRows(1);
        for (Slip slip : annex) {
            list.addCell(cell(slip.number() == null ? "—" : slip.number(), NORMAL));
            list.addCell(right(amount(cdf(slip.gross(), slip.currency(), rate)), NORMAL));
        }
        if (annex.size() % 2 == 1) {
            list.addCell(cell("", NORMAL));
            list.addCell(cell("", NORMAL));
        }
        doc.add(list);
        doc.close();
        return bytes.toByteArray();
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    static BigDecimal cdf(BigDecimal amount, String currency, BigDecimal rate) {
        if (amount == null) {
            return zero();
        }
        return ("USD".equals(currency) ? amount.multiply(rate) : amount).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal pct(BigDecimal base, BigDecimal rate) {
        return base.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    static String amount(BigDecimal n) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.FRENCH);
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", sym).format(n);
    }

    private static String period(YearMonth m) {
        return m.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH));
    }

    private static String day(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private static String or(String s) {
        return s == null || s.isBlank() ? "……………………………" : s;
    }

    private static Paragraph spaced(Paragraph p, float before) {
        p.setSpacingBefore(before);
        p.setSpacingAfter(3);
        return p;
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        return t;
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(4);
        return c;
    }

    private static PdfPCell right(String text, Font font) {
        PdfPCell c = cell(text, font);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private static void row(PdfPTable t, String label, String value) {
        t.addCell(cell(label, NORMAL));
        t.addCell(right(value, NORMAL));
    }

    private static void boldRow(PdfPTable t, String label, String value) {
        t.addCell(cell(label, BOLD));
        t.addCell(right(value, BOLD));
    }
}
