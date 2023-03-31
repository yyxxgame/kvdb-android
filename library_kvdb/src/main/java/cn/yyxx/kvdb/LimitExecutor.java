package cn.yyxx.kvdb;

import java.util.concurrent.Executor;

/**
 * @author #Suyghur.
 * Created on 2023/3/31
 */
class LimitExecutor implements Executor {
    private Runnable mActive;

    private Runnable mWaiting;

    @Override
    public synchronized void execute(Runnable command) {
        if (mActive == null) {
            mActive = wrapTask(command);
            Config.getExecutor().execute(mActive);
        } else {
            if (mWaiting == null) {
                mWaiting = wrapTask(command);
            }
        }
    }

    private Runnable wrapTask(Runnable command) {
        return () -> {
            try {
                command.run();
            } finally {
                scheduleNext();
            }
        };
    }

    private synchronized void scheduleNext() {
        mActive = mWaiting;
        mWaiting = null;
        if (mActive != null) {
            Config.getExecutor().execute(mActive);
        }
    }
}
