import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight dependency-free load test for the helpdesk read path.
 *
 * Usage:
 *   java tools/LoadTest.java http://127.0.0.1:8092 acme agent@acme.test agent123 32 500
 */
public final class LoadTest {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    private LoadTest() {
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = arg(args, 0, "http://127.0.0.1:8092");
        String tenant = arg(args, 1, "acme");
        String email = arg(args, 2, "agent@acme.test");
        String password = arg(args, 3, "agent123");
        int concurrency = Integer.parseInt(arg(args, 4, "32"));
        int requests = Integer.parseInt(arg(args, 5, "500"));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        String token = login(client, baseUrl, tenant, email, password);

        for (int i = 0; i < 20; i++) {
            getTickets(client, baseUrl, token);
        }

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < requests; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    long began = System.nanoTime();
                    int status = getTickets(client, baseUrl, token);
                    long elapsedMs = (System.nanoTime() - began) / 1_000_000;
                    if (status >= 400) {
                        errors.incrementAndGet();
                    } else {
                        latencies.add(elapsedMs);
                    }
                } catch (Exception ex) {
                    errors.incrementAndGet();
                }
                return null;
            }));
        }

        long began = System.nanoTime();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        long totalMs = (System.nanoTime() - began) / 1_000_000;
        pool.shutdown();

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        System.out.printf(
                "requests=%d concurrency=%d ok=%d errors=%d totalMs=%d rps=%.1f "
                        + "p50=%dms p95=%dms p99=%dms min=%dms max=%dms%n",
                requests, concurrency, sorted.size(), errors.get(), totalMs,
                requests / Math.max(0.001, totalMs / 1000.0),
                percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
                sorted.isEmpty() ? -1 : sorted.get(0),
                sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1));
    }

    private static int getTickets(HttpClient client, String baseUrl, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tickets"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String login(
            HttpClient client, String baseUrl, String tenant, String email, String password
    ) throws Exception {
        String body = String.format(
                "{\"tenantSlug\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                tenant, email, password);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("login failed: HTTP " + response.statusCode());
        }
        Matcher matcher = TOKEN.matcher(response.body());
        if (!matcher.find()) {
            throw new IllegalStateException("login response has no token");
        }
        return matcher.group(1);
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return -1;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index && !args[index].isBlank() ? args[index] : fallback;
    }
}
