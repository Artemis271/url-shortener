package shortener.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record Config(int port, Duration ttl, Duration cleanupInterval, Duration noticeRetention,
        int defaultLimit, int maxLimit) {

    public static Config read(Path path) throws IOException {
        Properties values = new Properties();
        try (var input = Files.newBufferedReader(path)) {
            values.load(input);
        }
        int port = integer(values, "port");
        int defaultLimit = integer(values, "defaultLimit");
        int maxLimit = integer(values, "maxLimit");
        if (port > 65535 || defaultLimit > maxLimit) {
            throw new IllegalArgumentException("Проверьте port и соотношение defaultLimit/maxLimit.");
        }
        return new Config(port, duration(values, "ttl"), duration(values, "cleanupInterval"),
            duration(values, "noticeRetention"), defaultLimit, maxLimit);
    }

    private static int integer(Properties values, String name) {
        int value = Integer.parseInt(values.getProperty(name, "0").strip());
        if (value < 1) {
            throw new IllegalArgumentException(name + " должен быть положительным числом.");
        }
        return value;
    }

    private static Duration duration(Properties values, String name) {
        Duration duration = Duration.parse(values.getProperty(name, "").strip());
        if (duration.compareTo(Duration.ofMillis(1)) < 0 || duration.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException(name + " должен быть от 1 мс до 365 дней.");
        }
        return duration;
    }
}
