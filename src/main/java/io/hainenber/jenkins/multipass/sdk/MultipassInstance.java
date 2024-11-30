package io.hainenber.jenkins.multipass.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MultipassInstance {
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public InstanceState getState() {
        return state;
    }

    public void setState(InstanceState state) {
        this.state = state;
    }

    public int getSnapshots() {
        return snapshots;
    }

    public void setSnapshots(int snapshots) {
        this.snapshots = snapshots;
    }

    @Nullable
    public List<String> getIpv4() {
        return Objects.isNull(ipv4) ? new ArrayList<>() : ipv4;
    }

    public void setIpv4(@Nullable List<String> ipv4) {
        this.ipv4 = ipv4;
    }

    public String getReleaseName() {
        return releaseName;
    }

    public void setReleaseName(String releaseName) {
        this.releaseName = releaseName;
    }

    public String getImageHash() {
        return imageHash;
    }

    public void setImageHash(String imageHash) {
        this.imageHash = imageHash;
    }

    public int getCpus() {
        return cpus;
    }

    public void setCpus(int cpus) {
        this.cpus = cpus;
    }

    @JsonProperty(value = "name", required = true)
    private String name;

    @JsonProperty(value = "state", required = true)
    private InstanceState state;

    @JsonProperty("snapshots")
    private int snapshots;

    @JsonProperty("ipv4")
    private List<String> ipv4;

    @JsonProperty("release")
    private String releaseName;

    @Nullable
    @JsonProperty("image_hash")
    private String imageHash;

    @JsonProperty("cpus")
    private int cpus;

    public MultipassInstance() {}

    public MultipassInstance(
            String name,
            InstanceState state,
            int snapshots,
            List<String> ipv4,
            String releaseName,
            String imageHash,
            int cpus) {
        this.name = name;
        this.state = state;
        this.snapshots = snapshots;
        this.ipv4 = ipv4;
        this.releaseName = releaseName;
        this.imageHash = imageHash;
        this.cpus = cpus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MultipassInstance that = (MultipassInstance) o;
        return getSnapshots() == that.getSnapshots()
                && getCpus() == that.getCpus()
                && Objects.equals(getName(), that.getName())
                && getState() == that.getState()
                && Objects.equals(getIpv4(), that.getIpv4())
                && Objects.equals(getReleaseName(), that.getReleaseName())
                && Objects.equals(getImageHash(), that.getImageHash());
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                getName(), getState(), getSnapshots(), getIpv4(), getReleaseName(), getImageHash(), getCpus());
    }
}
