package cd.hrpay.lifecycle;

import java.util.List;

/**
 * A contract (or letter) ready to print, as both the PDF and the Word copy lay it out: the title and the model's
 * reference on the first page, the articles, the place and date, the two signature blocks, the small-print notes,
 * then the annex with the parties' full identity and the model's document control.
 *
 * @param body           the articles: a blank line starts a paragraph, a line starting with "## " is a heading
 * @param notes          small print after the signatures (the part of the model after a line "---"), or null
 * @param employeeSigner null for a letter the employee does not sign
 * @param annex          empty for a letter
 */
public record ContractSheet(String title, String reference, String number, String body, String notes, String placeAndDate,
                            String employerSigner, String employerHeading, String employerCapacity, String employeeSigner,
                            String employeeHeading, String employeeMention, List<Section> annex, Letters.Control control,
                            String approvalHeading) {

    public record Section(String heading, List<LifecyclePdf.Line> lines) {}

    /** The model's document control lines that are filled in. */
    List<LifecyclePdf.Line> controlLines() {
        java.util.List<LifecyclePdf.Line> out = new java.util.ArrayList<>();
        Letters.Control c = control == null ? Letters.Control.NONE : control;
        add(out, "Document code", c.code());
        add(out, "Version", c.version());
        add(out, "Classification", c.classification());
        add(out, "Document owner", c.owner());
        add(out, "Approver", c.approver());
        add(out, "Date of adoption", c.adoptedOn() == null ? null : cd.hrpay.i18n.Texts.day(c.adoptedOn()));
        add(out, "Date in force", c.effectiveOn() == null ? null : cd.hrpay.i18n.Texts.day(c.effectiveOn()));
        return out;
    }

    private static void add(List<LifecyclePdf.Line> out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.add(new LifecyclePdf.Line(cd.hrpay.i18n.Texts.t(label), value));
        }
    }

    /** The body as blocks: headings and paragraphs. */
    static List<Block> blocks(String text) {
        java.util.List<Block> out = new java.util.ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String block : text.replace("\r\n", "\n").split("\n\\s*\n")) {
            String t = block.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("## ")) {
                int nl = t.indexOf('\n');
                if (nl < 0) {
                    out.add(new Block(true, t.substring(3).strip()));
                } else {
                    // A heading followed directly by its text, without a blank line between.
                    out.add(new Block(true, t.substring(3, nl).strip()));
                    out.add(new Block(false, t.substring(nl + 1).strip()));
                }
            } else {
                out.add(new Block(false, t));
            }
        }
        return out;
    }

    record Block(boolean heading, String text) {}
}
