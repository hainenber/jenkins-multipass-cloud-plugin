package io.hainenber.jenkins.multipass;

import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class MultipassLauncher extends ComputerLauncher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultipassLauncher.class);
    private final MultipassCloud cloud;

    /**
     * Constructor for MultipassLauncher
     *
     * @param cloud
     */
    public MultipassLauncher(MultipassCloud cloud) {
        super();
        this.cloud = cloud;
    }

    @Override
    public void launch(@Nonnull SlaveComputer slaveComputer, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        try {
            MultipassComputer computer = (MultipassComputer) slaveComputer;
            launchScript(computer, listener);
        } catch (IOException e) {
            e.printStackTrace(listener.error(e.getMessage()));
            LOGGER.info("[multipass-cloud] Terminating Multipass agent {} due to problem launching or connecting to it.", slaveComputer.getName());
            var multipassComputer = ((MultipassComputer) slaveComputer).getNode();
            if (multipassComputer != null) {
                multipassComputer.terminate();
            }
        }
    }

    protected void launchScript(MultipassComputer computer, TaskListener listener) throws IOException, InterruptedException {
        Node node = computer.getNode();
        if (node == null) {
            LOGGER.info("[multipass-cloud] Not launching {} since it is missing a node.", computer);
            return;
        }

        LOGGER.info("[multipass-cloud] Launching {} with {}", computer, listener);
        try {
            cloud.getMultipassClient().createInstance(
                    cloud.getName(),
                    cloud.getCloudInitConfig(),
                    cloud.getCPUs(),
                    cloud.getMemory(),
                    cloud.getDisk(),
                    cloud.getDistroAlias()
            );
            LOGGER.info("[multipass-cloud] Waiting for agent '{}' to be connected",  computer);

        } catch (Exception e) {
            LOGGER.error("[multipass-cloud] Exception when launching Multipass VM: {}", e.getMessage());
            listener.fatalError("[multipass-cloud] Exception when launching Multipass VM: %s", e.getMessage());

            try {
                MultipassCloud.jenkinsController().removeNode(node);
            } catch (IOException e1) {
                LOGGER.error("[multipass-cloud] Failed to terminate agent: {}", node.getDisplayName(), e);
            }
        }
    }
}
