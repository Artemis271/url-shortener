package shortener.ui;

import shortener.core.LinkService;
import shortener.infra.Config;
import shortener.infra.RedirectServer;
import shortener.infra.StateFile;
import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            if (args.length > 2) {
                throw new IllegalArgumentException("Использование: java -jar url-shortener.jar [config.properties] [папка данных]");
            }
            Path config = Path.of(args.length > 0 ? args[0] : "config.properties");
            Path data = Path.of(args.length > 1 ? args[1] : "data");
            run(Config.read(config), data);
        } catch (IOException | IllegalArgumentException | DateTimeParseException error) {
            System.err.println("Ошибка запуска или сохранения: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void run(Config config, Path data) throws IOException {
        Files.createDirectories(data);
        try (var channel = FileChannel.open(data.resolve("links.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) {
                throw new IOException("Папка данных уже используется другим экземпляром.");
            }
            var state = new StateFile(data.resolve("links.db"));
            var random = new SecureRandom();
            var links = new LinkService(Clock.systemUTC(), config.ttl(), config.noticeRetention(), config.maxLimit(), () -> {
                byte[] bytes = new byte[9];
                random.nextBytes(bytes);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            }, state.load());
            try {
                try (var server = new RedirectServer(config.port(), links);
                     var scheduler = Executors.newSingleThreadScheduledExecutor();
                     var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    long interval = config.cleanupInterval().toMillis();
                    var console = new ConsoleApp(links, server.base(), config.defaultLimit(), Main::openBrowser, input, System.out);
                    var cleanup = scheduler.scheduleWithFixedDelay(() -> {
                        links.sweep();
                        console.showPendingNotices();
                    }, 0, interval, TimeUnit.MILLISECONDS);
                    try {
                        console.run();
                    } finally {
                        cleanup.cancel(false);
                    }
                }
            } finally {
                state.save(links.snapshot());
            }
        }
    }

    private static void openBrowser(URI uri) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("Браузер недоступен. Откройте вручную " + uri);
        }
        Desktop.getDesktop().browse(uri);
    }
}
