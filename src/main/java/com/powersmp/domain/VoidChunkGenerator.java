package com.powersmp.domain;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

/**
 * Generates nothing at all.
 *
 * <p>The Illusory Realm needs somewhere to put its arenas that is guaranteed to be empty: no
 * terrain to clip through, no structures, no mobs wandering in, and nothing anyone could have built
 * beforehand. Turning every generation stage off gives exactly that -- a world of pure air that
 * costs almost nothing to keep loaded, into which the arena shell is stamped block by block.
 *
 * <p>Deliberately <em>not</em> {@code WorldType.FLAT}: a flat world still has a bedrock floor and a
 * biome's worth of decoration, both of which would show through the arena's own floor.
 */
public class VoidChunkGenerator extends ChunkGenerator {

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    /**
     * Nobody should ever spawn here by accident, but a world spawn inside the void would drop an
     * unlucky player forever, so it sits on solid-ish ground well away from every arena slot.
     */
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5d, 128.0d, 0.5d);
    }
}
