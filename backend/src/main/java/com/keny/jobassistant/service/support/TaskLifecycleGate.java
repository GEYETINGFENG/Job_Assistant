package com.keny.jobassistant.service.support;

import lombok.extern.slf4j.Slf4j;

/**
 * 后台 worker 的优雅停机闸门，由各个 worker 的 SmartLifecycle 实现委托。
 *
 * 收到 SIGTERM 后：立即停止领取新任务；手上的任务继续跑完；最后一个任务结束时才回调 Spring 继续关闭。
 * 接单开关、处理中任务数和等待中的回调必须在同一把锁里读写，避免停机信号和领取任务之间的竞争。
 */
@Slf4j
public class TaskLifecycleGate {

    private final String workerName;
    private final Object lock = new Object();
    private boolean acceptingTasks = true;
    private int inFlightTasks;
    private Runnable stopCallback;
    private volatile boolean running;

    public TaskLifecycleGate(String workerName) {
        this.workerName = workerName;
    }

    public void start() {
        synchronized (lock) {
            acceptingTasks = true;
            running = true;
        }
    }

    public void stop() {
        synchronized (lock) {
            acceptingTasks = false;
            running = false;
        }
    }

    /**
     * 停止接单；若仍有任务在处理，等最后一个任务结束后再回调，让 Spring 在此之前不销毁数据源等依赖。
     */
    public void stop(Runnable callback) {
        synchronized (lock) {
            acceptingTasks = false;
            running = false;
            if (inFlightTasks > 0) {
                log.info("{} 收到停机信号，停止领取新任务，等待处理中任务完成，inFlight={}", workerName, inFlightTasks);
                stopCallback = callback;
                return;
            }
        }
        log.info("{} 收到停机信号，没有处理中任务，立即停止", workerName);
        callback.run();
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 领取任务前登记；登记和检查开关在同一把锁内，保证停机后不会再有新任务开始。
     *
     * @return false 表示已经停机，不能再领取
     */
    public boolean tryBeginTask() {
        synchronized (lock) {
            if (!acceptingTasks) {
                return false;
            }
            inFlightTasks++;
            return true;
        }
    }

    /**
     * 必须与成功的 tryBeginTask 成对调用（放在 finally 中）。
     */
    public void finishTask() {
        Runnable callback = null;
        synchronized (lock) {
            inFlightTasks--;
            if (inFlightTasks == 0 && stopCallback != null) {
                callback = stopCallback;
                stopCallback = null;
            }
        }
        if (callback != null) {
            log.info("{} 处理中任务已全部完成，已安全停止", workerName);
            callback.run();
        }
    }
}
