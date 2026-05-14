package caeruleusTait.world.preview.backend;

import caeruleusTait.world.preview.backend.worker.WorkBatch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class WorkScheduler {
    private final List<WorkBatch> currentBatches = new ArrayList<>();
    private final List<Future<?>> workerFutures = new ArrayList<>();
    private final List<Future<?>> queueFutures = new ArrayList<>();

    private ExecutorService workerExecutor;
    private ExecutorService queueExecutor;
    private boolean queueIsRunning = false;
    private boolean shouldAbortQueuing = false;

    public synchronized void start(int workerThreads) {
        workerExecutor = Executors.newFixedThreadPool(workerThreads);
        queueExecutor = Executors.newSingleThreadExecutor();
        queueIsRunning = false;
        shouldAbortQueuing = false;
    }

    public synchronized boolean isStarted() {
        return workerExecutor != null && queueExecutor != null;
    }

    public synchronized boolean shouldAbortQueuing() {
        return shouldAbortQueuing;
    }

    public synchronized boolean submitQueue(Runnable queueTask) {
        if (workerExecutor == null || queueExecutor == null) {
            return false;
        }
        drainCompletedWork();

        if (queueIsRunning) {
            shouldAbortQueuing = true;
            return false;
        }

        queueIsRunning = true;
        shouldAbortQueuing = false;
        queueFutures.add(queueExecutor.submit(() -> {
            try {
                queueTask.run();
            } finally {
                synchronized (this) {
                    queueIsRunning = false;
                }
            }
        }));
        return true;
    }

    public void cancelSubmittedBatchesAndWait() {
        final List<Future<?>> toWait;
        synchronized (this) {
            currentBatches.forEach(WorkBatch::cancel);
            currentBatches.clear();
            toWait = new ArrayList<>(workerFutures);
            workerFutures.clear();
        }

        for (Future<?> future : toWait) {
            try {
                future.get();
            } catch (CancellationException ignored) {
                // Expected during screen/world close or when the visible range is replaced.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public synchronized void submitBatches(List<WorkBatch> batches) {
        if (workerExecutor == null || batches.isEmpty()) {
            return;
        }
        drainCompletedWork();

        currentBatches.addAll(batches);
        for (WorkBatch batch : batches) {
            workerFutures.add(workerExecutor.submit(batch::process));
        }
    }

    public synchronized void cancelOutstandingWork() {
        shouldAbortQueuing = true;

        currentBatches.forEach(WorkBatch::cancel);
        currentBatches.clear();

        queueFutures.forEach(f -> f.cancel(true));
        workerFutures.forEach(f -> f.cancel(true));
        queueFutures.clear();
        workerFutures.clear();
    }

    public synchronized ShutdownExecutors shutdownNow() {
        final ExecutorService workers = workerExecutor;
        final ExecutorService queue = queueExecutor;

        cancelOutstandingWork();
        if (workers != null) {
            workers.shutdownNow();
        }
        if (queue != null) {
            queue.shutdownNow();
        }

        workerExecutor = null;
        queueExecutor = null;
        queueIsRunning = false;
        shouldAbortQueuing = false;
        return new ShutdownExecutors(workers, queue);
    }

    private void drainCompletedWork() {
        currentBatches.removeIf(WorkBatch::isDone);
        workerFutures.removeIf(Future::isDone);
        queueFutures.removeIf(Future::isDone);
    }

    public record ShutdownExecutors(ExecutorService workerExecutor, ExecutorService queueExecutor) {
    }
}
