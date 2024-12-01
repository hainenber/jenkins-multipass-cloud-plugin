package io.hainenber.jenkins.multipass.sdk;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.exec.CommandLine;
import org.junit.jupiter.api.Test;

class MultipassClientTest {
    public String getMultipassCliFixture(String filename) throws IOException {
        return Files.readString(Paths.get("src", "test", "resources", filename));
    }

    @Test
    public void givenInstanceWithAllStatesFromMultipassCLI_whenGetInstances_thenReturnListOfMultipassClient()
            throws IOException {
        var multipassClient = new MultipassClient();
        var spiedMultipassClient = spy(multipassClient);

        // Load fixture into spied method
        doReturn(getMultipassCliFixture("listOfInstances.json"))
                .when(spiedMultipassClient)
                .getOutput(any(CommandLine.class));

        var expected = List.of(
                new MultipassInstance(
                        "java-app-builder-1", InstanceState.STOPPED, 0, List.of(), "Ubuntu 24.04 LTS", null, 0),
                new MultipassInstance(
                        "javascript-app-builder-1",
                        InstanceState.RUNNING,
                        0,
                        List.of("192.168.73.3"),
                        "Ubuntu 24.04 LTS",
                        null,
                        0));
        var actual = spiedMultipassClient.getInstances();
        assertIterableEquals(expected, actual);
    }

    @Test
    public void givenDistroAliasesFromMultipassCLI_whenGetDistroAliases_thenReturnListOfDistro() throws IOException {
        var multipassClient = new MultipassClient();
        var spiedMultipassClient = spy(multipassClient);

        // Load fixture into spied method
        doReturn(getMultipassCliFixture("listOfDistroAliases.json"))
                .when(spiedMultipassClient)
                .getOutput(any(CommandLine.class));

        var expected = Stream.of("noble", "jammy", "focal").sorted().collect(Collectors.toList());
        var actual =
                spiedMultipassClient.getDistributionAlias().stream().sorted().collect(Collectors.toList());
        assertIterableEquals(expected, actual);
    }
}
