package cd.hrpay.job;

import cd.hrpay.dataimport.Imports;
import cd.hrpay.i18n.Texts;
import cd.hrpay.payroll.LeaveAllowances;
import cd.hrpay.payroll.PayrollRuns;
import cd.hrpay.payroll.PayslipPdf;
import cd.hrpay.payroll.Quinzaines;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The payroll's long tasks as background jobs: calculating and closing a month, the quinzaine, leave pay, imports and
 * every payslip of a month as PDFs in one zip. Each keeps the rules of its page; only where it runs changes.
 */
@Component
class PayrollJobs {

    static final String CALCULATE = "payroll.calculate";
    static final String CLOSE = "payroll.close";
    static final String QUINZAINE = "quinzaine.calculate";
    static final String LEAVE_PAY = "leave-pay.calculate";
    static final String IMPORT = "import";
    static final String PAYSLIPS_ZIP = "payslips.zip";

    PayrollJobs(JobTypes types, PayrollRuns runs, Quinzaines quinzaines, LeaveAllowances allowances, Imports imports,
                PayslipPdf pdf) {
        types.register(CALCULATE, "payroll.run", p -> Texts.t("Payroll calculation, %s", month(p)),
                job -> runs.calculate(YearMonth.parse(job.text("month"))));
        types.register(CLOSE, "payroll.close", p -> Texts.t("Payroll closing, %s", month(p)), job -> {
            YearMonth month = YearMonth.parse(job.text("month"));
            // Run again after its server stopped once the month was closed: nothing left to do.
            if (runs.run(month).map(r -> r.status().equals("CLOSED")).orElse(false)) {
                return runs.view(month, List.of());
            }
            return runs.close(month);
        });
        types.register(QUINZAINE, "payroll.run", p -> Texts.t("Quinzaine calculation, %s", month(p)),
                job -> quinzaines.calculate(YearMonth.parse(job.text("month"))));
        types.register(LEAVE_PAY, "payroll.run", p -> Texts.t("Leave pay calculation"),
                job -> allowances.calculate(job.ids("leaveRequestIds")));
        types.register(IMPORT, "data.import", p -> Texts.t("Import: %s", p.path("fileName").asString("")),
                job -> {
                    Imports.Kind kind = Imports.Kind.valueOf(job.text("kind"));
                    return imports.run(kind, job.input().name(), job.input().data(), job.flag("apply"));
                });
        types.register(PAYSLIPS_ZIP, "payslip.read.all", p -> Texts.t("All payslips of %s (zip)", month(p)), job -> {
            YearMonth month = YearMonth.parse(job.text("month"));
            List<Long> ids = runs.payslipIds(month);
            if (ids.isEmpty()) {
                throw new cd.hrpay.common.BusinessRuleException("No payslip has been calculated for " + month + ".");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Set<String> names = new HashSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                int n = 0;
                for (long id : ids) {
                    PayrollRuns.Payslip slip = runs.payslip(id);
                    String reg = String.valueOf(slip.snapshot().getOrDefault("registrationNo", slip.userId()))
                            .replaceAll("[^A-Za-z0-9_-]", "");
                    String base = "payslip-" + month + "-" + (reg.isBlank() ? String.valueOf(slip.userId()) : reg);
                    String name = base + ".pdf";
                    for (int i = 2; !names.add(name); i++) {
                        name = base + "-" + i + ".pdf";
                    }
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(pdf.render(slip));
                    zip.closeEntry();
                    n++;
                    Progress.report(n, ids.size(), Texts.t("Payslip %s of %s", n, ids.size()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new JobFile("payslips-" + month + ".zip", "application/zip", out.toByteArray());
        });
    }

    private static String month(JsonNode p) {
        String m = p.path("month").asString("");
        try {
            return Texts.month(YearMonth.parse(m));
        } catch (RuntimeException e) {
            return m.toLowerCase(Locale.ROOT);
        }
    }
}
