package io.hainenber.jenkins.multipass;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import com.trilead.ssh2.Connection;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.ListBoxModel;
import io.hainenber.jenkins.multipass.sdk.MultipassClient;
import jakarta.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root class that contains all configuration state about
 * Multipass cloud agents.
 *
 * @author hainenber
 */
public class MultipassCloud extends Cloud {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultipassCloud.class);
    private static final String DEFAULT_AGENT_DISTRIBUTION_ALIAS = "noble";

    private String label;
    private String distroAlias;
    private Integer cpu;
    private String memory;
    private String disk;
    private String cloudInitConfig;
    private String sshCredentialsId;

    private transient MultipassClient client;
    private transient long lastProvisionTime = 0;

    static {
        clearAllNodes();
    }

    /**
     * Constructor for MultipassCloud
     * @param name the name of the cloud, will auto-generated if not inputted.
     */
    @DataBoundConstructor
    public MultipassCloud(String name) {
        super(
                StringUtils.isNotBlank(name)
                        ? name
                        : String.format(
                                "multipass_cloud_%s", jenkinsController().clouds.size()));
        LOGGER.info("[multipass-cloud] Initializing Cloud {}", this);
    }

    /**
     * Get the active Jenkins instance.
     *
     * @return a {@link Jenkins} object.
     */
    @Nonnull
    protected static Jenkins jenkinsController() {
        return Objects.requireNonNull(Jenkins.getInstanceOrNull());
    }

    /**
     * Clear all nodes on bootup
     */
    private static void clearAllNodes() {
        List<Node> nodes = jenkinsController().getNodes();
        if (nodes.isEmpty()) {
            return;
        }

        LOGGER.info("[multipass-cloud]: Deleting all previous Multipass nodes...");

        for (final Node node : nodes) {
            if (node instanceof MultipassAgent) {
                try {
                    ((MultipassAgent) node).terminate();
                } catch (InterruptedException | IOException e) {
                    LOGGER.error("[multipass-cloud]: Failed to terminate agent '{}'", node.getDisplayName(), e);
                }
            }
        }
    }

    /** Getter for the field <code>client</code>
     *
     * @return a {@link MultipassClient}
     */
    public synchronized MultipassClient getMultipassClient() {
        if (this.client == null) {
            this.client = new MultipassClient();
        }
        return this.client;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Collection<NodeProvisioner.PlannedNode> provision(CloudState cloudState, int excessWorkload) {
        var nodeList = new ArrayList<NodeProvisioner.PlannedNode>();
        Label label = cloudState.getLabel();

        // Not provisioning nodes if Jenkins is getting terminated
        if (jenkinsController().isQuietingDown()) {
            LOGGER.info("Not provisioning Multipass node as Jenkins is shutting down");
        } else if (jenkinsController().isTerminating()) {
            LOGGER.info("Not provisioning Multipass node as Jenkins is getting terminated");
        }

        // Guard against non-matching labels
        if (label != null && !label.matches(List.of(new LabelAtom(getLabel())))) {
            return nodeList;
        }

        // Guard against double-provisioning with a 500ms cooldown check
        long timeDiff = System.currentTimeMillis() - lastProvisionTime;
        if (timeDiff < 500) {
            LOGGER.info(
                    "[multipass-cloud] Provision of {} skipped, still on cooldown ({}ms of 500ms)",
                    excessWorkload,
                    timeDiff);
            return nodeList;
        }

        String labelName = Objects.isNull(label) ? getLabel() : label.getDisplayName();
        long currentlyProvisioningInstanceCount = getCurrentlyProvisionedAgentCount();
        long numInstancesToLaunch = Math.max(excessWorkload - currentlyProvisioningInstanceCount, 0);
        LOGGER.info(
                "[multipass-cloud] Provisioning {} nodes for label '{}' ({} already provisioning)",
                numInstancesToLaunch,
                labelName,
                currentlyProvisioningInstanceCount);

        // Initializing builder nodes and add to list of provisioned instances.
        for (int i = 0; i < numInstancesToLaunch; i++) {
            final String instanceName = createInstanceName();
            final MultipassCloud cloud = this;
            final Future<Node> nodeResolver = Computer.threadPoolForRemoting.submit(() -> {
                MultipassLauncher launcher = new MultipassLauncher(cloud);
                try {
                    MultipassAgent agent = new MultipassAgent(cloud, instanceName, launcher);
                    agent.setLabelString(cloud.getLabel());
                    jenkinsController().addNode(agent);
                    return agent;
                } catch (Descriptor.FormException | IOException e) {
                    LOGGER.error("[multipass-cloud] Exception when initializing new Multipass agent: %s", e);
                    return null;
                }
            });
            nodeList.add(new NodeProvisioner.PlannedNode(instanceName, nodeResolver, 1));
        }

        lastProvisionTime = System.currentTimeMillis();

        return nodeList;
    }

    /**
     * Find the number of {@link MultipassAgent} instances still connecting
     * to Jenkins controller
     */
    private long getCurrentlyProvisionedAgentCount() {
        return jenkinsController().getNodes().stream()
                .filter(MultipassAgent.class::isInstance)
                .map(MultipassAgent.class::cast)
                .filter(a -> a.getLauncher().isLaunchSupported())
                .count();
    }

    /** {@inheritDoc} */
    @Override
    public boolean canProvision(CloudState cloudState) {
        Label cloudLabel = cloudState.getLabel();
        boolean canProvision = Objects.isNull(cloudLabel) || cloudLabel.matches(List.of(new LabelAtom(getLabel())));
        LOGGER.info("[multipass-cloud] Check provisioning capacity for label '{}': {}", label, canProvision);
        return canProvision;
    }

    /**
     *
     * @return int
     */
    public String getName() {
        return this.name;
    }

    /**
     * Getter for field <code>cloudInitConfig</code>.
     * @return a @{link String} object.
     */
    public String getCloudInitConfig() {
        return this.cloudInitConfig;
    }

    /**
     * Setter for the field <code>cloudInitConfig</code>
     * @param cloudInitConfig a {@link String} object.
     */
    @DataBoundSetter
    public void setCloudInitConfig(String cloudInitConfig) {
        this.cloudInitConfig = cloudInitConfig;
    }

    /**
     * Getter for field <code>cpu</code>.
     * @return a @{link Integer} object.
     */
    public Integer getCpu() {
        return this.cpu;
    }

    /**
     * Setter for the field <code>cpu</code>
     * @param cpu a {@link Integer} object.
     */
    @DataBoundSetter
    public void setCpu(Integer cpu) {
        this.cpu = cpu;
    }

    /**
     * Getter for field <code>cpu</code>.
     * @return a @{link String} object.
     */
    @Nonnull
    public String getMemory() {
        return this.memory;
    }

    /**
     * Setter for the field <code>memory</code>
     * @param memory a {@link String} object.
     */
    @DataBoundSetter
    public void setMemory(String memory) {
        this.memory = memory;
    }

    /**
     * Getter for field <code>disk</code>.
     * @return a @{link String} object.
     */
    @Nonnull
    public String getDisk() {
        return this.disk;
    }

    /**
     * Setter for the field <code>disk</code>
     * @param disk a {@link String} object.
     */
    @DataBoundSetter
    public void setDisk(String disk) {
        this.disk = disk;
    }

    /**
     * Getter for field <code>SSH credentials</code>.
     * @return a @{link String} object.
     */
    @Nonnull
    public String getSshCredentialsId() {
        return this.sshCredentialsId;
    }

    /**
     * Setter for the field <code>disk</code>
     * @param sshCredentialsId a {@link String} object.
     */
    @DataBoundSetter
    public void setSshCredentialsId(String sshCredentialsId) {
        this.sshCredentialsId = sshCredentialsId;
    }

    /**
     * Getter for field <code>distroAlias</code>.
     * @return a @{link String} object.
     */
    public String getDistroAlias() {
        return Objects.isNull(distroAlias) ? DEFAULT_AGENT_DISTRIBUTION_ALIAS : distroAlias;
    }

    /**
     * Setter for the field <code>distroAlias</code>
     * @param distroAlias a {@link String} object.
     */
    @DataBoundSetter
    public void setDistroAlias(String distroAlias) {
        this.distroAlias = distroAlias;
    }

    /**
     * Getter for field <code>label</code>.
     * @return a @{link String} object.
     */
    public String getLabel() {
        return this.label;
    }

    /**
     * Setter for the field <code>label</code>
     * @param label a {@link String} object.
     */
    @DataBoundSetter
    public void setLabel(String label) {
        this.label = label;
    }

    @Extension
    @SuppressWarnings("unused")
    public static class DescriptorImpl extends Descriptor<Cloud> {
        private final MultipassClient client;

        public DescriptorImpl() {
            super();
            this.client = new MultipassClient();
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.displayName();
        }

        // Fill out supported Ubuntu distribution aliases
        public ListBoxModel doFillDistroAliasItems() throws IOException {
            final ListBoxModel options = new ListBoxModel();

            List<String> availableDistroAliases = client.getDistributionAlias();
            if (availableDistroAliases.isEmpty()) {
                options.add(DEFAULT_AGENT_DISTRIBUTION_ALIAS);
            } else {
                for (String availableDistroAlias : availableDistroAliases) {
                    options.add(availableDistroAlias);
                }
            }
            return options;
        }

        public ListBoxModel doFillSshCredentialsIdItems(
                @AncestorInPath ItemGroup context, @QueryParameter String credentialsId) {
            AccessControlled securityContext =
                    context instanceof AccessControlled ? (AccessControlled) context : jenkinsController();
            // Not to fill any credentials if the current user lacks of required permissions.
            if (!securityContext.hasPermission(Computer.CONFIGURE)) {
                return new StandardUsernameListBoxModel().includeCurrentValue(credentialsId);
            }
            return new StandardUsernameListBoxModel()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            context,
                            StandardUsernameCredentials.class,
                            Collections.singletonList(new SchemeRequirement("ssh")),
                            SSHAuthenticator.matcher(Connection.class))
                    .includeCurrentValue(credentialsId);
        }
    }

    /**
     * Create a Multipass VM name with random alphanumeric suffix.
     * @return a {@link String} for a Multipass VM
     */
    private String createInstanceName() {
        return String.format("%s-%s", getName(), RandomStringUtils.randomAlphanumeric(4));
    }

    /**
     * Allow creating Multipass VM-based Jenkins agents manually, from "Nodes" page .
     */
    @RequirePOST
    @SuppressWarnings("unused")
    public HttpResponse doProvision() {
        checkPermission(PROVISION);

        if (jenkinsController().isQuietingDown()) {
            throw HttpResponses.error(SC_BAD_REQUEST, "Jenkins controller is quieting down");
        }
        if (jenkinsController().isTerminating()) {
            throw HttpResponses.error(SC_BAD_REQUEST, "Jenkins controller is terminating");
        }

        try {
            var nodes = provision(
                    new CloudState(new LabelAtom(getLabel()), 1), (int) (getCurrentlyProvisionedAgentCount() + 1));
            var manuallyProvisionedAgent = Arrays.stream(nodes.toArray(NodeProvisioner.PlannedNode[]::new))
                    .findFirst();
            if (manuallyProvisionedAgent.isEmpty()) {
                throw HttpResponses.error(
                        SC_INTERNAL_SERVER_ERROR,
                        "multipass-cloud-plugin cannot provision on-demand agent as requested");
            }
            var manuallyProvisionedAgentName = manuallyProvisionedAgent.get().displayName;
            return HttpResponses.redirectViaContextPath(String.format("/computer/%s", manuallyProvisionedAgentName));
        } catch (Exception e) {
            throw HttpResponses.error(SC_INTERNAL_SERVER_ERROR, e);
        }
    }
}
