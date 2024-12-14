package io.hainenber.jenkins.multipass.jcasc;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.hainenber.jenkins.multipass.MultipassCloud;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import org.junit.Rule;
import org.junit.Test;

public class ConfigurationAsCodeTest {
    @Rule
    public JenkinsConfiguredWithCodeRule r = new JenkinsConfiguredWithCodeRule();

    @Test
    @ConfiguredWithCode("configuration-as-code.yaml")
    public void should_support_configuration_as_code() throws Exception {
        var clouds = r.jenkins.clouds;
        assertEquals(1, clouds.size());

        var cloud = (MultipassCloud) (clouds.get(0));
        var templates = cloud.getTemplates();
        assertEquals(2, templates.size());

        var javaAppBuilderTemplate =
                cloud.getTemplatesByName("java-app-builder").get(0);
        assertNotNull(javaAppBuilderTemplate);
        assertEquals("java", javaAppBuilderTemplate.getLabels());
        assertEquals(1, (long) javaAppBuilderTemplate.getCpu());
        assertEquals("1G", javaAppBuilderTemplate.getMemory());
        assertEquals("10G", javaAppBuilderTemplate.getDisk());
        assertEquals("test-ssh", javaAppBuilderTemplate.getSshCredentialsId());
        assertThat(
                javaAppBuilderTemplate.getCloudInitConfig(),
                containsString("AAAAB3NzaC1yc2EAAAADAQABAAACAQDBcqtJZZ4fWGnnxAWQ2BTmyEhKZvTyFqcO5FhnwNRg"));
        assertThat(javaAppBuilderTemplate.getCloudInitConfig(), containsString("openjdk-21-jre-headless"));
        assertThat(javaAppBuilderTemplate.getCloudInitConfig(), containsString("openjdk-21-jdk-headless"));
    }
}
