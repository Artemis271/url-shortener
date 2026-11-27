package shortener.infra;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import shortener.core.LinkService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RedirectServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public RedirectServer(int port, LinkService links) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(workers);
        server.createContext("/", exchange -> handle(exchange, links));
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static void handle(HttpExchange exchange, LinkService links) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, "Используйте GET.");
                return;
            }

            String path = exchange.getRequestURI().getRawPath();
            if (!path.matches("/[A-Za-z0-9_-]{12}")) {
                send(exchange, 404, "Короткая ссылка не найдена.");
                return;
            }
            LinkService.Visit visit = links.visit(path.substring(1));
            if (visit.target() != null) {
                exchange.getResponseHeaders().set("Location", visit.target().toASCIIString());
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            send(exchange, visit.reason() == null ? 404 : 410, unavailable(visit.reason()));
        }
    }

    public static String unavailable(LinkService.Reason reason) {
        if (reason == null) {
            return "Короткая ссылка не найдена.";
        }
        return switch (reason) {
            case LIMIT -> "Лимит переходов исчерпан. Создайте новую ссылку.";
            case EXPIRED -> "Срок действия истёк. Создайте новую ссылку.";
            case DELETED -> "Ссылка удалена владельцем.";
        };
    }

    private static void send(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        server.stop(1);
        workers.close();
    }
}
