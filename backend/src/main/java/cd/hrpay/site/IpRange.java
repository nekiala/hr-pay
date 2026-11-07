package cd.hrpay.site;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** An IPv4 or IPv6 range in CIDR notation, such as 203.0.113.0/24. A bare address means a single host. */
public final class IpRange {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

    private final byte[] network;
    private final int prefixLength;
    private final String normalized;

    private IpRange(byte[] network, int prefixLength, String normalized) {
        this.network = network;
        this.prefixLength = prefixLength;
        this.normalized = normalized;
    }

    public static IpRange parse(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            throw new IllegalArgumentException("IP range is empty.");
        }
        String text = cidr.trim();
        String[] parts = text.split("/", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("Invalid IP range: " + cidr);
        }
        byte[] address = parseLiteral(parts[0], cidr);
        int maxBits = address.length * 8;
        int prefix;
        try {
            prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : maxBits;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid prefix length in " + cidr);
        }
        if (prefix < 0 || prefix > maxBits) {
            throw new IllegalArgumentException("Prefix length must be between 0 and " + maxBits + " in " + cidr);
        }
        byte[] masked = mask(address, prefix);
        String base;
        try {
            base = InetAddress.getByAddress(masked).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP range: " + cidr);
        }
        return new IpRange(masked, prefix, base + "/" + prefix);
    }

    /** Parses only literal addresses, so no DNS lookup can ever happen. */
    private static byte[] parseLiteral(String text, String original) {
        boolean v4 = IPV4.matcher(text).matches();
        boolean v6 = text.contains(":") && IPV6.matcher(text).matches();
        if (v4) {
            for (String octet : text.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    throw new IllegalArgumentException("Invalid IP address in " + original);
                }
            }
        }
        if (!v4 && !v6) {
            throw new IllegalArgumentException("Invalid IP address in " + original);
        }
        try {
            return InetAddress.getByName(text).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP address in " + original);
        }
    }

    public boolean contains(String ip) {
        byte[] candidate;
        try {
            candidate = parseLiteral(ip.trim(), ip);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (candidate.length != network.length) {
            return false;
        }
        return java.util.Arrays.equals(mask(candidate, prefixLength), network);
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] result = address.clone();
        for (int i = 0; i < result.length; i++) {
            int bitsInByte = Math.clamp(prefix - i * 8L, 0, 8);
            int byteMask = bitsInByte == 0 ? 0 : (0xFF << (8 - bitsInByte)) & 0xFF;
            result[i] = (byte) (result[i] & byteMask);
        }
        return result;
    }

    @Override
    public String toString() {
        return normalized;
    }
}
