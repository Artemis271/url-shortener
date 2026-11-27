package shortener.infra;

import shortener.core.LinkService;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class StateFile {
    private static final String VERSION = "links-v1";
    private final Path path;

    public StateFile(Path path) {
        this.path = path.toAbsolutePath();
    }

    public LinkService.State load() throws IOException {
        List<String> rows;
        try {
            rows = Files.readAllLines(path);
        } catch (NoSuchFileException absent) {
            return LinkService.emptyState();
        }
        var users = new HashSet<UUID>();
        var issued = new HashSet<String>();
        var links = new ArrayList<LinkService.Link>();
        var closed = new ArrayList<LinkService.Closed>();
        try {
            if (rows.isEmpty() || !VERSION.equals(rows.getFirst())) {
                throw new IllegalArgumentException("Неизвестный формат.");
            }
            for (String row : rows.subList(1, rows.size())) {
                String[] f = row.split("\t", -1);
                switch (f[0]) {
                    case "U" -> users.add(UUID.fromString(f[1]));
                    case "I" -> issued.add(f[1]);
                    case "L" -> links.add(new LinkService.Link(f[1], UUID.fromString(f[2]), URI.create(f[3]),
                        Instant.parse(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6])));
                    case "C" -> closed.add(new LinkService.Closed(f[1], UUID.fromString(f[2]),
                        LinkService.Reason.valueOf(f[3]), Instant.parse(f[4]), Boolean.parseBoolean(f[5])));
                    default -> throw new IllegalArgumentException("Неизвестная запись.");
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("Повреждён файл состояния " + path + ".", error);
        }
        return new LinkService.State(users, links, closed, issued);
    }

    public void save(LinkService.State state) throws IOException {
        StringBuilder content = new StringBuilder(VERSION).append('\n');
        state.users().stream().sorted().forEach(user -> row(content, "U", user));
        state.issuedCodes().stream().sorted().forEach(code -> row(content, "I", code));
        state.links().forEach(link -> row(content, "L", link.code(), link.owner(), link.target().toASCIIString(),
            link.expires(), link.limit(), link.visits()));
        state.closed().forEach(item -> row(content, "C", item.code(), item.owner(), item.reason(),
            item.closedAt(), item.seen()));
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "links-", ".tmp");
        try {
            Files.writeString(temporary, content);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void row(StringBuilder result, Object... fields) {
        StringJoiner row = new StringJoiner("\t");
        for (Object field : fields) {
            row.add(field.toString());
        }
        result.append(row).append('\n');
    }
}
