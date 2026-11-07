package cd.hrpay.platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The provider's licence tool, run on the provider's own computer, never on a customer server:
 * <pre>
 *   ./gradlew licence --args="keys ~/.hrpay-licence"
 *       makes the provider's key pair once: keep private.key secret and backed up; put the printed public key in
 *       backend/src/main/resources/licence/public-key.txt so that builds check licences against it.
 *   ./gradlew licence --args="issue --key ~/.hrpay-licence/private.key --id L-2026-001 --licensee 'Acme SARL'
 *       --expires 2027-12-31 --employees 300 --companies 1 --modules ATTENDANCE,PAYROLL,EVALUATIONS --out acme.licence"
 *       signs a licence file for a customer, who installs it on the Licence page.
 * </pre>
 */
public final class LicenceTool {

    private LicenceTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "keys" -> keys(Path.of(expand(args.length > 1 ? args[1] : "~/.hrpay-licence")));
            case "issue" -> issue(options(Arrays.copyOfRange(args, 1, args.length)));
            default -> usage();
        }
    }

    private static void keys(Path dir) throws Exception {
        Path privateFile = dir.resolve("private.key");
        if (Files.exists(privateFile)) {
            System.err.println(privateFile + " already exists: a new key pair would make every issued licence invalid.");
            System.exit(1);
        }
        Files.createDirectories(dir);
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(privateFile, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n");
        try {
            Files.setPosixFilePermissions(privateFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        Files.writeString(dir.resolve("public-key.txt"), publicKey + "\n");
        System.out.println("Private key: " + privateFile + " (keep it secret and backed up)");
        System.out.println("Public key, for backend/src/main/resources/licence/public-key.txt:");
        System.out.println(publicKey);
    }

    private static void issue(Map<String, String> o) throws Exception {
        for (String required : List.of("key", "id", "licensee", "expires", "modules", "out")) {
            if (!o.containsKey(required)) {
                System.err.println("Missing --" + required);
                usage();
                System.exit(1);
            }
        }
        List<String> modules = Arrays.stream(o.get("modules").split(",")).map(String::trim).map(String::toUpperCase).toList();
        if (!Licences.MODULES.containsAll(modules)) {
            System.err.println("Modules are ATTENDANCE, PAYROLL and EVALUATIONS.");
            System.exit(1);
        }
        Licences.Licence licence = new Licences.Licence(o.get("id"), o.get("licensee"), LocalDate.now(),
                LocalDate.parse(o.get("expires")), o.containsKey("employees") ? Integer.valueOf(o.get("employees")) : null,
                Integer.valueOf(o.getOrDefault("companies", "1")), modules, Integer.valueOf(o.getOrDefault("grace", "30")));
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(Files.readString(Path.of(expand(o.get("key")))).trim())));
        String file = sign(licence, key);
        Files.writeString(Path.of(expand(o.get("out"))), file, StandardCharsets.UTF_8);
        System.out.println("Licence written to " + o.get("out"));
    }

    /** The licence file: readable comments, then base64url(json) "." base64url(signature). */
    public static String sign(Licences.Licence licence, PrivateKey key) throws Exception {
        byte[] payload = JsonMapper.builder().build().writeValueAsBytes(licence);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(payload);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return "# HR Pay licence " + licence.id() + "\n"
                + "# Licensee: " + licence.licensee() + "\n"
                + "# Modules: " + String.join(", ", licence.modules()) + "\n"
                + "# Employees: " + (licence.maxEmployees() == null ? "no limit" : licence.maxEmployees())
                + ", companies: " + licence.maxCompanies() + "\n"
                + "# Valid until: " + licence.expires() + "\n"
                + b64.encodeToString(payload) + "." + b64.encodeToString(signer.sign()) + "\n";
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            o.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        return o;
    }

    private static String expand(String path) {
        return path.startsWith("~/") ? System.getProperty("user.home") + path.substring(1) : path;
    }

    private static void usage() {
        System.out.println("""
                HR Pay licence tool
                  keys [dir]       make the provider's key pair (default ~/.hrpay-licence)
                  issue --key FILE --id ID --licensee NAME --expires YYYY-MM-DD --modules A,B[,C]
                        [--employees N] [--companies N] [--grace DAYS] --out FILE""");
    }
}
