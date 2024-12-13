package io.hainenber.jenkins.multipass;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
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
import net.sf.json.JSONObject;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
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

    private List<MultipassAgentTemplate> templates;

    private transient MultipassClient client;
    private transient long lastProvisionTime = 0;

    /**
     * Constructor for MultipassCloud
     * @param name the name of the cloud, will auto-generated if not inputted.
     */
    @DataBoundConstructor
    public MultipassCloud(String name, List<MultipassAgentTemplate> templates) {
        super(
                StringUtils.isNotBlank(name)
                        ? name
                        : String.format(
                                "multipass_cloud_%s", jenkinsController().clouds.size()));
        this.templates = Objects.isNull(templates) ? Collections.emptyList() : templates;
        LOGGER.info("[multipass-cloud] Initializing Cloud {}", this);
    }

    public List<MultipassAgentTemplate> getTemplatesByLabel(Label label) {
        return Objects.nonNull(this.templates)
                ? this.templates.stream()
                        .filter(t -> Objects.nonNull(t) && label.matches(t.getLabelSet()))
                        .toList()
                : Collections.emptyList();
    }

    public List<MultipassAgentTemplate> getTemplatesByName(String templateName) {
        return Objects.nonNull(this.templates)
                ? this.templates.stream()
                        .filter(t -> Objects.nonNull(t) && t.getName().equals(templateName))
                        .toList()
                : Collections.emptyList();
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
        var label = cloudState.getLabel();
        final var matchingTemplates = getTemplatesByLabel(label);

        // Not provisioning nodes if Jenkins is getting terminated
        if (jenkinsController().isQuietingDown()) {
            LOGGER.info("Not provisioning Multipass node as Jenkins is shutting down");
            return Collections.emptyList();
        } else if (jenkinsController().isTerminating()) {
            LOGGER.info("Not provisioning Multipass node as Jenkins is getting terminated");
            return Collections.emptyList();
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

        for (MultipassAgentTemplate t : matchingTemplates) {
            String labelName = Objects.isNull(label) ? t.getLabels() : label.getDisplayName();
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
                        MultipassAgent agent = new MultipassAgent(cloud, instanceName, launcher, t);
                        agent.setLabelString(t.getLabels());
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
        }

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
        boolean canProvision = !getTemplatesByLabel(cloudLabel).isEmpty();
        LOGGER.info("[multipass-cloud] Check provisioning capacity for label '{}': {}", cloudLabel, canProvision);
        return canProvision;
    }

    public String getName() {
        return this.name;
    }

    @SuppressWarnings("unused")
    public List<MultipassAgentTemplate> getTemplates() {
        return this.templates;
    }

    @DataBoundSetter
    public void setTemplates(List<MultipassAgentTemplate> templates) {
        this.templates = templates;
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
    public HttpResponse doProvision(@QueryParameter String template) {
        checkPermission(PROVISION);

        var matchingTemplate = this.templates.stream().filter(t -> t.getName().equals(template)).toList().stream()
                .findFirst();
        if (matchingTemplate.isEmpty()) {
            throw HttpResponses.error(SC_BAD_REQUEST, "No such template " + template);
        }

        if (jenkinsController().isQuietingDown()) {
            throw HttpResponses.error(SC_BAD_REQUEST, "Jenkins controller is quieting down");
        }
        if (jenkinsController().isTerminating()) {
            throw HttpResponses.error(SC_BAD_REQUEST, "Jenkins controller is terminating");
        }

        try {
            var nodes = provision(
                    new CloudState(new LabelAtom(matchingTemplate.get().getLabels()), 1),
                    (int) (getCurrentlyProvisionedAgentCount() + 1));
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

    @Extension
    @SuppressWarnings("unused")
    public static class DescriptorImpl extends Descriptor<Cloud> {
        public DescriptorImpl() {
            super();
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.displayName();
        }

        public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
            return Boolean.TRUE;
        }
    }
}
