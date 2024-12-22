package io.hainenber.jenkins.multipass;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import io.hainenber.jenkins.multipass.sdk.MultipassClient;
import jakarta.annotation.Nonnull;
import java.io.IOException;
import java.io.Serial;
import java.util.Objects;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultipassAgent extends AbstractCloudSlave implements TrackedItem {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultipassAgent.class);
    private final MultipassCloud cloud;
    private final MultipassAgentTemplate template;

    @Serial
    private static final long serialVersionUID = 2553788927582449937L;

    @Nonnull
    private final ProvisioningActivity.Id id;

    /**
     * Constructor
     * @param cloud a {@link MultipassCloud} object;
     * @param name     the name of the agent.
     * @param launcher a {@link hudson.slaves.ComputerLauncher} object.
     * @param template a {@link MultipassAgentTemplate} object.
     * @throws hudson.model.Descriptor.FormException if any.
     * @throws java.io.IOException                   if any.
     */
    public MultipassAgent(
            MultipassCloud cloud,
            @Nonnull String name,
            @Nonnull ComputerLauncher launcher,
            @Nonnull MultipassAgentTemplate template)
            throws Descriptor.FormException, IOException {
        // TODO: remove this hardcoded value.
        super(name, "/home/jenkins", launcher);
        this.cloud = cloud;
        this.template = template;
        this.id = new ProvisioningActivity.Id(cloud.getName(), template.getName(), name);
    }

    /**
     * Get cloud instance associated with this builder agent.
     * @return a {@link MultipassCloud} object.
     */
    public MultipassCloud getCloud() {
        return cloud;
    }

    /**
     * Get cloud instance associated with this builder agent.
     * @return a {@link MultipassAgentTemplate} object.
     */
    public MultipassAgentTemplate getTemplate() {
        return template;
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
                getComputer().disconnect(new MultipassOfflineCause());
                LOGGER.info("[multipass-cloud]: Disconnect instance named '{}'", instanceName);
                MultipassClient multipassClient = new MultipassClient();
                multipassClient.terminateInstance(instanceName);
                LOGGER.info("[multipass-cloud]: Terminated instance named '{}'", instanceName);
                Jenkins.get().removeNode(this);
                LOGGER.info(
                        "[multipass-cloud]: Removed Multipass instance named '{}' from Jenkins controller",
                        instanceName);
            } catch (IOException e) {
                LOGGER.warn("[multipass-cloud]: Failed to terminate Multipass instance named '{}'", instanceName);
            }
        }
    }

    private static class MultipassOfflineCause extends OfflineCause {
        @Override
        public String toString() {
            return "Disconnect Multipass VM";
        }
    }

    @Nonnull
    @Override
    public ProvisioningActivity.Id getId() {
        return id;
    }


    @Extension
    @SuppressWarnings("unused") // used by jelly
    public static final class DescriptorImpl extends SlaveDescriptor {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Multipass VM";
        }
    }
}
