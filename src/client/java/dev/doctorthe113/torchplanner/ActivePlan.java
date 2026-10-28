package dev.doctorthe113.torchplanner;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The frozen plan as shown in the world, grouped by chunk so drawing and
 * pruning only touch chunks near the player, however large the scan was.
 * Client thread only.
 */
final class ActivePlan {
	static final ActivePlan EMPTY = new ActivePlan(TorchSolver.Plan.EMPTY);

	private final Long2ObjectOpenHashMap<List<TorchSolver.PlannedTorch>> torches = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<List<BlockPos>> darkSpots = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<List<BlockPos>> unreachable = new Long2ObjectOpenHashMap<>();
	private final boolean startedWithTorches;
	private int torchCount;

	ActivePlan(TorchSolver.Plan plan) {
		for (TorchSolver.PlannedTorch torch : plan.torches()) {
			torches.computeIfAbsent(ChunkPos.pack(torch.pos()), k -> new ArrayList<>()).add(torch);
		}
		group(plan.darkSpots(), darkSpots);
		group(plan.unreachable(), unreachable);
		torchCount = plan.torches().size();
		startedWithTorches = torchCount > 0;
	}

	private static void group(List<BlockPos> spots, Long2ObjectOpenHashMap<List<BlockPos>> into) {
		for (BlockPos p : spots) {
			into.computeIfAbsent(ChunkPos.pack(p), k -> new ArrayList<>()).add(p);
		}
	}

	int torchCount() {
		return torchCount;
	}

	/** True once every torch the plan started with has been dealt with. */
	boolean finished() {
		return startedWithTorches && torchCount == 0;
	}

	void forTorchesNear(BlockPos center, int chunkRadius, Consumer<TorchSolver.PlannedTorch> action) {
		forNear(torches, center, chunkRadius, list -> list.forEach(action));
	}

	void forDarkSpotsNear(BlockPos center, int chunkRadius, Consumer<List<BlockPos>> action) {
		forNear(darkSpots, center, chunkRadius, action);
	}

	void forUnreachableNear(BlockPos center, int chunkRadius, Consumer<List<BlockPos>> action) {
		forNear(unreachable, center, chunkRadius, action);
	}

	/**
	 * Removes markers near {@code center} that {@code handled} says are dealt with. A torch
	 * goes once every spot it lights is handled.
	 */
	void prune(BlockPos center, int chunkRadius, Predicate<BlockPos> handled) {
		forNear(darkSpots, center, chunkRadius, list -> list.removeIf(handled));
		forNear(unreachable, center, chunkRadius, list -> list.removeIf(handled));
		// A torch's light reaches ~1 chunk past its own, so look one chunk wider for torches.
		forNear(torches, center, chunkRadius + 1, list -> {
			int before = list.size();
			list.removeIf(t -> t.lights().stream().allMatch(handled));
			torchCount -= before - list.size();
		});
	}

	private static <T> void forNear(Long2ObjectOpenHashMap<List<T>> map, BlockPos center, int chunkRadius,
			Consumer<List<T>> action) {
		if (map.isEmpty()) return;
		int cx = center.getX() >> 4, cz = center.getZ() >> 4;
		for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
			for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
				List<T> list = map.get(ChunkPos.pack(cx + dx, cz + dz));
				if (list != null && !list.isEmpty()) action.accept(list);
			}
		}
	}
}
