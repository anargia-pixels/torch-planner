package dev.doctorthe113.torchplanner;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorchSolverTest {
	private static final int SIZE = 87;
	private static final int HEIGHT = 8;
	private static final int FLOOR_Y = 1;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Stone floor at y=0, air above; {@code wall} marks extra stone columns (x, z packed as x * SIZE + z). */
	private static WorldSnapshot room(IntPredicate wall) {
		return room(wall, SIZE);
	}

	/** Like {@link #room(IntPredicate)}, but dark spots only within {@code spotRadius} of the centre, as the client scans. */
	private static WorldSnapshot room(IntPredicate wall, int spotRadius) {
		WorldSnapshot snap = new WorldSnapshot(0, 0, 0, SIZE, HEIGHT, SIZE, 0);
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int y = 0; y < HEIGHT; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					boolean solid = y == 0 || wall.test(x * SIZE + z);
					snap.states[snap.index(x, y, z)] = solid ? stone : air;
				}
			}
		}
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				if (wall.test(x * SIZE + z)) continue;
				int i = snap.index(x, FLOOR_Y, z);
				snap.darkSpot[i] = Math.abs(x - SIZE / 2) <= spotRadius && Math.abs(z - SIZE / 2) <= spotRadius;
				snap.torchSpot[i] = true;
			}
		}
		return snap;
	}

	@Test
	void flatFloorIsFullyLitWithNearIdealTorchCount() {
		// As in game: plan a 61x61 area, torches allowed up to 13 blocks outside it.
		int shown = SIZE / 2 - 13;
		BlockPos center = new BlockPos(SIZE / 2, FLOOR_Y, SIZE / 2);
		TorchSolver.Plan plan = TorchSolver.solve(room(c -> false, shown), () -> false);

		assertTrue(plan.unreachable().isEmpty());
		// Independent check: on open ground light reaches 13 blocks (Manhattan) at level >= 1.
		for (BlockPos spot : plan.darkSpots()) {
			boolean lit = plan.torches().stream().map(TorchSolver.PlannedTorch::pos).anyMatch(t -> t.distManhattan(spot) <= 13);
			assertTrue(lit, "unlit spot " + spot);
			assertTrue(plan.torches().stream().anyMatch(t -> t.lights().contains(spot)), "no torch assigned to " + spot);
		}
		List<BlockPos> inside = plan.torches().stream().map(TorchSolver.PlannedTorch::pos)
				.filter(t -> Math.abs(t.getX() - center.getX()) <= shown && Math.abs(t.getZ() - center.getZ()) <= shown)
				.toList();
		long onGrid = plan.torches().stream().map(TorchSolver.PlannedTorch::pos)
				.filter(t -> TorchSolver.isLatticePoint(t.getX(), t.getZ(), 13)).count();
		// Perfect diamond tiling needs area / 365 torches; edges cost some extra.
		double ideal = Math.pow(2 * shown + 1, 2) / 365;
		System.out.printf("flat %dx%d: %d torches (%d inside, %d on diamond grid), tiling bound %.1f%n",
				2 * shown + 1, 2 * shown + 1, plan.torches().size(), inside.size(), onGrid, ideal);
		assertTrue(plan.torches().size() <= ideal * 2, "too many torches: " + plan.torches().size());
	}

	@Test
	void wallsBlockLightSoMoreTorchesAreNeeded() {
		int flat = TorchSolver.solve(room(c -> false), () -> false).torches().size();

		// Full-height wall along x = 30 with a 1-block doorway at z = 5.
		IntPredicate wall = c -> c / SIZE == 30 && c % SIZE != 5;
		WorldSnapshot snap = room(wall);
		TorchSolver.Plan plan = TorchSolver.solve(snap, () -> false);

		assertTrue(plan.unreachable().isEmpty());
		assertTrue(plan.torches().stream().map(TorchSolver.PlannedTorch::pos).anyMatch(t -> t.getX() < 30));
		assertTrue(plan.torches().stream().map(TorchSolver.PlannedTorch::pos).anyMatch(t -> t.getX() > 30));
		System.out.printf("walled: %d torches vs %d flat%n", plan.torches().size(), flat);
		assertTrue(plan.torches().size() > flat);
	}

	@Test
	void defaultRadiusSolvesQuickly() {
		timeCaveBox(24, 8);
	}

	@Test
	void largestRadiusSolvesQuickly() {
		timeCaveBox(48, 16);
	}

	/** Radius + 13 margin, a cave-like box: a floor every 4th layer with pillars. */
	private static void timeCaveBox(int radius, int verticalRadius) {
		int size = 2 * (radius + 13) + 1, height = 2 * verticalRadius + 1;
		WorldSnapshot snap = new WorldSnapshot(0, 0, 0, size, height, size, 0);
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < size; z++) {
				for (int x = 0; x < size; x++) {
					boolean solid = y % 4 == 0 || (x % 7 == 0 && z % 5 == 0);
					snap.states[snap.index(x, y, z)] = solid ? stone : air;
					if (!solid && y % 4 == 1 && y > 0) {
						snap.darkSpot[snap.index(x, y, z)] = true;
						snap.torchSpot[snap.index(x, y, z)] = true;
					}
				}
			}
		}
		long start = System.nanoTime();
		TorchSolver.Plan plan = TorchSolver.solve(snap, () -> false);
		long ms = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("cave box r=%d h=%d: %d torches, %d dark spots, %d ms%n",
				radius, verticalRadius, plan.torches().size(), plan.darkSpots().size(), ms);
		assertTrue(plan.unreachable().isEmpty());
	}

	@Test
	void maxRadiusFlatSurfaceIsTiledAndFullyLit() {
		// Radius 256 + 13 margin, as the client captures it: one floor, dark spots only within 256.
		int radius = 256, size = 2 * (radius + 13) + 1, height = 2 * 8 + 1;
		WorldSnapshot snap = new WorldSnapshot(0, 0, 0, size, height, size, 0);
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		int floor = 8;
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < size; z++) {
				for (int x = 0; x < size; x++) {
					int i = snap.index(x, y, z);
					snap.states[i] = y < floor ? stone : air;
					if (y == floor) {
						snap.torchSpot[i] = true;
						snap.darkSpot[i] = Math.abs(x - size / 2) <= radius && Math.abs(z - size / 2) <= radius;
					}
				}
			}
		}
		long start = System.nanoTime();
		TorchSolver.Plan plan = TorchSolver.solve(snap, () -> false);
		long ms = (System.nanoTime() - start) / 1_000_000;
		double ideal = Math.pow(2 * radius + 1, 2) / 365;
		System.out.printf("flat r=256: %d torches (tiling bound %.0f), %d dark spots, %d ms, heap %d MB%n",
				plan.torches().size(), ideal, plan.darkSpots().size(), ms,
				(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);

		assertTrue(plan.unreachable().isEmpty());
		java.util.Set<BlockPos> lit = new java.util.HashSet<>();
		plan.torches().forEach(t -> lit.addAll(t.lights()));
		assertEquals(plan.darkSpots().size(), lit.size(), "every spot is assigned to a torch");
		assertTrue(plan.torches().size() <= ideal * 1.6, "too many torches: " + plan.torches().size());
	}

	/** Uneven cave floor with boulders and a ceiling, deterministic. */
	private static WorldSnapshot roughCave(int radius, long seed) {
		int size = 2 * (radius + 13) + 1, height = 24;
		java.util.Random random = new java.util.Random(seed);
		// Smooth-ish heightmap: random walk per row, blended with the row above.
		int[][] floor = new int[size][size];
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				int prev = z > 0 ? floor[x][z - 1] : 8;
				int above = x > 0 ? floor[x - 1][z] : 8;
				int h = (prev + above) / 2 + random.nextInt(3) - 1;
				floor[x][z] = Math.clamp(h, 4, 12);
			}
		}
		WorldSnapshot snap = new WorldSnapshot(0, 0, 0, size, height, size, 0);
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				boolean boulder = random.nextInt(40) == 0;
				for (int y = 0; y < height; y++) {
					boolean solid = y < floor[x][z] || y >= floor[x][z] + 6 || (boulder && y < floor[x][z] + 3);
					snap.states[snap.index(x, y, z)] = solid ? stone : air;
				}
			}
		}
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				int y = floor[x][z];
				int i = snap.index(x, y, z);
				if (snap.states[i] != air) continue;
				snap.torchSpot[i] = true;
				snap.darkSpot[i] = Math.abs(x - size / 2) <= radius && Math.abs(z - size / 2) <= radius;
			}
		}
		return snap;
	}

	@Test
	void mergingReducesCrowdedTorchesInCaves() {
		int before = 0, after = 0;
		int closeBefore = 0, closeAfter = 0;
		for (long seed = 1; seed <= 5; seed++) {
			TorchSolver.mergePasses = 0;
			TorchSolver.Plan greedy = TorchSolver.solve(roughCave(40, seed), () -> false);
			TorchSolver.mergePasses = 8;
			long start = System.nanoTime();
			TorchSolver.Plan merged = TorchSolver.solve(roughCave(40, seed), () -> false);
			long ms = (System.nanoTime() - start) / 1_000_000;
			assertTrue(merged.unreachable().size() <= greedy.unreachable().size());
			java.util.Set<BlockPos> lit = new java.util.HashSet<>();
			merged.torches().forEach(t -> lit.addAll(t.lights()));
			assertEquals(merged.darkSpots().size() - merged.unreachable().size(), lit.size(), "every reachable spot lit");
			before += greedy.torches().size();
			after += merged.torches().size();
			closeBefore += closePairs(greedy);
			closeAfter += closePairs(merged);
			System.out.printf("rough cave seed %d: greedy %d torches -> merged %d (%d ms)%n",
					seed, greedy.torches().size(), merged.torches().size(), ms);
		}
		System.out.printf("rough caves total: %d -> %d torches; pairs within 12 blocks: %d -> %d%n",
				before, after, closeBefore, closeAfter);
		assertTrue(after < before, "merging should save torches");
	}

	private static int closePairs(TorchSolver.Plan plan) {
		int count = 0;
		List<TorchSolver.PlannedTorch> t = plan.torches();
		for (int a = 0; a < t.size(); a++) {
			for (int b = a + 1; b < t.size(); b++) {
				if (t.get(a).pos().distManhattan(t.get(b).pos()) <= 12) count++;
			}
		}
		return count;
	}

	@Test
	void latticeTilesThePlane() {
		// Every point is within distance 13 of exactly one lattice point inside a 365-cell period.
		int r = 13;
		for (int x = 0; x < 365; x++) {
			int owners = 0;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -(r - Math.abs(dx)); dz <= r - Math.abs(dx); dz++) {
					if (TorchSolver.isLatticePoint(x + dx, 7 + dz, r)) owners++;
				}
			}
			assertEquals(1, owners, "x=" + x);
		}
	}
}
