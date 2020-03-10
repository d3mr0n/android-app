package fr.gaulupeau.apps.Poche.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import fr.gaulupeau.apps.Poche.service.tasks.SimpleTask;

public class TaskService extends Service {

    public static final String ACTION_SIMPLE_TASK = "action_simple_task";

    public interface Task {
        void run(Context context);
    }

    public class TaskServiceBinder extends Binder {
        public void enqueueTask(Task task) {
            TaskService.this.enqueueTask(task, true);
        }
    }

    /**
     * The time to wait for more tasks before stopping the service (in milliseconds).
     * The setting is not precise.
     */
    private static final int WAIT_TIME = 1000;

    private String tag;

    private Thread taskThread;

    private final Object startIdLock = new Object();
    private volatile int lastStartId;

    private BlockingQueue<Task> taskQueue = new LinkedBlockingQueue<>();

    public static Intent newStartIntent(Context context,
                                        Class<? extends TaskService> serviceClass) {
        return new Intent(context, serviceClass);
    }

    public static Intent newSimpleTaskIntent(Context context,
                                             Class<? extends TaskService> serviceClass,
                                             SimpleTask task) {
        Intent intent = newStartIntent(context, serviceClass);
        intent.setAction(ACTION_SIMPLE_TASK);
        intent.putExtra(SimpleTask.SIMPLE_TASK, task);
        return intent;
    }

    public TaskService(String name) {
        this.tag = name;
    }

    protected int getThreadPriority() {
        return Process.THREAD_PRIORITY_BACKGROUND;
    }

    @Override
    public void onCreate() {
        Log.d(tag, "onCreate()");

        taskThread = new Thread(this::run, "TaskService-taskThread");
        taskThread.start();
    }

    @Override
    public void onDestroy() {
        Log.d(tag, "onDestroy()");

        if (taskThread != null) {
            taskThread.interrupt();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(tag, "onStartCommand()");

        if (ACTION_SIMPLE_TASK.equals(intent.getAction())) {
            Task task = taskFromSimpleTask(SimpleTask.fromIntent(intent));
            if (task != null) {
                enqueueTask(task, false);
            }
        }

        synchronized (startIdLock) {
            lastStartId = startId;
        }

        return START_NOT_STICKY;
    }

    private Task taskFromSimpleTask(SimpleTask simpleTask) {
        if (simpleTask == null) {
            Log.d(tag, "taskFromActionRequest() request is null");
            return null;
        }

        return simpleTask::run;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(tag, "onBind()");

        return new TaskServiceBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(tag, "onUnbind()");
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        Log.d(tag, "onRebind()");
    }

    private void run() {
        Process.setThreadPriority(getThreadPriority());

        while (true) {
            Task task;
            try {
                task = taskQueue.poll(WAIT_TIME, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Log.d(tag, "run() interrupted");
                break;
            }

            if (task != null) {
                try {
                    Log.v(tag, "run() running a task");
                    task.run(this);
                    Log.v(tag, "run() finished a task");
                } catch (Exception e) {
                    Log.e(tag, "run() exception during task execution", e);
                }
            }

            synchronized (startIdLock) {
                if (taskQueue.isEmpty()) {
                    Log.d(tag, "run() no more tasks; notifying that we are ready to stop");
                    readyToStop();
                }
            }
        }
    }

    private void ensureStarted() {
        Log.d(tag, "ensureStarted()");

        startService(newStartIntent(this, getClass()));
    }

    private void readyToStop() {
        Log.d(tag, "readyToStop()");

        if (!stopSelfResult(lastStartId)) {
            Log.d(tag, "readyToStop() startId didn't match");
        }
    }

    private void enqueueTask(Task task, boolean ensureStarted) {
        Log.d(tag, "enqueueTask()");
        Objects.requireNonNull(task, "task is null");

        Log.v(tag, "enqueueTask() enqueueing task");
        taskQueue.add(task);

        if (ensureStarted) {
            Log.v(tag, "enqueueTask() starting service");
            ensureStarted();
            Log.v(tag, "enqueueTask() started service");
        }
    }

}
