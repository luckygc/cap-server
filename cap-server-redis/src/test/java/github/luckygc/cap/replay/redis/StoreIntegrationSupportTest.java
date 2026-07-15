package github.luckygc.cap.replay.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Redis 存储集成并发辅助")
class StoreIntegrationSupportTest {
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("32 个任务就绪后同时起跑")
    void startsAllThirtyTwoTasksTogether() throws Exception {
        TrackingExecutor executor = trackingExecutor(32);
        CountDownLatch entered = new CountDownLatch(32);

        List<Boolean> results =
                StoreIntegrationSupport.runConcurrently(
                        32,
                        Duration.ofSeconds(2),
                        executor,
                        () -> {
                            entered.countDown();
                            return entered.await(1, TimeUnit.SECONDS);
                        });

        assertThat(results).hasSize(32).containsOnly(true);
        assertCleanedUp(executor);
    }

    @Test
    @DisplayName("任务未全部就绪时有界失败并清理")
    void boundsReadinessWaitAndCleansUp() {
        TrackingExecutor executor = trackingExecutor(1);

        assertThatThrownBy(
                        () ->
                                StoreIntegrationSupport.runConcurrently(
                                        2, SHORT_TIMEOUT, executor, () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("store-integration category=concurrency_timeout")
                .hasNoCause();

        assertCleanedUp(executor);
    }

    @Test
    @DisplayName("任务阻塞时按总 deadline 失败并清理")
    void boundsFutureWaitAndCleansUp() {
        TrackingExecutor executor = trackingExecutor(2);
        CountDownLatch blocker = new CountDownLatch(1);

        assertThatThrownBy(
                        () ->
                                StoreIntegrationSupport.runConcurrently(
                                        2,
                                        SHORT_TIMEOUT,
                                        executor,
                                        () -> {
                                            blocker.await();
                                            return true;
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("store-integration category=concurrency_timeout")
                .hasNoCause();

        assertCleanedUp(executor);
    }

    @Test
    @DisplayName("任务异常时固定分类并清理")
    void classifiesTaskFailureAndCleansUp() {
        TrackingExecutor executor = trackingExecutor(2);

        assertThatThrownBy(
                        () ->
                                StoreIntegrationSupport.runConcurrently(
                                        2,
                                        SHORT_TIMEOUT,
                                        executor,
                                        () -> {
                                            throw new IllegalArgumentException(
                                                    "sensitive-signature");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("store-integration category=concurrency_failure")
                .hasNoCause();

        assertCleanedUp(executor);
    }

    @Test
    @DisplayName("执行器不能终止时明确失败")
    void failsClearlyWhenExecutorCannotTerminate() {
        AtomicBoolean release = new AtomicBoolean();
        TrackingExecutor executor = trackingDaemonExecutor(1);

        try {
            assertThatThrownBy(
                            () ->
                                    StoreIntegrationSupport.runConcurrently(
                                            1,
                                            SHORT_TIMEOUT,
                                            executor,
                                            () -> {
                                                while (!release.get()) {
                                                    Thread.interrupted();
                                                }
                                                return true;
                                            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("store-integration category=executor_not_terminated")
                    .hasNoCause();
        } finally {
            release.set(true);
        }

        assertCleanedUp(executor);
    }

    @Test
    @DisplayName("中断时恢复线程中断标记")
    void restoresInterruptFlag() {
        TrackingExecutor executor = trackingExecutor(1);
        Thread.currentThread().interrupt();

        assertThatThrownBy(
                        () ->
                                StoreIntegrationSupport.runConcurrently(
                                        2, SHORT_TIMEOUT, executor, () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("store-integration category=concurrency_interrupted")
                .hasNoCause();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(executor.shutdownNowCalled).isTrue();
        assertThat(executor.awaitTerminationCalled).isTrue();
        assertThat(executor.tasks).allMatch(task -> task.cancelAttempted.get());
    }

    private static TrackingExecutor trackingExecutor(int threads) {
        return new TrackingExecutor(Executors.newFixedThreadPool(threads));
    }

    private static TrackingExecutor trackingDaemonExecutor(int threads) {
        return new TrackingExecutor(
                Executors.newFixedThreadPool(
                        threads,
                        task -> {
                            Thread thread = new Thread(task, "store-integration-test");
                            thread.setDaemon(true);
                            return thread;
                        }));
    }

    private static void assertCleanedUp(TrackingExecutor executor) {
        assertThat(executor.shutdownNowCalled).isTrue();
        assertThat(executor.awaitTerminationCalled).isTrue();
        assertThat(executor.tasks).isNotEmpty().allMatch(task -> task.cancelAttempted.get());
    }

    private static final class TrackingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final List<TrackingFutureTask<?>> tasks = new ArrayList<>();
        private boolean shutdownNowCalled;
        private boolean awaitTerminationCalled;

        private TrackingExecutor(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        protected <T> java.util.concurrent.RunnableFuture<T> newTaskFor(Callable<T> callable) {
            TrackingFutureTask<T> task = new TrackingFutureTask<>(callable);
            tasks.add(task);
            return task;
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownNowCalled = true;
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            awaitTerminationCalled = true;
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }
    }

    private static final class TrackingFutureTask<T> extends FutureTask<T> {
        private final AtomicBoolean cancelAttempted = new AtomicBoolean();

        private TrackingFutureTask(Callable<T> callable) {
            super(callable);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelAttempted.set(true);
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
