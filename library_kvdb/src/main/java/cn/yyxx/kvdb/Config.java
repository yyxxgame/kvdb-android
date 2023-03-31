package cn.yyxx.kvdb;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author #Suyghur.
 * Created on 2023/3/31
 */
public final class Config {
    static ILogger sLogger;

    static volatile Executor sExecutor;

    static int internalLimit = 8192;

    private Config() {
    }

    public static void setInternalLimit(int limit) {
        if (limit >= 2048 && limit <= 0xFFFF) {
            internalLimit = limit;
        }
    }

    public static void setLogger(ILogger logger) {
        sLogger = logger;
    }

    /**
     * It's highly recommended setting your own Executor for reusing threads in common thread pool.
     *
     * @param executor The executor for loading or writing.
     */
    public static void setExecutor(Executor executor) {
        if (executor != null) {
            sExecutor = executor;
        }
    }

    static Executor getExecutor() {
        if (sExecutor == null) {
            synchronized (Config.class) {
                if (sExecutor == null) {
                    ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
                    executor.allowCoreThreadTimeOut(true);
                    sExecutor = executor;
                }
            }
        }
        return sExecutor;
    }
}
