package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.lifecycle.Documents;
import cd.hrpay.notify.WebPush;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Phase 16: checklists, the document vault, letters signed with a code, certificates, notifications, offline check-ins. */
class LifecycleIT extends IntegrationTest {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj <<>> endobj\ntrailer <<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Documents documents;

    /** AuthIT locks David's account on purpose; these tests sign in as him, so the lock is lifted first. */
    @Override
    protected MockHttpSession login(String email) throws Exception {
        jdbc.sql("delete from login_failure where email = ?").param(email).update();
        return super.login(email);
    }

    private long id(String email) {
        return jdbc.sql("select id from app_user where email = ?").param(email).query(Long.class).single();
    }

    private long typeId(String code) {
        return jdbc.sql("select id from document_type where code = ?").param(code).query(Long.class).single();
    }

    private String latestCode(String email) {
        String body = jdbc.sql("select body from outbox_message where recipient = :r order by id desc limit 1")
                .param("r", email).query(String.class).single();
        Matcher m = Pattern.compile("(?:code is|est) (\\d{6})").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static String upload(long typeId, String name, byte[] data, String extra) {
        return "{\"typeId\":%d,\"fileName\":\"%s\",\"content\":\"%s\"%s}".formatted(typeId, name,
                Base64.getEncoder().encodeToString(data), extra);
    }

    @Test
    void eachCompanyStartsWithDocumentTypesStepsAndTemplates() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/lifecycle/document-types").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'ID_CARD')].hasExpiry").value(Matchers.contains(true)));
        mvc.perform(get("/api/lifecycle/checklist-steps").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'DEPARTURE')]", Matchers.hasSize(Matchers.greaterThanOrEqualTo(5))));
        mvc.perform(get("/api/lifecycle/templates").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'CONTRACT_CDI')]", Matchers.hasSize(1)));
        // An unknown field in a template is refused.
        mvc.perform(json(post("/api/lifecycle/templates").session(hr), """
                        {"code":"BAD","name":"Bad","kind":"LETTER","body":"Hello {{employee.shoe_size}}","requiresSignature":true,"active":true}"""))
                .andExpect(status().isUnprocessableContent());
        // Only HR and admins.
        mvc.perform(get("/api/lifecycle/overview").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void theHiringChecklistTicksItselfAsDocumentsAndEquipmentComeIn() throws Exception {
        MockHttpSession hr = login(HR);
        long david = id("employee3@demo.hrpay.cd");
        String started = mvc.perform(json(post("/api/lifecycle/people/" + david + "/checklists").session(hr),
                        "{\"kind\":\"HIRING\",\"dueOn\":\"2026-12-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items[?(@.documentTypeId == " + typeId("ID_CARD") + ")].done").value(Matchers.contains(false)))
                .andReturn().getResponse().getContentAsString();
        long checklist = ((Number) JsonPath.read(started, "$.id")).longValue();
        // One open checklist of each kind at a time.
        mvc.perform(json(post("/api/lifecycle/people/" + david + "/checklists").session(hr), "{\"kind\":\"HIRING\"}"))
                .andExpect(status().isConflict());

        // A file that is not a PDF or a picture is refused; an ID needs its expiry date.
        mvc.perform(json(post("/api/lifecycle/people/" + david + "/documents").session(hr),
                        upload(typeId("ID_CARD"), "id.exe", "MZ-not-a-pdf".getBytes(), ",\"expiresOn\":\"2030-01-01\"")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/lifecycle/people/" + david + "/documents").session(hr), upload(typeId("ID_CARD"), "id.pdf", PDF, "")))
                .andExpect(status().isUnprocessableContent());
        String doc = mvc.perform(json(post("/api/lifecycle/people/" + david + "/documents").session(hr),
                        upload(typeId("ID_CARD"), "id.pdf", PDF, ",\"expiresOn\":\"%s\"".formatted(LocalDate.now().plusDays(10)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andReturn().getResponse().getContentAsString();
        long docId = ((Number) JsonPath.read(doc, "$.id")).longValue();
        mvc.perform(get("/api/lifecycle/documents/" + docId + "/file").session(hr)).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(json(post("/api/lifecycle/people/" + david + "/equipment").session(hr),
                        "{\"kind\":\"Laptop\",\"serialNo\":\"SN-1\",\"issuedOn\":\"2026-11-01\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/lifecycle/people/" + david).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.checklists[0].items[?(@.documentTypeId == " + typeId("ID_CARD") + ")].done").value(Matchers.contains(true)))
                .andExpect(jsonPath("$.checklists[0].items[?(@.action == 'EQUIPMENT')].done").value(Matchers.contains(true)))
                .andExpect(jsonPath("$.documents[?(@.id == " + docId + ")]", Matchers.hasSize(1)));

        // The person sees the ID in their own documents; another employee cannot open it.
        mvc.perform(get("/api/my/documents").session(login("employee3@demo.hrpay.cd")))
                .andExpect(jsonPath("$.documents[?(@.id == " + docId + ")]", Matchers.hasSize(1)));
        mvc.perform(get("/api/my/documents/" + docId + "/file").session(login(EMPLOYEE))).andExpect(status().isNotFound());

        // The expiry is within the reminder window: HR and the person hear of it once.
        documents.remind();
        documents.remind();
        assertThat(jdbc.sql("select count(*) from notification where kind = 'document.expiring' and user_id = ?").param(david)
                .query(Integer.class).single()).isEqualTo(1);
        mvc.perform(get("/api/lifecycle/overview").session(hr))
                .andExpect(jsonPath("$.expiring[?(@.id == " + docId + ")]", Matchers.hasSize(1)));

        // A step ticked by hand, then the checklist closed.
        long manual = ((Number) JsonPath.<List<Number>>read(started, "$.items[?(@.action == null)].id").getFirst()).longValue();
        mvc.perform(json(post("/api/lifecycle/checklist-items/" + manual).session(hr), "{\"done\":true,\"note\":\"Done in person\"}"))
                .andExpect(status().isOk());
        // Steps are still open: it can only be cancelled.
        mvc.perform(json(post("/api/lifecycle/checklists/" + checklist + "/close").session(hr), "{\"cancel\":false}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/lifecycle/checklists/" + checklist + "/close").session(hr), "{\"cancel\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(delete("/api/lifecycle/documents/" + docId).session(hr).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/lifecycle/documents/" + docId + "/file").session(hr)).andExpect(status().isNotFound());
    }

    @Test
    void aLetterIsSignedWithAOneTimeCodeAndFiledWithItsSignaturePage() throws Exception {
        MockHttpSession hr = login(HR);
        String email = "employee2@demo.hrpay.cd";
        long esther = id(email);
        String created = mvc.perform(json(post("/api/lifecycle/people/" + esther + "/letters").session(hr), """
                        {"title":"Mission letter","body":"## Object\\n\\nDear {{employee.name}},\\n\\n{{company.name}} sends you on mission on {{today}}.",
                         "kind":"LETTER","requiresSignature":true}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long letter = ((Number) JsonPath.read(created, "$.id")).longValue();
        mvc.perform(get("/api/lifecycle/letters/" + letter + "/file").session(hr)).andExpect(status().isOk());
        // A draft is not shown to the employee.
        MockHttpSession me = login(email);
        mvc.perform(post("/api/my/letters/" + letter + "/code").session(me).with(csrf())).andExpect(status().isUnprocessableContent());

        String sent = mvc.perform(post("/api/lifecycle/letters/" + letter + "/send").session(hr).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.number").value(Matchers.startsWith("LT-")))
                .andExpect(jsonPath("$.body").value(Matchers.containsString("KALALA Esther")))
                .andReturn().getResponse().getContentAsString();
        String sha = JsonPath.read(sent, "$.sha256");
        mvc.perform(get("/api/my/documents").session(me))
                .andExpect(jsonPath("$.letters[?(@.id == " + letter + ")].status").value(Matchers.contains("SENT")));
        mvc.perform(get("/api/notifications").session(me))
                .andExpect(jsonPath("$.items[?(@.kind == 'letter.sent')]", Matchers.not(Matchers.empty())));

        String code = mvc.perform(post("/api/my/letters/" + letter + "/code").session(me).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String challenge = JsonPath.read(code, "$.challengeId");
        // The name typed must be the person's; a wrong code is refused.
        mvc.perform(json(post("/api/my/letters/" + letter + "/sign").session(me),
                        "{\"challengeId\":\"%s\",\"code\":\"%s\",\"name\":\"Someone Else\"}".formatted(challenge, latestCode(email))))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/my/letters/" + letter + "/sign").session(me),
                        "{\"challengeId\":\"%s\",\"code\":\"000000\",\"name\":\"Esther Kalala\"}".formatted(challenge)))
                .andExpect(status().is4xxClientError());
        // Another employee cannot sign it.
        MockHttpSession other = login(EMPLOYEE);
        mvc.perform(post("/api/my/letters/" + letter + "/code").session(other).with(csrf())).andExpect(status().isNotFound());

        String fresh = JsonPath.read(mvc.perform(post("/api/my/letters/" + letter + "/code").session(me).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.challengeId");
        mvc.perform(json(post("/api/my/letters/" + letter + "/sign").session(me),
                        "{\"challengeId\":\"%s\",\"code\":\"%s\",\"name\":\"esther  KALALA\"}".formatted(fresh, latestCode(email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"))
                .andExpect(jsonPath("$.signedSha256").value(Matchers.not(sha)));
        byte[] signed = mvc.perform(get("/api/my/letters/" + letter + "/file").session(me)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(signed, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(Documents.sha256(signed)).isNotEqualTo(sha);
        try (var reader = new org.openpdf.text.pdf.PdfReader(signed)) {
            String last = new org.openpdf.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(reader.getNumberOfPages());
            assertThat(last).contains(sha);
        }
        // Filed in the vault, recorded in the audit log, and the sender is told.
        assertThat(jdbc.sql("select count(*) from employee_document where user_id = ? and source = 'SIGNED' and deleted_at is null")
                .param(esther).query(Integer.class).single()).isPositive();
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'letter.signed' and details->>'sha256' = ?").param(sha)
                .query(Integer.class).single()).isEqualTo(1);
        mvc.perform(get("/api/notifications").session(hr))
                .andExpect(jsonPath("$.items[?(@.kind == 'letter.signed')]", Matchers.not(Matchers.empty())));
        // A signed letter stays as it is.
        mvc.perform(post("/api/lifecycle/letters/" + letter + "/cancel").session(hr).with(csrf())).andExpect(status().isUnprocessableContent());

        // A second letter, refused by the employee.
        String second = mvc.perform(json(post("/api/lifecycle/people/" + esther + "/letters").session(hr),
                        "{\"title\":\"Warning\",\"body\":\"Dear {{employee.first_name}}.\",\"kind\":\"LETTER\",\"requiresSignature\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long refused = ((Number) JsonPath.read(second, "$.id")).longValue();
        mvc.perform(post("/api/lifecycle/letters/" + refused + "/send").session(hr).with(csrf())).andExpect(status().isOk());
        mvc.perform(json(post("/api/my/letters/" + refused + "/decline").session(me), "{\"reason\":\"I disagree\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
    }

    @Test
    void aCertificateIsAskedForThenIssuedOrRefused() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession me = login(EMPLOYEE);
        long jean = id(EMPLOYEE);
        String asked = mvc.perform(json(post("/api/my/certificates").session(me), "{\"kind\":\"WORK\",\"purpose\":\"Visa\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long request = ((Number) JsonPath.read(asked, "$.id")).longValue();
        // One request of each kind at a time.
        mvc.perform(json(post("/api/my/certificates").session(me), "{\"kind\":\"WORK\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/lifecycle/overview").session(hr))
                .andExpect(jsonPath("$.certificates[?(@.id == " + request + ")]", Matchers.hasSize(1)));

        mvc.perform(post("/api/lifecycle/certificates/" + request + "/issue").session(hr).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.number").value(Matchers.startsWith("AT-")))
                .andExpect(jsonPath("$.documentId").isNumber());
        String mine = mvc.perform(get("/api/my/documents").session(me)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long doc = ((Number) JsonPath.<List<Number>>read(mine, "$.documents[?(@.source == 'CERTIFICATE')].id").getFirst()).longValue();
        mvc.perform(get("/api/my/documents/" + doc + "/file").session(me)).andExpect(status().isOk());

        String salary = mvc.perform(json(post("/api/my/certificates").session(me), "{\"kind\":\"SALARY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long second = ((Number) JsonPath.read(salary, "$.id")).longValue();
        mvc.perform(json(post("/api/lifecycle/certificates/" + second + "/reject").session(hr), "{\"reason\":\"Ask the bank's form\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        // HR can also issue one directly.
        mvc.perform(json(post("/api/lifecycle/people/" + jean + "/certificates").session(hr), "{\"kind\":\"SALARY\",\"purpose\":\"Loan\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(Matchers.startsWith("AS-")));
    }

    @Test
    void offlineCheckInsAreRecordedSentForReviewOrRefusedBySiteSetting() throws Exception {
        String email = "employee3@demo.hrpay.cd";
        long site = jdbc.sql("select site_id from app_user where email = ?").param(email).query(Long.class).single();
        String before = jdbc.sql("select offline_checkin from site where id = ?").param(site).query(String.class).single();
        MockHttpSession me = login(email);
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusHours(50).withNano(0);
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String batch = """
                {"punches":[
                  {"clientId":"%s","type":"OUT","at":"%s","lat":-4.30375,"lng":15.31085,"accuracyM":20},
                  {"clientId":"%s","type":"IN","at":"%s","lat":-4.30375,"lng":15.31085,"accuracyM":20}]}"""
                .formatted(b, at.plusHours(1), a, at);
        try {
            jdbc.sql("update site set offline_checkin = 'IN_AREA' where id = ?").param(site).update();
            mvc.perform(json(post("/api/attendance/offline").session(me), batch))
                    .andExpect(status().isOk())
                    // Oldest first, whatever order the phone sent them in.
                    .andExpect(jsonPath("$[0].clientId").value(a))
                    .andExpect(jsonPath("$[0].outcome").value("RECORDED"))
                    .andExpect(jsonPath("$[1].outcome").value("RECORDED"));
            assertThat(jdbc.sql("select count(*) from clock_event where user_id = ? and source = 'OFFLINE'").param(id(email))
                    .query(Integer.class).single()).isEqualTo(2);
            // Sent again: the same answer, nothing recorded twice.
            mvc.perform(json(post("/api/attendance/offline").session(me), batch))
                    .andExpect(jsonPath("$[0].outcome").value("RECORDED"));
            assertThat(jdbc.sql("select count(*) from clock_event where user_id = ? and source = 'OFFLINE'").param(id(email))
                    .query(Integer.class).single()).isEqualTo(2);

            // Outside the area: the manager decides.
            mvc.perform(json(post("/api/attendance/offline").session(me), """
                            {"punches":[{"clientId":"%s","type":"IN","at":"%s","lat":-4.32,"lng":15.31085,"accuracyM":20}]}"""
                            .formatted(UUID.randomUUID(), at.plusHours(24))))
                    .andExpect(jsonPath("$[0].outcome").value("REVIEW"));
            // Too old, or ahead of the server's clock.
            mvc.perform(json(post("/api/attendance/offline").session(me), """
                            {"punches":[{"clientId":"%s","type":"IN","at":"%s"},{"clientId":"%s","type":"IN","at":"%s"}]}"""
                            .formatted(UUID.randomUUID(), at.minusDays(3), UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC).plusHours(1))))
                    .andExpect(jsonPath("$[0].outcome").value("REFUSED"))
                    .andExpect(jsonPath("$[1].outcome").value("REFUSED"));

            jdbc.sql("update site set offline_checkin = 'OFF' where id = ?").param(site).update();
            mvc.perform(json(post("/api/attendance/offline").session(me), """
                            {"punches":[{"clientId":"%s","type":"IN","at":"%s"}]}""".formatted(UUID.randomUUID(), at.plusHours(2))))
                    .andExpect(jsonPath("$[0].outcome").value("REFUSED"));
        } finally {
            jdbc.sql("update site set offline_checkin = ? where id = ?").params(before, site).update();
        }
    }

    @Test
    void notificationsAreReadAndPhonesOnlyRegisterWithKnownPushServices() throws Exception {
        MockHttpSession me = login(EMPLOYEE);
        mvc.perform(get("/api/notifications").session(me)).andExpect(status().isOk()).andExpect(jsonPath("$.unread").isNumber());
        mvc.perform(json(post("/api/notifications/read").session(me), "{}")).andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/notifications").session(me)).andExpect(jsonPath("$.unread").value(0));
        mvc.perform(get("/api/notifications/push").session(me)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(json(post("/api/notifications/push").session(me), "{\"endpoint\":\"https://169.254.169.254/latest\"}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(json(post("/api/notifications/push").session(me), "{\"endpoint\":\"http://fcm.googleapis.com/fcm/send/x\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void aPushTokenIsSignedWithTheVapidKey() throws Exception {
        // A key made for this test only, thrown away at its end.
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = g.generateKeyPair();
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(pair.getPrivate());
        s.update("x".getBytes());
        byte[] raw = WebPush.raw(s.sign());
        assertThat(raw).hasSize(64);
        Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
        v.initVerify((ECPublicKey) pair.getPublic());
        v.update("x".getBytes());
        assertThat(v.verify(raw)).isTrue();
    }
}
