package io.hainenber.jenkins.multipass;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import jakarta.annotation.Nonnull;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.Future;

public class MultipassComputer extends AbstractCloudComputer<MultipassAgent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultipassComputer.class);

    @Nonnull
    private final MultipassCloud cloud;

    /**
     * Constructor for MultipassComputer
     * @param multipassAgent a {@link MultipassAgent} object.
     */
    public MultipassComputer(MultipassAgent multipassAgent) {
        super(multipassAgent);
        this.cloud = multipassAgent.getCloud();
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);
        LOGGER.info("[multipass-cloud] [{}]: Task in job '{}' accepted", this, task.getFullDisplayName());
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        super.taskCompleted(executor, task, durationMS);
        LOGGER.info("[multipass-cloud] [{}]: Task in job '{}' completed in {}", this, task.getFullDisplayName(), DurationFormatUtils.formatDurationWords(durationMS, true, true));
        gracefulShutdown();
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        LOGGER.info("[multipass-cloud] [{}]: Task in job '{}' completed with problems in {}", this, task.getFullDisplayName(), DurationFormatUtils.formatDurationWords(durationMS, true, true));
        gracefulShutdown();
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", MultipassComputer.class.getSimpleName() + "[", "]")
                .add("cloud=" + cloud)
                .toString();
    }

    private void gracefulShutdown() {
        // Mark the computer to no longer accept new tasks;
        setAcceptingTasks(false);

        Future<Object> next = Computer.threadPoolForRemoting.submit(() -> {
            LOGGER.info("[multipass-cloud] [{}]: Terminating agent after task.", this);
            try {
                Thread.sleep(500);
                MultipassCloud.jenkinsController().removeNode(Objects.requireNonNull(getNode()));
            } catch (Exception e) {
                LOGGER.info("[multipass-cloud] [{}]: Error encounter when trying to terminate agent: {}", this, e.getClass());
            }
            return null;
        });

        next.notify();
    }
}
