package me.clip.placeholderapi.expansion;

@Deprecated
public final class Version {
    private final boolean spigot;
    private final String version;

    public Version(String version, boolean spigot) {
        this.version = version;
        this.spigot = spigot;
    }

    public String getVersion() {
        return version == null ? "unknown" : version;
    }

    public boolean isSpigot() {
        return spigot;
    }

    public boolean compareTo(String otherVersion) {
        return getVersion().equalsIgnoreCase(otherVersion);
    }
}
