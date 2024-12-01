package io.hainenber.jenkins.multipass.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class MultipassImage {
    @JsonProperty("aliases")
    public List<String> aliases;

    @JsonProperty("os")
    public String os;

    @JsonProperty("release")
    public String release;

    @JsonProperty("remote")
    public String remote;

    @JsonProperty("version")
    public String version;
}
