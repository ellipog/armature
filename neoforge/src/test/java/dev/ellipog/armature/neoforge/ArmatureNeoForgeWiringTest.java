package dev.ellipog.armature.neoforge;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.energy.EnergyAccess;

import net.neoforged.fml.common.Mod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The NeoForge entry shape, pinned without launching anything.
 *
 * <p>Same reasoning as the Fabric twin's test: a missing or misnamed {@code @Mod} is a silent
 * no-load, and the installed adapter honouring its seam is the wiring the entry point relies
 * on. Neither needs registries or a world.
 */
@DisplayName("the NeoForge entry wiring")
class ArmatureNeoForgeWiringTest {

    @Test
    @DisplayName("the entry point carries this mod's marker")
    void entryCarriesMarker() {
        Mod mod = ArmatureNeoForge.class.getAnnotation(Mod.class);
        assertNotNull(mod, "without the marker the loader never constructs the entry");
        assertEquals(Constants.MOD_ID, mod.value(), "for this mod, not another");
    }

    @Test
    @DisplayName("the energy adapter honours the seam")
    void energyHonoursSeam() {
        assertInstanceOf(EnergyAccess.class, new NeoForgeEnergy(),
                "the installed implementation answers the seam's type");
    }
}
