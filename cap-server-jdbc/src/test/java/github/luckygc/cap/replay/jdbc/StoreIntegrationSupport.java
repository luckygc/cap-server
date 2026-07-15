package github.luckygc.cap.replay.jdbc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class StoreIntegrationSupport {
    private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(30);

    private StoreIntegrationSupport() {}

    static List<Boolean> runConcurrently(int concurrency, Callable<Boolean> operation) {
        return runConcurrently(
                concurrency,
                CONCURRENCY_TIMEOUT,
                Executors.newFixedThreadPool(concurrency),
                operation);
    }

    static List<Boolean> runConcurrently(
            int concurrency,
            Duration timeout,
            ExecutorService executor,
            Callable<Boolean> operation) {
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        List<Boolean> results = new ArrayList<>();
        IllegalStateException failure = null;
        IllegalStateException cleanupFailure = null;
        boolean interrupted = false;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            for (int index = 0; index < concurrency; index++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return operation.call();
                                }));
            }
            if (!ready.await(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                failure = concurrencyFailure("concurrency_timeout");
            } else {
                start.countDown();
                for (Future<Boolean> future : futures) {
                    long remaining = remainingNanos(deadline);
                    if (remaining == 0) {
                        failure = concurrencyFailure("concurrency_timeout");
                        break;
                    }
                    results.add(future.get(remaining, TimeUnit.NANOSECONDS));
                }
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            failure = concurrencyFailure("concurrency_interrupted");
        } catch (ExecutionException | RuntimeException exception) {
            failure = concurrencyFailure("concurrency_failure");
        } catch (TimeoutException exception) {
            failure = concurrencyFailure("concurrency_timeout");
        } finally {
            start.countDown();
            for (Future<Boolean> future : futures) {
                future.cancel(true);
            }
            executor.shutdownNow();
            long cleanupDeadline = System.nanoTime() + timeout.toNanos();
            boolean terminated = false;
            while (!terminated) {
                long remaining = remainingNanos(cleanupDeadline);
                if (remaining == 0) {
                    break;
                }
                try {
                    terminated = executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                    executor.shutdownNow();
                }
            }
            if (!terminated) {
                cleanupFailure = concurrencyFailure("executor_not_terminated");
            } else if (interrupted) {
                cleanupFailure = concurrencyFailure("concurrency_interrupted");
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
        if (failure != null) {
            throw failure;
        }
        return results;
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static IllegalStateException concurrencyFailure(String category) {
        return new IllegalStateException("store-integration category=" + category);
    }
}
