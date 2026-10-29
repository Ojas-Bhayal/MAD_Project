package com.cropdoc.app.di;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bounded thread pools ensuring zero disk, network, or ML work ever blocks the Android UI thread.
 */
public final class AppExecutors {

    private static final int DISK_IO_THREADS = 2;
    private static final int NETWORK_IO_THREADS = 3;

    @NonNull
    private final Executor diskIo;

    @NonNull
    private final Executor networkIo;

    @NonNull
    private final Executor mlInference;

    @NonNull
    private final Executor mainThread;

    public AppExecutors(
            @NonNull Executor diskIo,
            @NonNull Executor networkIo,
            @NonNull Executor mlInference,
            @NonNull Executor mainThread) {
        this.diskIo = Objects.requireNonNull(diskIo, "diskIo");
        this.networkIo = Objects.requireNonNull(networkIo, "networkIo");
        this.mlInference = Objects.requireNonNull(mlInference, "mlInference");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    @NonNull
    public static AppExecutors createDefault() {
        ExecutorService disk = Executors.newFixedThreadPool(DISK_IO_THREADS);
        ExecutorService net = Executors.newFixedThreadPool(NETWORK_IO_THREADS);
        ExecutorService ml = Executors.newSingleThreadExecutor();
        return new AppExecutors(disk, net, ml, new MainThreadExecutor());
    }

    @NonNull
    public Executor diskIo() {
        return diskIo;
    }

    @NonNull
    public Executor networkIo() {
        return networkIo;
    }

    @NonNull
    public Executor mlInference() {
        return mlInference;
    }

    @NonNull
    public Executor mainThread() {
        return mainThread;
    }

    static final class MainThreadExecutor implements Executor {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        MainThreadExecutor() {
        }

        @Override
        public void execute(@NonNull Runnable command) {
            mainHandler.post(command);
        }
    }
}
