package dev.ellipog.armature.fabric;

import dev.ellipog.armature.api.energy.EnergyAccess;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Fabric entry shape, pinned without launching anything.
 *
 * <p>A wrong entry shape is a silent no-load: the loader never calls a class that does not
 * implement its initialiser, and nothing reports it. The holder check pins the soft-dependency
 * contract beside it: the id is a compile-time constant (reading it loads nothing) and the
 * adapter builds on first read. Neither needs registries or a world.
 */
@DisplayName("the Fabric entry wiring")
class ArmatureFabricWiringTest {

    @Test
    @DisplayName("the entry points implement the loader's initialisers")
    void entriesImplementInitialisers() {
        assertTrue(ModInitializer.class.isAssignableFrom(ArmatureFabric.class),
                "the common initialiser is what the loader constructs");
        assertTrue(ClientModInitializer.class.isAssignableFrom(FabricClient.class),
                "and the client one is what it constructs on a client");
    }

    @Test
    @DisplayName("the energy holder names its id cheaply and builds its adapter")
    void energyHolderContract() {
        assertEquals("team_reborn_energy", TrEnergyProvider.MOD_ID,
                "the probe string the entry point guards on");
        assertInstanceOf(EnergyAccess.class, TrEnergyProvider.INSTANCE,
                "reading the holder builds the adapter behind the guard");
    }
}
