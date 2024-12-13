package io.hainenber.jenkins.multipass;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import hudson.AbortException;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.NamingThreadFactory;
import jakarta.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultipassLauncher extends ComputerLauncher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultipassLauncher.class);
    private final String REMOTING_JAR = "remoting.jar";
    private final MultipassCloud cloud;

    /**
     * Constructor for MultipassLauncher.
     *
     * @param cloud a {@link MultipassCloud} object.
     */
    public MultipassLauncher(MultipassCloud cloud) {
        super();
        this.cloud = cloud;
    }

    public StandardUsernameCredentials getSshCredentialsId(String credentialsId) {
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernameCredentials.class,
                        MultipassCloud.jenkinsController(),
                        ACL.SYSTEM2,
                        List.of(new SchemeRequirement("ssh"))),
                CredentialsMatchers.withId(credentialsId));
    }

    @Override
    public void launch(@Nonnull SlaveComputer slaveComputer, @Nonnull TaskListener listener)
            throws IOException, InterruptedException {
        try {
            MultipassComputer computer = (MultipassComputer) slaveComputer;
            computer.setCloud(cloud);
            launchScript(computer, listener);
        } catch (IOException e) {
            e.printStackTrace(listener.error(e.getMessage()));
            LOGGER.info(
                    "[multipass-cloud] Terminating Multipass agent {} due to problem launching or connecting to it.",
                    slaveComputer.getName());
            var multipassComputer = ((MultipassComputer) slaveComputer).getNode();
            if (Objects.nonNull(multipassComputer)) {
                multipassComputer.terminate();
            }
        }
    }

    protected void launchScript(MultipassComputer computer, TaskListener listener) throws IOException {
        Node node = computer.getNode();
        if (Objects.isNull(node)) {
            LOGGER.info("[multipass-cloud] Not launching {} since it is missing a node.", computer);
            return;
        }

        // Find matching template by name for the computer.
        var matchingTemplates =
                cloud.getTemplatesByName(computer.getOriginTemplate().getName());
        if (matchingTemplates.isEmpty()) {
            LOGGER.info("[multipass-cloud] No matching template for {}", computer.getDisplayName());
            return;
        }

        LOGGER.info("[multipass-cloud] Creating new Multipass VM {} with {}", computer, listener);

        synchronized (this) {
            try {
                var instanceName = computer.getDisplayName();
                var multipassClient = cloud.getMultipassClient();
                var matchingTemplate = matchingTemplates.get(0);

                // Only create new Multipass VM when there's no VM with matching name identifier.
                // If there's matched one, the launcher will launch its Computer abstraction.
                var existingInstance = multipassClient.getInstance(instanceName);
                if (existingInstance.isEmpty()) {
                    multipassClient.createInstance(
                            instanceName,
                            matchingTemplate.getCloudInitConfig(),
                            matchingTemplate.getCpu(),
                            matchingTemplate.getMemory(),
                            matchingTemplate.getDisk(),
                            matchingTemplate.getDistroAlias());
                }

                // Establish SSH connection between controller and agent.
                var instance = multipassClient.getInstance(instanceName);
                if (instance.isEmpty()) {
                    throw new RuntimeException("Cannot find the instance named " + instanceName);
                }
                var instanceHostIp = instance.get().getIpv4();
                if (Objects.isNull(instanceHostIp)) {
                    throw new RuntimeException("Cannot find the instance named " + instanceName);
                }
                var sshConnection = new Connection(instanceHostIp.get(0), computer.getSshPort());

                var launcherExecutorService = Executors.newSingleThreadExecutor(new NamingThreadFactory(
                        Executors.defaultThreadFactory(),
                        String.format("Launching SSH connection for %s node", computer.getName())));
                Set<Callable<Boolean>> callables = getCallables(computer, listener, sshConnection, matchingTemplate);

                LOGGER.info("[multipass-cloud] Waiting for agent '{}' to be connected", computer);

                try {
                    launcherExecutorService.invokeAll(callables);
                } catch (Throwable e) {
                    LOGGER.error("Launch failed due to %", e);
                }

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

    private Set<Callable<Boolean>> getCallables(
            MultipassComputer computer,
            TaskListener listener,
            Connection sshConnection,
            MultipassAgentTemplate template) {
        Set<Callable<Boolean>> callables = new HashSet<>();
        callables.add(() -> {
            // Accept widely used cryptographic algorithms.
            sshConnection.setServerHostKeyAlgorithms(new String[] {
                "ssh-rsa", "rsa-sha2-256", "rsa-sha2-512",
            });

            // Connect and authenticate.
            sshConnectToAgent(computer, listener, sshConnection, template);

            // Display SSH connection activities onto agent's console log.
            sshConnection.exec("set", listener.getLogger());

            var agentRemoteFs = Objects.requireNonNull(computer.getNode()).getRemoteFS();

            // Move the necessary remoting.jar so that
            copyRemotingJarToAgent(listener, agentRemoteFs, sshConnection);

            // Initiate the remoting agent
            startAgent(computer, listener, agentRemoteFs, sshConnection);

            return Boolean.TRUE;
        });
        return callables;
    }

    private void startAgent(MultipassComputer computer, TaskListener listener, String absoluteRemoteFs, Connection conn)
            throws IOException {
        var session = conn.openSession();
        String cmd = String.format("cd %s && java -jar %s", Paths.get(absoluteRemoteFs, REMOTING_JAR), REMOTING_JAR);

        // Run command to initiate the agent
        session.execCommand(cmd);
        session.pipeStderr(new DelegateNoCloseOutputStream(listener.getLogger()));

        try {
            computer.setChannel(session.getStdout(), session.getStdin(), listener.getLogger(), null);
        } catch (InterruptedException e) {
            session.close();
            throw new IOException("Aborted when trying to set I/O channel after initiating agent", e);
        }
    }

    private void copyRemotingJarToAgent(TaskListener listener, String absoluteRemoteFs, Connection conn) {
        var remotingDirJarPath = Paths.get(absoluteRemoteFs, REMOTING_JAR);
        var remotingJarPath = Paths.get(String.valueOf(remotingDirJarPath), REMOTING_JAR);
        SCPClient scpClient = new SCPClient(conn);
        try {
            // Create directory to contain remoting.jar
            conn.exec("mkdir -p " + remotingDirJarPath, listener.getLogger());
            // Delete remoting.jar if exists.
            if (conn.exec("test -f " + remotingJarPath, listener.getLogger()) == 0) {
                conn.exec("rm " + remotingJarPath, listener.getLogger());
            }
            // Copy remoting.jar
            var agentJar = new Slave.JnlpJar(REMOTING_JAR).readFully();
            scpClient.put(agentJar, REMOTING_JAR, String.valueOf(remotingDirJarPath), "0644");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void sshConnectToAgent(
            MultipassComputer computer,
            TaskListener listener,
            Connection sshConnection,
            MultipassAgentTemplate template)
            throws Exception {
        int agentConnectTimeout = 10_000; // 10 seconds

        String hostName = computer.getName();
        Integer portNumber = computer.getSshPort();

        LOGGER.info(
                "[multipass-cloud] Connecting to {} host on portNumber {} with timeout {}",
                hostName,
                portNumber,
                agentConnectTimeout);

        while (true) {
            try {
                // Connect via SSH
                sshConnection.connect(
                        // TODO: verify the host key in a correct way
                        (hostname, port, serverHostKeyAlgorithm, serverHostKey) -> true,
                        agentConnectTimeout,
                        agentConnectTimeout);
                LOGGER.info("[multipass-cloud] Established SSH connection with host {}", hostName);

                // Authenticate
                StandardUsernameCredentials credentials = getSshCredentialsId(template.getSshCredentialsId());
                if (SSHAuthenticator.newInstance(sshConnection, credentials).authenticate(listener)
                        && sshConnection.isAuthenticationComplete()) {
                    LOGGER.info(
                            "Successfully authenticated agent {} with credentials \"{}\"",
                            computer.getName(),
                            credentials.getId());
                } else {
                    LOGGER.error(
                            "Failed to authenticate agent {} with credentials \"{}\"",
                            computer.getName(),
                            credentials.getId());
                }

                return;
            } catch (IOException e) {
                if (computer.isOffline() && StringUtils.isNotBlank(computer.getOfflineCauseReason())) {
                    throw new AbortException(String.format(
                            "SSH connection cannot be established and the computer is now offline: %s", e));
                } else {
                    LOGGER.error(
                            "Waiting for SSH server on agent side to be ready. Sleeping for 5 second before next attempt. Possible error: ",
                            e);
                    Thread.sleep(5000);
                }
            }
        }
    }

    /**
     *  Private class to handle SSH session output from Multipass-based agents.
     */
    private static class DelegateNoCloseOutputStream extends OutputStream {
        private OutputStream out;

        public DelegateNoCloseOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            if (out != null) out.write(b);
        }

        @Override
        public void close() {
            out = null;
        }

        @Override
        public void flush() throws IOException {
            if (out != null) out.flush();
        }

        @Override
        public void write(@Nonnull byte[] b) throws IOException {
            if (out != null) out.write(b);
        }

        @Override
        public void write(@Nonnull byte[] b, int off, int len) throws IOException {
            if (out != null) out.write(b, off, len);
        }
    }
}
