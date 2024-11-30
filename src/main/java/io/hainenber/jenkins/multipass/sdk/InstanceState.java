package io.hainenber.jenkins.multipass.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum InstanceState {
    @JsonProperty("Running")
    RUNNING,

    @JsonProperty("Stopped")
    STOPPED,

    @JsonProperty("Deleted")
    DELETED,
}
