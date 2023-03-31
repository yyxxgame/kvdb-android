package cn.yyxx.kvdb;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author #Suyghur.
 * Created on 2023/3/31
 */
public class TagExecutor {

    private static final Set<String> SCHEDULED_TAGS = new HashSet<>();
    private static final Map<String, Runnable> WAITING_TASKS = new HashMap<>();

    public synchronized void execute(String tag, Runnable r) {
        if (r == null) {
            return;
        }
        if (!SCHEDULED_TAGS.contains(tag)) {
            SCHEDULED_TAGS.add(tag);
            start(tag, r);
        } else {
            WAITING_TASKS.put(tag, r);
        }
    }

    private void start(String tag, Runnable r) {
        Config.getExecutor().execute(() -> {
            try {
                r.run();
            } finally {
                scheduleNext(tag);
            }
        });
    }

    private synchronized void scheduleNext(String tag) {
        Runnable r = WAITING_TASKS.remove(tag);
        if (r != null) {
            start(tag, r);
        } else {
            SCHEDULED_TAGS.remove(tag);
        }
    }
}
