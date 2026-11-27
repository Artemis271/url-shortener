package shortener.core;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public final class LinkService {
    public enum Reason { LIMIT, EXPIRED, DELETED }
    public record Link(String code, UUID owner, URI target, Instant expires, int limit, int visits) {}
    public record Closed(String code, UUID owner, Reason reason, Instant closedAt, boolean seen) {}
    public record State(Set<UUID> users, List<Link> links, List<Closed> closed, Set<String> issuedCodes) {}
    public record Visit(URI target, Reason reason) {}

    private final Clock clock;
    private final Duration ttl;
    private final Duration noticeRetention;
    private final int maxLimit;
    private final Supplier<String> codes;
    private final Set<UUID> users;
    private final Set<String> issuedCodes;
    private final Map<String, Link> links = new LinkedHashMap<>();
    private final Map<String, Closed> closed = new LinkedHashMap<>();

    public LinkService(Clock clock, Duration ttl, Duration noticeRetention, int maxLimit,
            Supplier<String> codes, State state) {
        this.clock = clock;
        this.ttl = ttl;
        this.noticeRetention = noticeRetention;
        this.maxLimit = maxLimit;
        this.codes = codes;
        users = new HashSet<>(state.users());
        issuedCodes = new HashSet<>(state.issuedCodes());
        for (Link link : state.links()) {
            links.put(link.code(), link);
            issuedCodes.add(link.code());
        }
        for (Closed item : state.closed()) {
            closed.put(item.code(), item);
            issuedCodes.add(item.code());
        }
    }

    public static State emptyState() {
        return new State(Set.of(), List.of(), List.of(), Set.of());
    }

    public static URI target(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getRawUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Введите полный URL с http:// или https:// без логина и пароля.");
        }
        return uri;
    }

    public synchronized Link create(UUID owner, String url, int limit) {
        URI destination = target(url);
        checkLimit(limit);
        if (owner == null) {
            do {
                owner = UUID.randomUUID();
            } while (!users.add(owner));
        } else {
            requireUser(owner);
        }

        String code;
        do {
            code = codes.get();
        } while (!issuedCodes.add(code));
        Link link = new Link(code, owner, destination, clock.instant().plus(ttl), limit, 0);
        links.put(code, link);
        return link;
    }

    public synchronized void requireUser(UUID owner) {
        if (!users.contains(owner)) {
            throw new IllegalArgumentException("UUID пользователя не найден.");
        }
    }

    public synchronized List<Link> links(UUID owner) {
        requireUser(owner);
        sweep();
        return links.values().stream().filter(link -> link.owner().equals(owner)).toList();
    }

    public synchronized Link edit(UUID owner, String code, String url, Integer limit) {
        Link current = owned(owner, code);
        URI destination = url == null ? current.target() : target(url);
        int updatedLimit = limit == null ? current.limit() : limit;
        if (limit != null) {
            checkLimit(updatedLimit);
        }
        if (updatedLimit <= current.visits()) {
            throw new IllegalArgumentException("Новый лимит должен быть больше числа уже учтённых переходов.");
        }
        Link updated = new Link(code, owner, destination, current.expires(), updatedLimit, current.visits());
        links.put(code, updated);
        return updated;
    }

    public synchronized void delete(UUID owner, String code) {
        Link link = owned(owner, code);
        close(link, Reason.DELETED, clock.instant());
    }

    public synchronized Visit status(String code) {
        sweep();
        Link link = links.get(code);
        if (link != null) {
            return new Visit(link.target(), null);
        }
        Closed item = closed.get(code);
        return new Visit(null, item == null ? null : item.reason());
    }

    public synchronized Visit visit(String code) {
        Visit status = status(code);
        if (status.target() == null) {
            return status;
        }
        Link link = links.get(code);
        int visits = link.visits() + 1;
        if (visits == link.limit()) {
            close(link, Reason.LIMIT, clock.instant());
        } else {
            links.put(code, new Link(code, link.owner(), link.target(), link.expires(), link.limit(), visits));
        }
        return status;
    }

    public synchronized List<Closed> notices(UUID owner) {
        requireUser(owner);
        sweep();
        List<Closed> unread = new ArrayList<>();
        closed.replaceAll((code, item) -> {
            if (item.seen() || !item.owner().equals(owner)) {
                return item;
            }
            unread.add(item);
            return new Closed(code, owner, item.reason(), item.closedAt(), true);
        });
        return unread;
    }

    public synchronized void sweep() {
        Instant now = clock.instant();
        List<Link> expired = links.values().stream().filter(link -> !link.expires().isAfter(now)).toList();
        expired.forEach(link -> close(link, Reason.EXPIRED, link.expires()));
        closed.values().removeIf(item -> !item.closedAt().plus(noticeRetention).isAfter(now));
    }

    public synchronized State snapshot() {
        sweep();
        return new State(Set.copyOf(users), List.copyOf(links.values()), List.copyOf(closed.values()), Set.copyOf(issuedCodes));
    }

    private Link owned(UUID owner, String code) {
        requireUser(owner);
        sweep();
        Link link = links.get(code);
        if (link == null || !link.owner().equals(owner)) {
            throw new IllegalArgumentException("Активная ссылка не найдена у этого пользователя.");
        }
        return link;
    }

    private void checkLimit(int limit) {
        if (limit < 1 || limit > maxLimit) {
            throw new IllegalArgumentException("Лимит переходов должен быть от 1 до " + maxLimit + ".");
        }
    }

    private void close(Link link, Reason reason, Instant now) {
        links.remove(link.code());
        closed.put(link.code(), new Closed(link.code(), link.owner(), reason, now, reason == Reason.DELETED));
    }
}
