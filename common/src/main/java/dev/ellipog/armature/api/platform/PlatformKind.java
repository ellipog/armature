package dev.ellipog.armature.api.platform;

/**
 * Which mod loader Armature is running on.
 *
 * <p>Named rather than a boolean so that a third loader, or a test double, is added by
 * adding a constant instead of changing every {@code isFabric()} call in the codebase.
 */
public enum PlatformKind {

    FABRIC("Fabric"),
    NEOFORGE("NeoForge");

    private final String displayName;

    PlatformKind(String displayName) {
        this.displayName = displayName;
    }

    /** The name to show a player — matches what the launcher and the mod list call it. */
    public String displayName() {
        return displayName;
    }
}
