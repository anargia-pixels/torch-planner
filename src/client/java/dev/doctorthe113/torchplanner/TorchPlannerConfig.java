package dev.doctorthe113.torchplanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** User settings, persisted to config/torchplanner.json. */
public class TorchPlannerConfig {
	public static final List<Integer> HORIZONTAL_RADII = List.of(16, 24, 32, 48, 64, 96, 128, 192, 256);
	public static final List<Integer> VERTICAL_RADII = List.of(4, 8, 12, 16);

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("torchplanner.json");

	private static TorchPlannerConfig instance = new TorchPlannerConfig();

	public boolean enabled = true;
	public boolean showDarkSpots = true;
	public boolean showThroughWalls = false;
	public int horizontalRadius = 24;
	public int verticalRadius = 8;

	public static TorchPlannerConfig get() {
		return instance;
	}

	public static void load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				TorchPlannerConfig loaded = GSON.fromJson(reader, TorchPlannerConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (Exception e) {
				TorchPlannerClient.LOGGER.warn("Failed to read {}, using defaults", PATH, e);
			}
		}
		instance.horizontalRadius = Math.clamp(instance.horizontalRadius, 8, 256);
		instance.verticalRadius = Math.clamp(instance.verticalRadius, 2, 32);
	}

	public static void save() {
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			GSON.toJson(instance, writer);
		} catch (IOException e) {
			TorchPlannerClient.LOGGER.warn("Failed to write {}", PATH, e);
		}
	}
}
