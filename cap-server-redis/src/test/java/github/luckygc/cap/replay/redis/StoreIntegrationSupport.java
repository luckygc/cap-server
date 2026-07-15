package github.luckygc.cap.replay.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class StoreIntegrationSupport {
    private StoreIntegrationSupport() {}

    static List<Boolean> runConcurrently(int concurrency, Callable<Boolean> operation)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return operation.call();
                                }));
            }
            ready.await();
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    static IllegalStateException dockerUnavailable() {
        return new IllegalStateException(
                "store-integration category=docker_unavailable; start Docker and rerun "
                        + "mise exec maven -- mvn -Pstore-integration verify");
    }
}
