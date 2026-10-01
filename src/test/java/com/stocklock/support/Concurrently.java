package com.stocklock.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/**
 * Starts {@code n} tasks on separate (virtual) threads and releases them at the same instant via a
 * latch, maximising the overlap of the requests under test.
 */
public final class Concurrently {

    private Concurrently() {
    }

    public static <T> List<T> run(int n, IntFunction<T> task) {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index);
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>(n);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("Concurrent task failed", e);
        }
    }
}
