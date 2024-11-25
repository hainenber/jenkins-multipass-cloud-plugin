package io.hainenber.jenkins.multipass;

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import io.hainenber.jenkins.multipass.sdk.MultipassClient;
import jakarta.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

public class MultipassAgent extends AbstractCloudSlave {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultipassAgent.class);
    private final transient MultipassCloud cloud;
    private static final long serialVersionUID = 2553788927582449937L;

    /**
     * Constructor
     * @param cloud a {@link MultipassCloud} object;
     * @param name     the name of the agent.
     * @param launcher a {@link hudson.slaves.ComputerLauncher} object.
     * @throws hudson.model.Descriptor.FormException if any.
     * @throws java.io.IOException                   if any.
     */
    public MultipassAgent(MultipassCloud cloud, @Nonnull String name, @Nonnull ComputerLauncher launcher) throws Descriptor.FormException, IOException {
        super(name, "/build", launcher);
        this.cloud = cloud;
    }

    /**
     * Get cloud instance associated with this builder agent.
     * @return a {@link MultipassCloud} object.
     */
    public MultipassCloud getCloud() {
        return cloud;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCloudComputer<MultipassAgent> createComputer() {
        return new MultipassComputer(this);
    }

    /** {@inheritDoc} */
    protected void _terminate(TaskListener listener) {
        listener.getLogger().println("[multipass-cloud]: Terminating agent " + getDisplayName());

        if (getLauncher() instanceof MultipassLauncher) {
            var instanceName = Objects.requireNonNull(getComputer()).getName();
            if (StringUtils.isBlank(instanceName)) {
                return;
            }

            try {
                LOGGER.info("[multipass-cloud]: Terminating instance named '{}'", instanceName);
                MultipassClient multipassClient = cloud.getMultipassClient();
                multipassClient.terminateInstance(instanceName);
                LOGGER.info("[multipass-cloud]: Terminated instance named '{}'", instanceName);
                Jenkins.get().removeNode(this);
                LOGGER.info("[multipass-cloud]: Removed Multipass instance named '{}' from Jenkins controller", instanceName);
            } catch (IOException e) {
                LOGGER.warn("[multipass-cloud]: Failed to terminate Multipass instance named '{}'", instanceName);
            }
        }
    }
}
