package io.hainenber.jenkins.multipass.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MultipassClient {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultExecutor executor = DefaultExecutor.builder().get();

    public String getOutput(CommandLine cmd) throws IOException {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PumpStreamHandler pumpStreamHandler = new PumpStreamHandler(stdout);

        executor.setStreamHandler(pumpStreamHandler);
        executor.execute(cmd);

        return stdout.toString();
    }

    public List<MultipassInstance> getInstances() throws IOException {
        CommandLine getCmd = CommandLine.parse("multipass list");
        getCmd.addArgument("--format");
        getCmd.addArgument("json");

        var instanceListString = getOutput(getCmd);

        // Extract and deserialize the 'list' property
        return objectMapper.readValue(
                objectMapper.readTree(instanceListString).get("list").toString(),
                new TypeReference<>() {}
        );
    }

    public void createInstance(String name, String cloudInitConfig, Integer cpus, String memory, String disk, String distroAlias) throws IOException {
        // Save cloud-init config to temporary file
        Path cloudInitConfigPath = Files.createTempFile("cloud-init-config", ".yaml");
        Files.writeString(cloudInitConfigPath, cloudInitConfig, StandardCharsets.UTF_8);

        // Add Multipass arguments
        CommandLine createCmd = CommandLine.parse("multipass launch");
        createCmd.addArguments(new String[] {"--name", name} );
        createCmd.addArguments(new String[] {"--cpus", cpus.toString() });
        createCmd.addArguments(new String[] {"--memory", memory});
        createCmd.addArguments(new String[] {"--disk", disk});
        createCmd.addArguments(new String[] {"--cloud-init", cloudInitConfigPath.toAbsolutePath().toString() });
        createCmd.addArgument(distroAlias);

        executor.execute(createCmd);
    }

    public void terminateInstance(String instanceName) throws IOException {
        CommandLine deleteCmd = CommandLine.parse("multipass delete");
        deleteCmd.addArgument(instanceName);
        CommandLine purgeCmd = CommandLine.parse("multipass purge");
        executor.execute(deleteCmd);
        executor.execute(purgeCmd);
    }
}
