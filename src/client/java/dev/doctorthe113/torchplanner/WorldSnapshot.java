package dev.doctorthe113.torchplanner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;

/**
 * Copy of the blocks around the player, so the solver can run on worker threads
 * without touching the live level.
 *
 * <p>Dark spots are collected within {@code spotRadius} of the centre and torch
 * candidates within the wider {@code torchRadius}, so a torch just outside the
 * planned area can light its edge. The snapshot extends {@link #PAD} blocks past
 * that so light can bend around corners near the boundary.
 */
public final class WorldSnapshot {
	static final int PAD = 3;

	final int minX, minY, minZ;
	final int sizeX, sizeY, sizeZ;
	/** Block light level above which hostile mobs cannot spawn in this dimension. */
	final int blockLightLimit;
	/** {@code null} means unloaded: treated as fully opaque. */
	final BlockState[] states;
	/** Spawnable and currently dark enough for a monster to spawn. */
	final boolean[] darkSpot;
	/** A floor torch can be placed here. */
	final boolean[] torchSpot;

	WorldSnapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, int blockLightLimit) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.blockLightLimit = blockLightLimit;
		int volume = sizeX * sizeY * sizeZ;
		this.states = new BlockState[volume];
		this.darkSpot = new boolean[volume];
		this.torchSpot = new boolean[volume];
	}

	int index(int x, int y, int z) {
		return (y * sizeZ + z) * sizeX + x;
	}

	BlockPos pos(int index) {
		int x = index % sizeX;
		int z = (index / sizeX) % sizeZ;
		int y = index / (sizeX * sizeZ);
		return new BlockPos(minX + x, minY + y, minZ + z);
	}

	/**
	 * Captures a snapshot a slice at a time, so a large radius doesn't freeze the game.
	 * Call {@link #step} on the client thread each tick until it returns true.
	 */
	static final class Builder {
		private static final BlockState AIR = Blocks.AIR.defaultBlockState();

		private final Level level;
		private final WorldSnapshot snap;
		private final int spotMin, spotMax;
		private final int chunkMinX, chunkMinZ, chunksX, chunksZ;
		/** Chunk columns copied so far (stage 1), then x rows flagged so far (stage 2). */
		private int cursor;
		private boolean copied;
		private int missingChunks;

		Builder(Level level, BlockPos center, int spotRadius, int torchRadius, int verticalRadius) {
			this.level = level;
			int innerMinY = Math.max(center.getY() - verticalRadius, level.getMinY() + 1);
			int innerMaxY = Math.min(center.getY() + verticalRadius, level.getMaxY() - 1);
			int minX = center.getX() - torchRadius - PAD;
			int minZ = center.getZ() - torchRadius - PAD;
			int sizeXZ = 2 * (torchRadius + PAD) + 1;
			int sizeY = innerMaxY - innerMinY + 1 + 2 * PAD;
			this.snap = new WorldSnapshot(minX, innerMinY - PAD, minZ, sizeXZ, sizeY, sizeXZ,
					level.dimensionType().monsterSpawnBlockLightLimit());
			this.spotMin = PAD + torchRadius - spotRadius;
			this.spotMax = sizeXZ - 1 - spotMin;
			this.chunkMinX = SectionPos.blockToSectionCoord(minX);
			this.chunkMinZ = SectionPos.blockToSectionCoord(minZ);
			this.chunksX = SectionPos.blockToSectionCoord(minX + sizeXZ - 1) - chunkMinX + 1;
			this.chunksZ = SectionPos.blockToSectionCoord(minZ + sizeXZ - 1) - chunkMinZ + 1;
		}

		WorldSnapshot result() {
			return snap;
		}

		/** Chunks in the area that weren't loaded; their blocks are treated as opaque. */
		int missingChunks() {
			return missingChunks;
		}

		/** Rough progress, 0 to 1. */
		float progress() {
			return copied
					? 0.5f + 0.5f * cursor / snap.sizeX
					: 0.5f * cursor / (chunksX * chunksZ);
		}

		/** Works until {@code deadline} ({@link System#nanoTime()}); returns true when finished. */
		boolean step(long deadline) {
			if (!copied) {
				while (cursor < chunksX * chunksZ) {
					copyChunk(chunkMinX + cursor % chunksX, chunkMinZ + cursor / chunksX);
					cursor++;
					if (System.nanoTime() > deadline) return false;
				}
				copied = true;
				cursor = 0;
				// Torches can't help in dimensions where monsters ignore block light (the Nether).
				if (snap.blockLightLimit >= TorchSolver.TORCH_LIGHT) return true;
			}
			while (cursor < snap.sizeX) {
				if (cursor >= PAD && cursor < snap.sizeX - PAD) flagRow(cursor);
				cursor++;
				if (System.nanoTime() > deadline) return false;
			}
			return true;
		}

		private void copyChunk(int chunkX, int chunkZ) {
			LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
			if (chunk == null) {
				missingChunks++;
				return; // stays null: opaque
			}
			int x0 = Math.max(chunkX * 16, snap.minX), x1 = Math.min(chunkX * 16 + 15, snap.minX + snap.sizeX - 1);
			int z0 = Math.max(chunkZ * 16, snap.minZ), z1 = Math.min(chunkZ * 16 + 15, snap.minZ + snap.sizeZ - 1);
			LevelChunkSection[] sections = chunk.getSections();
			for (int y = 0; y < snap.sizeY; y++) {
				int worldY = snap.minY + y;
				int sectionIndex = chunk.getSectionIndex(worldY);
				LevelChunkSection section = sectionIndex >= 0 && sectionIndex < sections.length ? sections[sectionIndex] : null;
				boolean empty = section == null || section.hasOnlyAir();
				for (int z = z0; z <= z1; z++) {
					int row = snap.index(x0 - snap.minX, y, z - snap.minZ);
					if (empty) {
						Arrays.fill(snap.states, row, row + (x1 - x0) + 1, AIR);
						continue;
					}
					for (int x = x0; x <= x1; x++) {
						snap.states[row + (x - x0)] = section.getBlockState(x & 15, worldY & 15, z & 15);
					}
				}
			}
		}

		private void flagRow(int x) {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
			for (int z = PAD; z < snap.sizeZ - PAD; z++) {
				boolean inSpotArea = x >= spotMin && x <= spotMax && z >= spotMin && z <= spotMax;
				for (int y = PAD; y < snap.sizeY - PAD; y++) {
					int i = snap.index(x, y, z);
					BlockState state = snap.states[i];
					BlockState belowState = snap.states[snap.index(x, y - 1, z)];
					// Cheap rejects first; both checks below need a solid-ish block underneath.
					if (state == null || belowState == null || belowState.isAir() || state.isSolidRender()) {
						continue;
					}
					pos.set(snap.minX + x, snap.minY + y, snap.minZ + z);
					below.setWithOffset(pos, Direction.DOWN);

					if (state.canBeReplaced() && state.getFluidState().isEmpty()
							&& Block.canSupportCenter(level, below, Direction.UP)) {
						snap.torchSpot[i] = true;
					}
					// Zombie is the reference: a standard 2-block-tall ground monster.
					if (inSpotArea && SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(level, pos, EntityTypes.ZOMBIE)
							&& level.getBrightness(LightLayer.BLOCK, pos) <= snap.blockLightLimit) {
						snap.darkSpot[i] = true;
					}
				}
			}
		}
	}
}
