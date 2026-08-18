package com.tinyurl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class Main {

    // Instances partagées entre toutes les requêtes
    private static final LinkStore store = new LinkStore();
    private static final ObjectMapper json = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        int port = 8080;
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("TinyURL server started on http://localhost:" + port);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        //ExecutorService executor = Executors.newFixedThreadPool(50);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            executor.submit(() -> handle(clientSocket));
            //new Thread(() -> handle(clientSocket)).start();
        }
    }

    private static void handle(Socket clientSocket) {
        try (clientSocket;
             BufferedReader in = new BufferedReader(
                 new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = clientSocket.getOutputStream()) {

            // ---------- 1. Parser la request line ----------
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                writeResponse(out, 400, "Bad Request", "text/plain", "Malformed request line");
                return;
            }
            String method = parts[0];
            String path = parts[1];

            System.out.println("→ " + method + " " + path);

            // ---------- 2. Parser les headers ----------
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String name = line.substring(0, colonIdx).trim().toLowerCase();
                    String value = line.substring(colonIdx + 1).trim();
                    headers.put(name, value);
                }
            }

            // ---------- 3. Lire le body si Content-Length ----------
            String body = "";
            String contentLengthHeader = headers.get("content-length");
            if (contentLengthHeader != null) {
                int contentLength = Integer.parseInt(contentLengthHeader);
                if (contentLength > 0) {
                    char[] buffer = new char[contentLength];
                    int read = in.read(buffer, 0, contentLength);
                    body = new String(buffer, 0, read);
                }
            }

            // ---------- 4. Router vers le bon handler ----------
            route(method, path, body, out);

        } catch (Exception e) {
            System.err.println("Error handling request: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void route(String method, String path, String body, OutputStream out) throws IOException {

        // GET /health
        if (method.equals("GET") && path.equals("/health")) {
            writeResponse(out, 200, "OK", "application/json", "{\"status\":\"UP\"}");
            return;
        }

        // POST /api/links → créer un lien
        if (method.equals("POST") && path.equals("/api/links")) {
            handleCreateLink(body, out);
            return;
        }

        // GET /api/links/{code} → info sur un lien
        if (method.equals("GET") && path.startsWith("/api/links/")) {
            String code = path.substring("/api/links/".length());
            handleGetLink(code, out);
            return;
        }

        // DELETE /api/links/{code}
        if (method.equals("DELETE") && path.startsWith("/api/links/")) {
            String code = path.substring("/api/links/".length());
            handleDeleteLink(code, out);
            return;
        }

        // GET /r/{code} → REDIRECTION (le vrai use case)
        if (method.equals("GET") && path.startsWith("/r/")) {
            String code = path.substring("/r/".length());
            handleRedirect(code, out);
            return;
        }

        // Path connu mais mauvaise méthode → 405
        if (path.equals("/health") || path.equals("/api/links") || path.startsWith("/api/links/") || path.startsWith("/r/")) {
            writeResponse(out, 405, "Method Not Allowed", "application/json",
                "{\"error\":\"method not allowed\"}");
            return;
        }

        // Rien de tout ça → 404
        writeResponse(out, 404, "Not Found", "application/json",
            "{\"error\":\"route not found\"}");
    }

    // ---------- Handlers ----------

    private static void handleCreateLink(String body, OutputStream out) throws IOException {
        if (body.isEmpty()) {
            writeResponse(out, 400, "Bad Request", "application/json",
                "{\"error\":\"body required\"}");
            return;
        }

        String url;
        try {
            JsonNode node = json.readTree(body);
            if (!node.has("url") || node.get("url").asText().isBlank()) {
                writeResponse(out, 422, "Unprocessable Entity", "application/json",
                    "{\"error\":\"field 'url' is required\"}");
                return;
            }
            url = node.get("url").asText();
        } catch (Exception e) {
            writeResponse(out, 400, "Bad Request", "application/json",
                "{\"error\":\"invalid JSON\"}");
            return;
        }

        // Validation basique de l'URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            writeResponse(out, 422, "Unprocessable Entity", "application/json",
                "{\"error\":\"url must start with http:// or https://\"}");
            return;
        }

        Link created = store.save(url);
        String responseBody = json.writeValueAsString(Map.of(
            "code", created.code(),
            "url", created.url(),
            "short_url", "http://localhost:8080/r/" + created.code(),
            "hits", created.hits().get(),
            "created_at", created.createdAt().toString()
        ));

        // 201 Created avec header Location
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Location", "/api/links/" + created.code());
        writeResponseWithHeaders(out, 201, "Created", headers, responseBody);
    }

    private static void handleGetLink(String code, OutputStream out) throws IOException {
        Optional<Link> found = store.findByCode(code);
        if (found.isEmpty()) {
            writeResponse(out, 404, "Not Found", "application/json",
                "{\"error\":\"link not found\"}");
            return;
        }
        Link link = found.get();
        String responseBody = json.writeValueAsString(Map.of(
            "code", link.code(),
            "url", link.url(),
            "short_url", "http://localhost:8080/r/" + link.code(),
            "hits", link.hits().get(),
            "created_at", link.createdAt().toString()
        ));
        writeResponse(out, 200, "OK", "application/json", responseBody);
    }

    private static void handleDeleteLink(String code, OutputStream out) throws IOException {
        boolean deleted = store.delete(code);
        if (!deleted) {
            writeResponse(out, 404, "Not Found", "application/json",
                "{\"error\":\"link not found\"}");
            return;
        }
        // 204 No Content : succès, rien à renvoyer
        writeResponse(out, 204, "No Content", null, "");
    }

    private static void handleRedirect(String code, OutputStream out) throws IOException {
        Optional<Link> found = store.findByCode(code);
        if (found.isEmpty()) {
            writeResponse(out, 404, "Not Found", "text/plain", "Link not found");
            return;
        }
        store.incrementHits(code);

        // 302 Found avec header Location (le navigateur va suivre automatiquement)
        Map<String, String> headers = new HashMap<>();
        headers.put("Location", found.get().url());
        writeResponseWithHeaders(out, 302, "Found", headers, "");
    }

    // ---------- Helpers pour écrire les réponses ----------

    private static void writeResponse(OutputStream out, int status, String statusText,
                                       String contentType, String body) throws IOException {
        Map<String, String> headers = new HashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        writeResponseWithHeaders(out, status, statusText, headers, body);
    }

    private static void writeResponseWithHeaders(OutputStream out, int status, String statusText,
                                                  Map<String, String> headers, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(" ").append(statusText).append("\r\n");
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        sb.append("\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }
}