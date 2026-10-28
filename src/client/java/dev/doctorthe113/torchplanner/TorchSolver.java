package dev.doctorthe113.torchplanner;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * Picks torch positions that bring every dark spawnable block above the
 * monster spawn light limit.
 *
 * <p>Each candidate's coverage comes from simulating vanilla block-light
 * propagation through the snapshot (same per-face occlusion and dampening as
 * {@link LightEngine}), so walls, pits and overhangs shrink the area a torch
 * lights. Choosing torches is a set-cover problem; we use greedy max-coverage
 * with a lazy priority queue, then drop torches made redundant by later picks.
 *
 * <p>Ties are broken toward a diamond lattice, the perfect tiling for open
 * flat ground. Greedy is run for several lattice alignments and the plan with
 * the fewest torches wins.
 *
 * <p>Large areas are planned in {@link #TILE}-block tiles, centre first, so
 * memory stays bounded. Torches chosen for one tile count as lighting for the
 * tiles after it.
 */
public final class TorchSolver {
	/** Torch light emission. */
	static final int TORCH_LIGHT = 14;
	/** Tile edge length. Areas up to radius 63 are solved as a single tile. */
	static final int TILE = 128;

	private static final Direction[] DIRECTIONS = Direction.values();
	/** Greedy passes per tile, each with a different diamond-grid alignment. */
	private static final int GREEDY_RUNS = 16;
	/** Max rounds of {@link #mergeNeighbours}; 0 disables it (tests compare with and without). */
	static int mergePasses = 8;
	/** How many of the best greedy plans get the merge pass. */
	private static final int MERGE_STARTS = 4;
	/** Leaves a core free so the game itself doesn't stutter while a scan runs. */
	private static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

	/** A suggested torch and the dark spots it lights. */
	public record PlannedTorch(BlockPos pos, List<BlockPos> lights) {
	}

	public record Plan(List<PlannedTorch> torches, List<BlockPos> darkSpots, List<BlockPos> unreachable) {
		static final Plan EMPTY = new Plan(List.of(), List.of(), List.of());
	}

	private TorchSolver() {
	}

	static Plan solve(WorldSnapshot snap, BooleanSupplier cancelled) {
		return solve(snap, cancelled, done -> {});
	}

	/**
	 * Returns {@code null} if {@code cancelled} turns true mid-solve. {@code progress}
	 * receives the number of tiles finished so far.
	 */
	static Plan solve(WorldSnapshot snap, BooleanSupplier cancelled, IntConsumer progress) {
		int volume = snap.states.length;
		int limit = snap.blockLightLimit;
		if (limit >= TORCH_LIGHT) {
			return Plan.EMPTY;
		}

		int[] spotId = new int[volume];
		Arrays.fill(spotId, -1);
		IntArrayList spotCells = new IntArrayList();
		for (int i = 0; i < volume; i++) {
			if (snap.darkSpot[i]) {
				spotId[i] = spotCells.size();
				spotCells.add(i);
			}
		}
		int spotCount = spotCells.size();
		if (spotCount == 0) {
			return Plan.EMPTY;
		}

		// A torch can only reach dark spots within its light radius, so skip far-away candidates.
		int reach = TORCH_LIGHT - limit - 1;
		boolean[] nearSpot = dilate(snap, snap.darkSpot, reach);

		// Bucket spots by tile, then do tiles nearest the centre first so the middle gets the best layout.
		int tilesX = tileCount(snap.sizeX), tilesZ = tileCount(snap.sizeZ);
		IntArrayList[] tileSpots = new IntArrayList[tilesX * tilesZ];
		for (int s = 0; s < spotCount; s++) {
			int cell = spotCells.getInt(s);
			int t = (cell % snap.sizeX) / TILE + ((cell / snap.sizeX) % snap.sizeZ) / TILE * tilesX;
			if (tileSpots[t] == null) tileSpots[t] = new IntArrayList();
			tileSpots[t].add(s);
		}
		double midX = (tilesX - 1) / 2.0, midZ = (tilesZ - 1) / 2.0;
		Integer[] order = IntStream.range(0, tileSpots.length).boxed().toArray(Integer[]::new);
		Arrays.sort(order, Comparator.comparingDouble(t -> Math.hypot(t % tilesX - midX, t / tilesX - midZ)));

		boolean[] covered = new boolean[spotCount];
		int[] localId = new int[spotCount];
		Arrays.fill(localId, -1);
		ThreadLocal<Propagator> propagators = ThreadLocal.withInitial(() -> new Propagator(snap));
		List<PlannedTorch> torches = new ArrayList<>();
		List<BlockPos> unreachable = new ArrayList<>();
		int tilesDone = 0;

		for (int t : order) {
			if (cancelled.getAsBoolean()) return null;
			IntArrayList spots = new IntArrayList();
			if (tileSpots[t] != null) {
				for (int s : tileSpots[t]) if (!covered[s]) spots.add(s);
			}
			if (!spots.isEmpty()) {
				for (int k = 0; k < spots.size(); k++) localId[spots.getInt(k)] = k;
				int[] localCells = new int[spots.size()];
				for (int k = 0; k < spots.size(); k++) localCells[k] = spotCells.getInt(spots.getInt(k));
				List<Integer> chosenCells = solveTile(snap, t % tilesX, t / tilesX, reach, nearSpot, spotId, localId,
						localCells, propagators, cancelled);
				for (int k = 0; k < spots.size(); k++) localId[spots.getInt(k)] = -1;
				if (chosenCells == null) return null;

				// Light from this tile's torches also reaches neighbouring tiles.
				Propagator propagator = propagators.get();
				for (int cell : chosenCells) {
					int[] lit = propagator.coverage(cell, spotId, null);
					List<BlockPos> lights = new ArrayList<>(lit.length);
					for (int s : lit) {
						covered[s] = true;
						lights.add(snap.pos(spotCells.getInt(s)));
					}
					torches.add(new PlannedTorch(snap.pos(cell), List.copyOf(lights)));
				}
				for (int s : spots) {
					if (!covered[s]) unreachable.add(snap.pos(spotCells.getInt(s)));
				}
			}
			progress.accept(++tilesDone);
		}

		List<BlockPos> darkSpots = new ArrayList<>(spotCount);
		for (int s = 0; s < spotCount; s++) darkSpots.add(snap.pos(spotCells.getInt(s)));
		return new Plan(List.copyOf(torches), List.copyOf(darkSpots), List.copyOf(unreachable));
	}

	/** Number of tiles along an axis of {@code size} blocks. */
	static int tileCount(int size) {
		return (size + TILE - 1) / TILE;
	}

	/** Chooses torches for the spots marked in {@code localId}; returns their snapshot cells. */
	private static List<Integer> solveTile(WorldSnapshot snap, int tileX, int tileZ, int reach, boolean[] nearSpot,
			int[] spotId, int[] localId, int[] localCells, ThreadLocal<Propagator> propagators, BooleanSupplier cancelled) {
		int spotCount = localCells.length;
		// Candidates: anywhere a torch could reach this tile from.
		int x0 = Math.max(0, tileX * TILE - reach), x1 = Math.min(snap.sizeX - 1, tileX * TILE + TILE - 1 + reach);
		int z0 = Math.max(0, tileZ * TILE - reach), z1 = Math.min(snap.sizeZ - 1, tileZ * TILE + TILE - 1 + reach);
		IntArrayList candidateList = new IntArrayList();
		for (int y = 0; y < snap.sizeY; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					int i = snap.index(x, y, z);
					if (snap.torchSpot[i] && nearSpot[i]) candidateList.add(i);
				}
			}
		}
		int[] candidates = candidateList.toIntArray();

		// Coverage is nearly all of the work and each candidate is independent, so fan it out.
		int[][] coverage = new int[candidates.length][];
		AtomicBoolean aborted = new AtomicBoolean();
		if (!runOnPool(() -> IntStream.range(0, candidates.length).parallel().forEach(k -> {
			if (aborted.get() || ((k & 63) == 0 && cancelled.getAsBoolean())) {
				aborted.set(true);
				return;
			}
			coverage[k] = propagators.get().coverage(candidates[k], spotId, localId);
		})) || aborted.get()) {
			return null;
		}

		List<Integer> candCells = new ArrayList<>();
		List<int[]> candCover = new ArrayList<>();
		for (int k = 0; k < candidates.length; k++) {
			if (coverage[k].length > 0) {
				candCells.add(candidates[k]);
				candCover.add(coverage[k]);
			}
		}

		// Greedy is cheap next to the light simulation but myopic: which tie it takes early
		// decides how well later torches pack. Run it once per diamond-grid alignment
		// (and once ignoring the grid) and keep the plan with the fewest torches.
		int candCount = candCells.size();
		int[] latticeClass = new int[candCount];
		for (int c = 0; c < candCount; c++) {
			BlockPos p = snap.pos(candCells.get(c));
			latticeClass[c] = latticeClass(p.getX(), p.getZ(), reach);
		}
		int classes = latticeSize(reach);
		int[] alignments = IntStream.range(-1, GREEDY_RUNS - 1)
				.map(k -> k < 0 ? -1 : (int) ((long) k * classes / (GREEDY_RUNS - 1)))
				.toArray();
		List<List<Integer>> results = new ArrayList<>();
		if (!runOnPool(() -> results.addAll(Arrays.stream(alignments).parallel()
				.mapToObj(alignment -> greedy(candCover, spotCount, latticeClass, alignment))
				.toList()))) {
			return null;
		}
		// Merging can rescue a slightly worse greedy start, so polish the few best and keep the winner.
		List<List<Integer>> starts = results.stream().sorted(Comparator.comparingInt(List::size)).limit(mergePasses > 0 ? MERGE_STARTS : 1).toList();
		MergeIndex index = mergePasses > 0 ? MergeIndex.build(snap, candCells, candCover, localCells) : null;
		List<List<Integer>> polished = new ArrayList<>();
		if (!runOnPool(() -> polished.addAll(starts.parallelStream()
				.map(start -> mergePasses > 0 ? mergeNeighbours(index, candCover, start, reach, cancelled) : start)
				.toList()))) {
			return null;
		}
		if (polished.contains(null)) return null;
		List<Integer> best = polished.stream().min(Comparator.comparingInt(List::size)).orElseThrow();
		return best.stream().map(candCells::get).toList();
	}

	/** Read-only lookups for {@link #mergeNeighbours}, built once per tile and shared by parallel runs. */
	private record MergeIndex(int[] cx, int[] cy, int[] cz, int[][] spotCands, int[][] spotProj, int[][] candProj) {
		static MergeIndex build(WorldSnapshot snap, List<Integer> candCells, List<int[]> candCover, int[] spotCells) {
			int candCount = candCells.size();
			int spotCount = spotCells.length;
			int[] cx = new int[candCount], cy = new int[candCount], cz = new int[candCount];
			for (int c = 0; c < candCount; c++) {
				int cell = candCells.get(c);
				cx[c] = cell % snap.sizeX;
				cz[c] = (cell / snap.sizeX) % snap.sizeZ;
				cy[c] = cell / (snap.sizeX * snap.sizeZ);
			}
			// Each spot projected onto the 4 diagonals that bound a Manhattan ball, for quick reach checks.
			int[][] spotProj = new int[4][spotCount];
			for (int s = 0; s < spotCount; s++) {
				int cell = spotCells[s];
				int x = cell % snap.sizeX, z = (cell / snap.sizeX) % snap.sizeZ, y = cell / (snap.sizeX * snap.sizeZ);
				for (int k = 0; k < 4; k++) spotProj[k][s] = project(k, x, y, z);
			}
			int[][] candProj = new int[4][candCount];
			for (int c = 0; c < candCount; c++) {
				for (int k = 0; k < 4; k++) candProj[k][c] = project(k, cx[c], cy[c], cz[c]);
			}
			// Inverse index: which candidates light each spot.
			int[] spotCandCount = new int[spotCount];
			for (int[] cover : candCover) for (int s : cover) spotCandCount[s]++;
			int[][] spotCands = new int[spotCount][];
			for (int s = 0; s < spotCount; s++) spotCands[s] = new int[spotCandCount[s]];
			Arrays.fill(spotCandCount, 0);
			for (int c = 0; c < candCount; c++) {
				for (int s : candCover.get(c)) {
					spotCands[s][spotCandCount[s]++] = c;
				}
			}

			return new MergeIndex(cx, cy, cz, spotCands, spotProj, candProj);
		}
	}

	/**
	 * Local search after greedy. Greedy's early picks leave odd-shaped leftovers that
	 * later torches mop up, often squeezed in close to existing ones. For each pair of
	 * nearby chosen torches, look for one candidate that lights everything only those two
	 * light; if found, swap the pair for it. Repeats until nothing merges.
	 */
	private static List<Integer> mergeNeighbours(MergeIndex index, List<int[]> candCover, List<Integer> start, int reach,
			BooleanSupplier cancelled) {
		int candCount = index.cx().length, spotCount = index.spotCands().length;
		int[] cx = index.cx(), cy = index.cy(), cz = index.cz();
		int[][] spotCands = index.spotCands(), spotProj = index.spotProj(), candProj = index.candProj();

		int[] coverCount = new int[spotCount];
		boolean[] chosen = new boolean[candCount];
		List<Integer> torches = new ArrayList<>(start);
		for (int c : torches) {
			chosen[c] = true;
			for (int s : candCover.get(c)) coverCount[s]++;
		}

		boolean[] mark = new boolean[spotCount];
		IntArrayList orphaned = new IntArrayList();
		boolean improved = true;
		for (int pass = 0; improved && pass < mergePasses; pass++) {
			improved = false;
			if (cancelled.getAsBoolean()) return null;
			for (int t : new ArrayList<>(torches)) {
				if (!chosen[t]) continue; // merged away earlier this pass
				for (int n : new ArrayList<>(torches)) {
					if (n == t || !chosen[n]) continue;
					// Two torches more than 4 light-radii apart can't share one replacement.
					if (Math.abs(cx[t] - cx[n]) + Math.abs(cy[t] - cy[n]) + Math.abs(cz[t] - cz[n]) > 4 * reach) continue;

					// Spots that go dark if both t and n are removed.
					for (int s : candCover.get(t)) coverCount[s]--;
					for (int s : candCover.get(n)) coverCount[s]--;
					orphaned.clear();
					for (int s : candCover.get(t)) if (coverCount[s] == 0 && !mark[s]) { mark[s] = true; orphaned.add(s); }
					for (int s : candCover.get(n)) if (coverCount[s] == 0 && !mark[s]) { mark[s] = true; orphaned.add(s); }
					for (int s : orphaned) mark[s] = false;

					int replacement = orphaned.isEmpty() ? -1
							: findCovering(orphaned, spotCands, candCover, mark, t, n, spotProj, candProj, reach);
					if (orphaned.isEmpty() || replacement >= 0) {
						chosen[t] = chosen[n] = false;
						torches.remove(Integer.valueOf(t));
						torches.remove(Integer.valueOf(n));
						if (replacement >= 0) {
							chosen[replacement] = true;
							torches.add(replacement);
							for (int s : candCover.get(replacement)) coverCount[s]++;
						}
						improved = true;
						break;
					}
					for (int s : candCover.get(t)) coverCount[s]++;
					for (int s : candCover.get(n)) coverCount[s]++;
				}
			}
		}

		// Merges can leave a torch whose spots are all lit by others; drop those too.
		for (int k = torches.size() - 1; k >= 0; k--) {
			int[] cover = candCover.get(torches.get(k));
			boolean redundant = true;
			for (int s : cover) {
				if (coverCount[s] < 2) {
					redundant = false;
					break;
				}
			}
			if (redundant) {
				for (int s : cover) coverCount[s]--;
				torches.remove(k);
			}
		}
		return torches;
	}

	/** Projection {@code x ± y ± z} for diagonal {@code k}; Manhattan distance is the max over the 4. */
	private static int project(int k, int x, int y, int z) {
		return x + ((k & 1) == 0 ? y : -y) + ((k & 2) == 0 ? z : -z);
	}

	/** A candidate (other than {@code skipA}/{@code skipB}) lighting every spot in {@code spots}, or -1. */
	private static int findCovering(IntArrayList spots, int[][] spotCands, List<int[]> candCover, boolean[] mark,
			int skipA, int skipB, int[][] spotProj, int[][] candProj, int reach) {
		// Light paths are never shorter than Manhattan distance, so a candidate must be within
		// `reach` of every spot. Bound that with the spots' extent along the 4 diagonals.
		int[] min = new int[4], max = new int[4];
		Arrays.fill(min, Integer.MAX_VALUE);
		Arrays.fill(max, Integer.MIN_VALUE);
		for (int s : spots) {
			for (int k = 0; k < 4; k++) {
				min[k] = Math.min(min[k], spotProj[k][s]);
				max[k] = Math.max(max[k], spotProj[k][s]);
			}
		}
		for (int k = 0; k < 4; k++) {
			if (max[k] - min[k] > 2 * reach) return -1; // too spread out for any one torch
		}

		// Only candidates lighting the rarest spot can qualify.
		int rarest = spots.getInt(0);
		for (int s : spots) {
			if (spotCands[s].length < spotCands[rarest].length) rarest = s;
		}
		for (int s : spots) mark[s] = true;
		int found = -1;
		candidates:
		for (int c : spotCands[rarest]) {
			if (c == skipA || c == skipB) continue;
			for (int k = 0; k < 4; k++) {
				int p = candProj[k][c];
				if (max[k] - p > reach || p - min[k] > reach) continue candidates;
			}
			int hits = 0;
			for (int s : candCover.get(c)) {
				if (mark[s]) hits++;
			}
			if (hits == spots.size()) {
				found = c;
				break;
			}
		}
		for (int s : spots) mark[s] = false;
		return found;
	}

	private static boolean runOnPool(Runnable task) {
		try {
			POOL.submit(task).get();
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (ExecutionException e) {
			throw new RuntimeException(e.getCause());
		}
	}

	/**
	 * Lazy greedy set cover, then removal of torches made redundant by later picks.
	 * Ties prefer candidates on the diamond grid with the given alignment ({@code -1}: none).
	 * Returns the chosen candidate indices.
	 */
	private static List<Integer> greedy(List<int[]> candCover, int spotCount, int[] latticeClass, int alignment) {
		int candCount = candCover.size();
		int[] gain = new int[candCount];
		PriorityQueue<Integer> queue = new PriorityQueue<>(Math.max(1, candCount), (a, b) -> {
			if (gain[a] != gain[b]) return Integer.compare(gain[b], gain[a]);
			boolean gridA = latticeClass[a] == alignment, gridB = latticeClass[b] == alignment;
			if (gridA != gridB) return gridA ? -1 : 1;
			return Integer.compare(a, b);
		});
		for (int c = 0; c < candCount; c++) {
			gain[c] = candCover.get(c).length;
			queue.add(c);
		}

		int[] coverCount = new int[spotCount];
		List<Integer> chosen = new ArrayList<>();
		while (!queue.isEmpty()) {
			int c = queue.poll();
			int actual = 0;
			for (int s : candCover.get(c)) {
				if (coverCount[s] == 0) actual++;
			}
			if (actual == 0) {
				continue;
			}
			if (actual < gain[c]) {
				gain[c] = actual;
				queue.add(c);
				continue;
			}
			chosen.add(c);
			for (int s : candCover.get(c)) {
				coverCount[s]++;
			}
		}

		List<Integer> kept = new ArrayList<>();
		for (int k = chosen.size() - 1; k >= 0; k--) {
			int[] cover = candCover.get(chosen.get(k));
			boolean redundant = true;
			for (int s : cover) {
				if (coverCount[s] < 2) {
					redundant = false;
					break;
				}
			}
			if (redundant) {
				for (int s : cover) coverCount[s]--;
			} else {
				kept.add(chosen.get(k));
			}
		}
		return kept;
	}

	/**
	 * Diamonds of Manhattan radius r tile the plane perfectly when their centres
	 * sit on the lattice spanned by (r, r+1) and (-(r+1), r). That lattice has
	 * {@code r² + (r+1)²} cosets; this returns which one (x, z) is in.
	 */
	static int latticeClass(int x, int z, int r) {
		return Math.floorMod(r * x + (r + 1) * z, latticeSize(r));
	}

	static int latticeSize(int r) {
		return r * r + (r + 1) * (r + 1);
	}

	static boolean isLatticePoint(int x, int z, int r) {
		return latticeClass(x, z, r) == 0;
	}

	/** Marks every cell within Manhattan distance {@code radius} of a marked cell. */
	private static boolean[] dilate(WorldSnapshot snap, boolean[] source, int radius) {
		byte[] dist = new byte[source.length]; // 0 = unvisited, else distance + 1
		int[] queue = new int[source.length];
		int head = 0, tail = 0;
		for (int i = 0; i < source.length; i++) {
			if (source[i]) {
				dist[i] = 1;
				queue[tail++] = i;
			}
		}
		int sx = snap.sizeX, sxz = snap.sizeX * snap.sizeZ;
		while (head < tail) {
			int i = queue[head++];
			if (dist[i] > radius) continue;
			int x = i % sx, z = (i / sx) % snap.sizeZ, y = i / sxz;
			if (x > 0) tail = visit(dist, queue, tail, i - 1, dist[i]);
			if (x < sx - 1) tail = visit(dist, queue, tail, i + 1, dist[i]);
			if (z > 0) tail = visit(dist, queue, tail, i - sx, dist[i]);
			if (z < snap.sizeZ - 1) tail = visit(dist, queue, tail, i + sx, dist[i]);
			if (y > 0) tail = visit(dist, queue, tail, i - sxz, dist[i]);
			if (y < snap.sizeY - 1) tail = visit(dist, queue, tail, i + sxz, dist[i]);
		}
		boolean[] out = new boolean[source.length];
		for (int i = 0; i < source.length; i++) out[i] = dist[i] != 0;
		return out;
	}

	private static int visit(byte[] dist, int[] queue, int tail, int n, byte from) {
		if (dist[n] == 0) {
			dist[n] = (byte) (from + 1);
			queue[tail++] = n;
		}
		return tail;
	}

	/**
	 * Simulates one torch's light. Light never travels more than {@code TORCH_LIGHT - 1}
	 * blocks, so all bookkeeping lives in a small cube around the torch rather than
	 * arrays the size of the whole snapshot.
	 */
	private static final class Propagator {
		private static final BlockState TORCH = Blocks.TORCH.defaultBlockState();
		private static final int R = TORCH_LIGHT - 1;
		private static final int W = 2 * R + 1;

		private final WorldSnapshot snap;
		private final int[] stamp = new int[W * W * W];
		private final byte[] level = new byte[W * W * W];
		private final int[][] buckets = new int[TORCH_LIGHT + 1][];
		private final int[] bucketSize = new int[TORCH_LIGHT + 1];
		private int[] found = new int[256];
		private int run;

		Propagator(WorldSnapshot snap) {
			this.snap = snap;
			for (int l = 0; l <= TORCH_LIGHT; l++) buckets[l] = new int[64];
		}

		/**
		 * Dark spots the torch at {@code source} would light. With {@code localId} set, only
		 * spots it maps to (>= 0) are reported, as those local ids; otherwise global spot ids.
		 */
		int[] coverage(int source, int[] spotId, int[] localId) {
			run++;
			int limit = snap.blockLightLimit;
			int foundCount = 0;
			Arrays.fill(bucketSize, 0);

			int sx = snap.sizeX, sz = snap.sizeZ, sy = snap.sizeY, sxz = sx * sz;
			int ox = source % sx - R, oz = (source / sx) % sz - R, oy = source / sxz - R;
			set(source, TORCH_LIGHT, local(R, R, R));

			for (int l = TORCH_LIGHT; l > limit; l--) {
				for (int b = 0; b < bucketSize[l]; b++) {
					int i = buckets[l][b];
					int x = i % sx, z = (i / sx) % sz, y = i / sxz;
					if (level[local(x - ox, y - oy, z - oz)] != l) continue; // superseded by a brighter path

					int id = spotId[i];
					if (id >= 0 && localId != null) id = localId[id];
					if (id >= 0) {
						if (foundCount == found.length) found = Arrays.copyOf(found, foundCount * 2);
						found[foundCount++] = id;
					}

					BlockState from = i == source ? TORCH : snap.states[i];
					for (Direction dir : DIRECTIONS) {
						int nx = x + dir.getStepX(), ny = y + dir.getStepY(), nz = z + dir.getStepZ();
						if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) continue;
						int n = (ny * sz + nz) * sx + nx;
						BlockState to = snap.states[n];
						if (to == null) continue;
						int damp = LightEngine.getLightDampeningInto(from, to, dir, to.getLightDampening());
						int nl = l - Math.max(1, damp);
						if (nl <= limit) continue;
						int li = local(nx - ox, ny - oy, nz - oz);
						if (stamp[li] == run && level[li] >= nl) continue;
						set(n, nl, li);
					}
				}
			}
			return Arrays.copyOf(found, foundCount);
		}

		private static int local(int x, int y, int z) {
			return (y * W + z) * W + x;
		}

		private void set(int cell, int light, int li) {
			stamp[li] = run;
			level[li] = (byte) light;
			int[] bucket = buckets[light];
			if (bucketSize[light] == bucket.length) buckets[light] = bucket = Arrays.copyOf(bucket, bucket.length * 2);
			bucket[bucketSize[light]++] = cell;
		}
	}
}
