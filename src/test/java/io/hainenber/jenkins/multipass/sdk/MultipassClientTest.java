package io.hainenber.jenkins.multipass.sdk;

import org.apache.commons.exec.CommandLine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.Mockito.*;

class MultipassClientTest {
    @Test
    public void givenStoppedAndRunningInstance_whenGetInstances_thenReturnListOfMultipassClient() throws IOException {
        var multipassClient = new MultipassClient();
        var spiedMultipassClient = spy(multipassClient);

        // Load fixture into spied method
        var multipassListFixture = Files.readString(Paths.get("src", "test", "resources", "listOfInstances.json"));
        doReturn(multipassListFixture).when(spiedMultipassClient).getOutput(any(CommandLine.class));

        var expected = List.of(
                new MultipassInstance("java-app-builder-1", InstanceState.STOPPED, 0, List.of(), "Ubuntu 24.04 LTS", null, 0),
                new MultipassInstance("javascript-app-builder-1", InstanceState.RUNNING, 0, List.of("192.168.73.3"), "Ubuntu 24.04 LTS", null, 0)
        );
        var actual = spiedMultipassClient.getInstances();
        assertIterableEquals(expected, actual);
    }

}
