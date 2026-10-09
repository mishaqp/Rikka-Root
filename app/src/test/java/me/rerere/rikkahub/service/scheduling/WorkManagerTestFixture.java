package me.rerere.rikkahub.service.scheduling;

import android.content.Context;
import androidx.work.Configuration;
import androidx.work.impl.WorkManagerImpl;
import androidx.work.impl.WorkManagerImplExtKt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Access the public JVM factory without exposing Kotlin-internal test construction to production. */
public final class WorkManagerTestFixture {
    private WorkManagerTestFixture() {}

    public static WorkManagerImpl create(Context context, Configuration configuration) {
        WorkManagerImpl manager = WorkManagerImplExtKt.createWorkManager(context, configuration);
        drainSerialTasks(manager);
        return manager;
    }

    public static void close(WorkManagerImpl manager) {
        // ForceStopRunnable is a serial executor task, outside WorkManager's coroutine scope.
        // Joining that scope alone can close Room while startup reconciliation is still running.
        drainSerialTasks(manager);
        WorkManagerImplExtKt.close(manager);
    }

    private static void drainSerialTasks(WorkManagerImpl manager) {
        CountDownLatch barrier = new CountDownLatch(1);
        manager.getWorkTaskExecutor().getSerialTaskExecutor().execute(barrier::countDown);
        try {
            if (!barrier.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("WorkManager serial executor did not finish within 5 seconds");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for WorkManager serial executor", interrupted);
        }
    }
}
