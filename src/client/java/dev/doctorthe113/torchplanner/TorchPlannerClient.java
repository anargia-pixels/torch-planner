package dev.doctorthe113.torchplanner;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class TorchPlannerClient implements ClientModInitializer {
	public static final String MOD_ID = "torchplanner";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** How often to check whether planned torches have been placed. */
	private static final int PRUNE_TICKS = 10;
	/** Lighting only changes near the player, so only re-check markers this many chunks out. */
	private static final int PRUNE_CHUNKS = 2;
	/** Gold torch markers are drawn this many chunks out; dark-spot squares a shorter distance. */
	private static final int TORCH_DRAW_CHUNKS = 16;
	private static final int SPOT_DRAW_CHUNKS = 4;
	/** Cap on drawn dark-spot squares per kind. */
	private static final int MAX_SPOT_MARKERS = 3000;
	/** Time per tick spent reading the world for a scan, so big scans don't freeze the game. */
	private static final long CAPTURE_BUDGET_NANOS = 4_000_000;
	/**
	 * Torches may go this far outside the planned area. Without it, torches at the
	 * edge are squeezed inward and only half their light lands on the area.
	 */
	private static final int TORCH_MARGIN = 13;
	/** Longest a scan waits for the area's chunks to finish loading (e.g. right after joining). */
	private static final int MAX_CHUNK_WAIT_TICKS = 100;
	private static final int PROGRESS_MESSAGE_TICKS = 20;

	private static final GizmoStyle TORCH_STYLE = GizmoStyle.strokeAndFill(0xFFFFC21A, 2.5f, 0x66FFC21A);
	private static final GizmoStyle DARK_STYLE = GizmoStyle.fill(0x55FF2020);
	private static final GizmoStyle UNREACHABLE_STYLE = GizmoStyle.fill(0x88A020FF);

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Torch Planner solver");
		t.setDaemon(true);
		return t;
	});
	/** Bumped whenever a running scan's result would be stale; the scan checks it and bails. */
	private static final AtomicInteger generation = new AtomicInteger();

	private static KeyMapping toggleKey;
	/** The frozen plan. Only replaced by a scan; otherwise markers are just removed as they're done. */
	private static ActivePlan plan = ActivePlan.EMPTY;
	private static boolean scanRequested;
	/** Non-null while a scan is reading the world. */
	private static WorldSnapshot.Builder capture;
	private static volatile boolean solving;
	private static final AtomicInteger tilesSolved = new AtomicInteger();
	private static volatile int tilesTotal;
	private static ClientLevel plannedLevel;
	private static int ticksUntilPrune;
	private static int chunkWaitTicks;
	private static int ticksUntilProgress;

	@Override
	public void onInitializeClient() {
		TorchPlannerConfig.load();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.torchplanner.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_F7, category));

		ClientTickEvents.END_CLIENT_TICK.register(TorchPlannerClient::tick);
		LevelRenderEvents.END_EXTRACTION.register(context -> render());
	}

	/** Toggles the overlay and tells the player. Used by the keybind and the config screen. */
	public static void setEnabled(boolean enabled) {
		TorchPlannerConfig.get().enabled = enabled;
		TorchPlannerConfig.save();
		clearPlan();
		scanRequested = enabled;
		message(enabled ? "message.torchplanner.enabled" : "message.torchplanner.disabled");
	}

	/** Throws away the current plan and scans again around the player. */
	public static void rescan() {
		if (!TorchPlannerConfig.get().enabled) {
			setEnabled(true);
			return;
		}
		clearPlan();
		scanRequested = true;
	}

	/** Drops the plan and stops any scan in progress. */
	private static void clearPlan() {
		generation.incrementAndGet();
		plan = ActivePlan.EMPTY;
		capture = null;
	}

	private static void message(String key, Object... args) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.translatable(key, args));
		}
	}

	private static void tick(Minecraft mc) {
		while (toggleKey.consumeClick()) {
			setEnabled(!TorchPlannerConfig.get().enabled);
		}
		if (!TorchPlannerConfig.get().enabled) {
			return;
		}
		if (mc.level == null || mc.player == null) {
			plannedLevel = null;
			return;
		}
		// New world or dimension: the old plan means nothing here.
		if (mc.level != plannedLevel) {
			plannedLevel = mc.level;
			clearPlan();
			scanRequested = true;
		}

		if (scanRequested && capture == null && !solving) {
			// The plan is frozen once made, so don't make it with holes where chunks are still loading.
			if (!areaLoaded(mc) && ++chunkWaitTicks < MAX_CHUNK_WAIT_TICKS) {
				return;
			}
			chunkWaitTicks = 0;
			scanRequested = false;
			TorchPlannerConfig config = TorchPlannerConfig.get();
			int radius = config.horizontalRadius;
			capture = new WorldSnapshot.Builder(mc.level, mc.player.blockPosition(),
					radius, radius + TORCH_MARGIN, config.verticalRadius);
			ticksUntilProgress = 0;
		}

		if (capture != null) {
			boolean done = capture.step(System.nanoTime() + CAPTURE_BUDGET_NANOS);
			if (done) {
				startSolve(mc, capture);
				capture = null;
			} else if (--ticksUntilProgress <= 0) {
				ticksUntilProgress = PROGRESS_MESSAGE_TICKS;
				message("message.torchplanner.reading", Math.round(capture.progress() * 100));
			}
			return;
		}
		if (solving) {
			if (--ticksUntilProgress <= 0) {
				ticksUntilProgress = PROGRESS_MESSAGE_TICKS;
				int total = Math.max(1, tilesTotal);
				message("message.torchplanner.planning", Math.round(100f * tilesSolved.get() / total));
			}
			return;
		}

		if (--ticksUntilPrune <= 0) {
			ticksUntilPrune = PRUNE_TICKS;
			prune(mc.level, mc.player.blockPosition());
		}
	}

	/** Whether the chunks the scan will read are loaded, as far as render distance allows. */
	private static boolean areaLoaded(Minecraft mc) {
		BlockPos center = mc.player.blockPosition();
		int viewable = Math.max(16, (mc.options.getEffectiveRenderDistance() - 1) * 16);
		int r = Math.min(TorchPlannerConfig.get().horizontalRadius, viewable);
		for (int x = center.getX() - r; x <= center.getX() + r + 15; x += 16) {
			for (int z = center.getZ() - r; z <= center.getZ() + r + 15; z += 16) {
				if (!mc.level.hasChunkAt(Math.min(x, center.getX() + r), Math.min(z, center.getZ() + r))) {
					return false;
				}
			}
		}
		return true;
	}

	private static void startSolve(Minecraft mc, WorldSnapshot.Builder finished) {
		WorldSnapshot snap = finished.result();
		int missingChunks = finished.missingChunks();
		int gen = generation.get();
		solving = true;
		tilesSolved.set(0);
		tilesTotal = TorchSolver.tileCount(snap.sizeX) * TorchSolver.tileCount(snap.sizeZ);
		ticksUntilProgress = 0;
		WORKER.execute(() -> {
			try {
				TorchSolver.Plan result = TorchSolver.solve(snap, () -> generation.get() != gen, tilesSolved::set);
				if (result != null) {
					ActivePlan active = new ActivePlan(result);
					mc.execute(() -> {
						if (generation.get() != gen) return;
						plan = active;
						message(missingChunks > 0 ? "message.torchplanner.planned_partial" : "message.torchplanner.planned",
								active.torchCount());
					});
				}
			} catch (Throwable t) {
				LOGGER.error("Torch planning failed", t);
			} finally {
				solving = false;
			}
		});
	}

	/**
	 * Drops markers the player has dealt with: dark spots that are now lit or blocked, and
	 * torches whose spots are all handled (by that torch or any other light). Rescans once
	 * every planned torch is gone.
	 */
	private static void prune(ClientLevel level, BlockPos player) {
		int limit = level.dimensionType().monsterSpawnBlockLightLimit();
		Map<BlockPos, Boolean> handled = new HashMap<>();
		// Unloaded chunks read as air, which would wrongly count as "handled".
		Predicate<BlockPos> isHandled = p -> handled.computeIfAbsent(p, q -> level.hasChunkAt(q)
				&& (level.getBrightness(LightLayer.BLOCK, q) > limit
						|| !SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(level, q, EntityTypes.ZOMBIE)));
		plan.prune(player, PRUNE_CHUNKS, isHandled);

		if (plan.finished()) {
			message("message.torchplanner.done");
			clearPlan();
			scanRequested = true;
		}
	}

	private static void render() {
		TorchPlannerConfig config = TorchPlannerConfig.get();
		Minecraft mc = Minecraft.getInstance();
		if (!config.enabled || mc.player == null) {
			return;
		}
		BlockPos player = mc.player.blockPosition();
		boolean onTop = config.showThroughWalls;

		plan.forTorchesNear(player, TORCH_DRAW_CHUNKS, torch -> {
			BlockPos t = torch.pos();
			// Roughly the torch's own shape, so it reads as "put one here".
			AABB box = new AABB(t.getX() + 0.375, t.getY(), t.getZ() + 0.375,
					t.getX() + 0.625, t.getY() + 0.65, t.getZ() + 0.625);
			show(Gizmos.cuboid(box, TORCH_STYLE), onTop);
		});

		if (config.showDarkSpots) {
			int[] budget = {MAX_SPOT_MARKERS};
			plan.forDarkSpotsNear(player, SPOT_DRAW_CHUNKS, spots -> drawFloorMarkers(spots, DARK_STYLE, onTop, budget));
		}
		// Spots no floor torch in range can light (e.g. a pillar top or a sealed pocket): always worth seeing.
		int[] budget = {MAX_SPOT_MARKERS};
		plan.forUnreachableNear(player, SPOT_DRAW_CHUNKS, spots -> drawFloorMarkers(spots, UNREACHABLE_STYLE, onTop, budget));
	}

	private static void drawFloorMarkers(List<BlockPos> spots, GizmoStyle style, boolean onTop, int[] budget) {
		for (BlockPos s : spots) {
			if (budget[0]-- <= 0) return;
			AABB box = new AABB(s.getX() + 0.05, s.getY() + 0.01, s.getZ() + 0.05,
					s.getX() + 0.95, s.getY() + 0.03, s.getZ() + 0.95);
			show(Gizmos.cuboid(box, style), onTop);
		}
	}

	private static void show(GizmoProperties gizmo, boolean onTop) {
		if (onTop) gizmo.setAlwaysOnTop();
	}
}
