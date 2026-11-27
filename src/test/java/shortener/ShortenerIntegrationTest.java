package shortener;

import shortener.core.LinkService;
import shortener.infra.Config;
import shortener.infra.RedirectServer;
import shortener.infra.StateFile;
import shortener.ui.ConsoleApp;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShortenerIntegrationTest {
    private static final String URL = "https://example.com/test";
    private final AtomicInteger sequence = new AtomicInteger();
    @TempDir Path temporary;

    private LinkService service(LinkService.State state) {
        return new LinkService(Clock.systemUTC(), Duration.ofDays(1), Duration.ofDays(7), 100,
            () -> "Abcdefghijk" + sequence.incrementAndGet(), state);
    }

    @Test void redirectsThroughRealHttpServerAndStopsAtLimit() throws IOException, InterruptedException {
        var links = service(LinkService.emptyState());
        var link = links.create(null, URL, 1);
        try (var server = new RedirectServer(0, links); var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(server.base() + link.code())).build();
            var first = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(302, first.statusCode());
            assertEquals(URL, first.headers().firstValue("Location").orElseThrow());
            assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
            var second = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(410, second.statusCode());
            assertTrue(second.body().contains("Лимит переходов исчерпан"));
        }
    }

    @Test void headDoesNotConsumeVisit() throws IOException, InterruptedException {
        var links = service(LinkService.emptyState());
        var link = links.create(null, URL, 1);
        try (var server = new RedirectServer(0, links); var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(server.base() + link.code()))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(405, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(0, links.links(link.owner()).getFirst().visits());
        }
    }

    @Test void persistsOwnerVisitsAndNotifications() throws IOException {
        var links = service(LinkService.emptyState());
        var first = links.create(null, URL, 2);
        var second = links.create(first.owner(), URL, 1);
        links.visit(first.code());
        links.visit(second.code());
        var file = new StateFile(temporary.resolve("data/links.db"));
        file.save(links.snapshot());

        var restored = service(file.load());
        assertEquals(1, restored.links(first.owner()).getFirst().visits());
        assertEquals(LinkService.Reason.LIMIT, restored.notices(first.owner()).getFirst().reason());
        assertEquals(2, restored.snapshot().issuedCodes().size());
    }

    @Test void cliCreatesSwitchesAndOpensShortLink() throws IOException {
        var links = service(LinkService.emptyState());
        var existing = links.create(null, URL, 2);
        String base = "http://127.0.0.1:8080/";
        String input = "use " + existing.owner() + "\n" + base + existing.code()
            + "\nnew\nshort https://example.org/\nlist\nexit\n";
        var output = new ByteArrayOutputStream();
        var opened = new ArrayList<URI>();
        new ConsoleApp(links, base, 3, opened::add, new BufferedReader(new StringReader(input)),
            new PrintStream(output, true, StandardCharsets.UTF_8)).run();

        assertEquals(URI.create(base + existing.code()), opened.getFirst());
        assertEquals(0, links.links(existing.owner()).getFirst().visits());
        assertEquals(2, links.snapshot().users().size());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("переходы: 0/3"));
    }

    @Test void readsAndValidatesExternalConfiguration() throws IOException {
        var path = temporary.resolve("settings.properties");
        String properties = """
            port=8000
            ttl=PT1H
            cleanupInterval=PT0.1S
            noticeRetention=P2D
            defaultLimit=2
            maxLimit=9
            """;
        Files.writeString(path, properties);
        var config = Config.read(path);
        assertEquals(Duration.ofHours(1), config.ttl());
        assertEquals(2, config.defaultLimit());
        Files.writeString(path, properties.replace("maxLimit=9", "maxLimit=1"));
        assertThrows(IllegalArgumentException.class, () -> Config.read(path));
    }
}
