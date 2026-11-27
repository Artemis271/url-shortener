package shortener.ui;

import shortener.core.LinkService;
import shortener.infra.RedirectServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.util.UUID;

public final class ConsoleApp {
    @FunctionalInterface
    public interface Browser {
        void open(URI uri) throws IOException;
    }

    private static final String HELP = """
        short <URL> [лимит]     Сократить URL
        open <код или ссылка>   Открыть в браузере (можно просто вставить короткую ссылку)
        list                    Показать свои активные ссылки
        limit <код> <лимит>      Изменить лимит
        target <код> <URL>       Изменить исходный URL
        delete <код>            Удалить ссылку
        id                      Показать UUID текущего пользователя
        use <UUID>              Переключиться на существующего пользователя
        new                     Следующая команда short создаст нового пользователя
        help                    Показать справку
        exit                    Сохранить данные и завершить работу

        Пример: short https://example.com/ 2
        """;

    private final LinkService links;
    private final String base;
    private final int defaultLimit;
    private final Browser browser;
    private final BufferedReader input;
    private final PrintStream output;
    private UUID user;

    public ConsoleApp(LinkService links, String base, int defaultLimit, Browser browser,
            BufferedReader input, PrintStream output) {
        this.links = links;
        this.base = base;
        this.defaultLimit = defaultLimit;
        this.browser = browser;
        this.input = input;
        this.output = output;
    }

    public void run() throws IOException {
        output.println("Сервис коротких ссылок. Список команд: help");
        while (true) {
            notices(false);
            output.print("shortener> ");
            output.flush();
            String line = input.readLine();
            if (line == null || line.strip().equals("exit")) {
                notices(false);
                return;
            }
            try {
                if (!line.isBlank()) {
                    execute(line.strip().split("\\s+"));
                }
            } catch (NumberFormatException error) {
                output.println("Ошибка: лимит должен быть целым числом.");
            } catch (IllegalArgumentException | IOException error) {
                output.println("Ошибка: " + error.getMessage());
            }
        }
    }

    private synchronized void execute(String[] parts) throws IOException {
        switch (parts[0]) {
            case "help" -> output.print(HELP);
            case "short" -> {
                if (parts.length < 2 || parts.length > 3) {
                    throw new IllegalArgumentException("Использование: short <URL> [лимит]");
                }
                var link = links.create(user, parts[1], parts.length == 3 ? Integer.parseInt(parts[2]) : defaultLimit);
                if (user == null) {
                    user = link.owner();
                    output.println("Ваш UUID: " + user + ". Сохраните его для управления ссылками.");
                }
                show(link);
            }
            case "id" -> output.println("Ваш UUID: " + owner());
            case "use" -> {
                UUID requested;
                try {
                    requested = UUID.fromString(argument(parts, 2, "use <UUID>"));
                    if (!requested.toString().equalsIgnoreCase(parts[1])) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("Использование: use <UUID пользователя>.");
                }
                links.requireUser(requested);
                user = requested;
                output.println("Текущий пользователь: " + user);
            }
            case "new" -> {
                user = null;
                output.println("Новый UUID появится при создании ссылки.");
            }
            case "list" -> {
                var owned = links.links(owner());
                if (owned.isEmpty()) {
                    output.println("Активных ссылок нет.");
                }
                owned.forEach(this::show);
            }
            case "limit" -> {
                String code = code(argument(parts, 3, "limit <код> <лимит>"));
                show(links.edit(owner(), code, null, Integer.parseInt(parts[2])));
            }
            case "target" -> {
                String code = code(argument(parts, 3, "target <код> <URL>"));
                show(links.edit(owner(), code, parts[2], null));
            }
            case "delete" -> {
                links.delete(owner(), code(argument(parts, 2, "delete <код>")));
                output.println("Ссылка удалена.");
            }
            case "open" -> open(argument(parts, 2, "open <код или ссылка>"));
            default -> {
                if (parts.length == 1 && (parts[0].startsWith(base) || parts[0].matches("[A-Za-z0-9_-]{12}"))) {
                    open(parts[0]);
                } else {
                    output.println("Неизвестная команда. Введите help.");
                }
            }
        }
    }

    private void open(String value) throws IOException {
        String code = code(value);
        var status = links.status(code);
        if (status.target() == null) {
            output.println(RedirectServer.unavailable(status.reason()));
            return;
        }
        browser.open(URI.create(base + code));
        output.println("Ссылка передана браузеру: " + base + code);
    }

    private void show(LinkService.Link link) {
        output.printf("%s → %s | переходы: %d/%d | истекает: %s%n",
            base + link.code(), link.target(), link.visits(), link.limit(), link.expires());
    }

    public synchronized void showPendingNotices() {
        if (notices(true)) {
            output.print("shortener> ");
            output.flush();
        }
    }

    private synchronized boolean notices(boolean redraw) {
        if (user != null) {
            var pending = links.notices(user);
            if (redraw && !pending.isEmpty()) {
                output.println();
            }
            pending.forEach(item ->
                output.println("Уведомление " + base + item.code() + ": " + RedirectServer.unavailable(item.reason())));
            return !pending.isEmpty();
        }
        return false;
    }

    private UUID owner() {
        if (user == null) {
            throw new IllegalArgumentException("Создайте ссылку или выберите пользователя командой use.");
        }
        return user;
    }

    private String code(String input) {
        String value = input.startsWith(base) ? input.substring(base.length()) : input;
        if (!value.matches("[A-Za-z0-9_-]{12}")) {
            throw new IllegalArgumentException("Укажите код или короткую ссылку этого сервиса.");
        }
        return value;
    }

    private static String argument(String[] parts, int length, String usage) {
        if (parts.length != length) {
            throw new IllegalArgumentException("Использование: " + usage);
        }
        return parts[1];
    }
}
