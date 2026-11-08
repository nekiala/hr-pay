package cd.hrpay.it;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A tiny SMTP server for tests: accepts everything, signs anyone in, and keeps what it received. */
final class FakeSmtp implements AutoCloseable {

    record Mail(String ehlo, String user, List<String> recipients, String data) {}

    final List<Mail> received = new CopyOnWriteArrayList<>();
    private final ServerSocket server;
    private final Thread thread;

    FakeSmtp() throws IOException {
        server = new ServerSocket(0);
        thread = Thread.ofVirtual().start(this::serve);
    }

    int port() {
        return server.getLocalPort();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                talk(s);
            } catch (IOException e) {
                // closed, or the client hung up
            }
        }
    }

    private void talk(Socket s) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
        out.print("220 fake ESMTP\r\n");
        out.flush();
        String ehlo = null;
        String user = null;
        List<String> to = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            String upper = line.toUpperCase();
            if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                ehlo = line.substring(5).trim();
                send(out, "250-fake\r\n250-AUTH PLAIN LOGIN\r\n250 OK");
            } else if (upper.startsWith("AUTH PLAIN")) {
                String token = line.length() > 11 ? line.substring(11).trim() : null;
                if (token == null || token.isEmpty()) {
                    send(out, "334 ");
                    token = in.readLine();
                }
                String[] parts = new String(java.util.Base64.getDecoder().decode(token), StandardCharsets.UTF_8).split("\0");
                user = parts.length > 1 ? parts[1] : "";
                send(out, "235 OK");
            } else if (upper.startsWith("AUTH LOGIN")) {
                send(out, "334 VXNlcm5hbWU6");
                user = new String(java.util.Base64.getDecoder().decode(in.readLine()), StandardCharsets.UTF_8);
                send(out, "334 UGFzc3dvcmQ6");
                in.readLine();
                send(out, "235 OK");
            } else if (upper.startsWith("MAIL FROM")) {
                to = new ArrayList<>();
                send(out, "250 OK");
            } else if (upper.startsWith("RCPT TO")) {
                to.add(line.substring(line.indexOf('<') + 1, line.indexOf('>')));
                send(out, "250 OK");
            } else if (upper.equals("DATA")) {
                send(out, "354 go");
                StringBuilder data = new StringBuilder();
                String d;
                while ((d = in.readLine()) != null && !d.equals(".")) {
                    data.append(d).append('\n');
                }
                received.add(new Mail(ehlo, user, List.copyOf(to), data.toString()));
                send(out, "250 OK");
            } else if (upper.startsWith("QUIT")) {
                send(out, "221 bye");
                return;
            } else {
                send(out, "250 OK");
            }
        }
    }

    private static void send(PrintWriter out, String text) {
        out.print(text + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
        thread.interrupt();
    }
}
