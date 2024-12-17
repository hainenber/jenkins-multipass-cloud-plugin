package io.hainenber.jenkins.multipass;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import com.trilead.ssh2.Connection;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.Saveable;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.util.ListBoxModel;
import io.hainenber.jenkins.multipass.sdk.MultipassClient;
import jakarta.annotation.Nonnull;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class MultipassAgentTemplate extends AbstractDescribableImpl<MultipassAgentTemplate>
        implements Saveable, Serializable {
    private static final String DEFAULT_AGENT_DISTRIBUTION_ALIAS = "noble";

    @Serial
    private static final long serialVersionUID = 1609229396383244191L;

    private String labels;
    private String distroAlias;
    private Integer cpu;
    private String memory;
    private String disk;
    private String cloudInitConfig;
    private String sshCredentialsId;
    private String name;

    @DataBoundConstructor
    public MultipassAgentTemplate(
            String sshCredentialsId,
            String cloudInitConfig,
            String disk,
            String memory,
            Integer cpu,
            String distroAlias,
            String label,
            String name) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            jenkins.checkPermission(Jenkins.ADMINISTER);
        }

        this.sshCredentialsId = sshCredentialsId;
        this.cloudInitConfig = cloudInitConfig;
        this.disk = disk;
        this.memory = memory;
        this.cpu = cpu;
        this.distroAlias = distroAlias;
        this.labels = label;
        this.name = name;
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
        return distroAlias == null ? DEFAULT_AGENT_DISTRIBUTION_ALIAS : distroAlias;
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
     * Getter for field <code>labels</code>.
     * @return a @{link String} object.
     */
    public String getLabels() {
        return this.labels;
    }

    /**
     * Setter for the field <code>labels</code>
     * @param labels a {@link String} object.
     */
    @DataBoundSetter
    public void setLabels(String labels) {
        this.labels = labels;
    }

    /**
     * Getter for field <code>name</code>.
     * @return a @{link String} object.
     */
    public String getName() {
        return this.name;
    }

    /**
     * Setter for the field <code>name</code>
     * @param name a {@link String} object.
     */
    @DataBoundSetter
    public void setName(String name) {
        this.name = name;
    }

    public Set<LabelAtom> getLabelSet() {
        return Label.parse(this.labels);
    }

    @Override
    public void save() throws IOException {}

    @Extension
    @SuppressWarnings("unused")
    public static class DescriptorImpl extends Descriptor<MultipassAgentTemplate> {
        private final MultipassClient client;

        public DescriptorImpl() {
            super();
            this.client = new MultipassClient();
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
                    context instanceof AccessControlled ? (AccessControlled) context : Jenkins.get();
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
}
