package com.keny.jobassistant.service.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TaskLifecycleGateTest {

    @Test
    void stopWithoutInFlightTasksShouldCallbackImmediately() {
        TaskLifecycleGate gate = new TaskLifecycleGate("test");
        gate.start();
        AtomicInteger callbacks = new AtomicInteger();

        gate.stop(callbacks::incrementAndGet);

        assertThat(callbacks).hasValue(1);
        assertThat(gate.isRunning()).isFalse();
        assertThat(gate.tryBeginTask()).isFalse();
    }

    @Test
    void stopShouldWaitForLastInFlightTask() {
        TaskLifecycleGate gate = new TaskLifecycleGate("test");
        gate.start();
        assertThat(gate.tryBeginTask()).isTrue();
        assertThat(gate.tryBeginTask()).isTrue();
        AtomicInteger callbacks = new AtomicInteger();

        gate.stop(callbacks::incrementAndGet);
        // 停机后不再接新任务。
        assertThat(gate.tryBeginTask()).isFalse();

        gate.finishTask();
        assertThat(callbacks).hasValue(0);
        gate.finishTask();
        assertThat(callbacks).hasValue(1);
    }

    @Test
    void restartShouldAcceptTasksAgain() {
        TaskLifecycleGate gate = new TaskLifecycleGate("test");
        gate.stop();
        gate.start();

        assertThat(gate.isRunning()).isTrue();
        assertThat(gate.tryBeginTask()).isTrue();
    }
}
