package dev.doctorthe113.torchplanner;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class TorchPlannerConfigScreen extends Screen {
	private static final int ROW = 24;
	private static final int WIDTH = 210;

	private final Screen parent;

	public TorchPlannerConfigScreen(Screen parent) {
		super(Component.translatable("config.torchplanner.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		TorchPlannerConfig config = TorchPlannerConfig.get();
		int x = (width - WIDTH) / 2;
		int y = height / 6;

		StringWidget title = new StringWidget(0, y - 24, width, 9, this.title, font);
		addRenderableWidget(title);

		addRenderableWidget(CycleButton.onOffBuilder(config.enabled)
				.create(x, y, WIDTH, 20, Component.translatable("config.torchplanner.enabled"),
						(button, value) -> TorchPlannerClient.setEnabled(value)));
		y += ROW;

		addRenderableWidget(CycleButton.onOffBuilder(config.showDarkSpots)
				.withTooltip(value -> Tooltip.create(Component.translatable("config.torchplanner.show_dark_spots.tooltip")))
				.create(x, y, WIDTH, 20, Component.translatable("config.torchplanner.show_dark_spots"),
						(button, value) -> {
							config.showDarkSpots = value;
							TorchPlannerConfig.save();
						}));
		y += ROW;

		addRenderableWidget(CycleButton.onOffBuilder(config.showThroughWalls)
				.withTooltip(value -> Tooltip.create(Component.translatable("config.torchplanner.through_walls.tooltip")))
				.create(x, y, WIDTH, 20, Component.translatable("config.torchplanner.through_walls"),
						(button, value) -> {
							config.showThroughWalls = value;
							TorchPlannerConfig.save();
						}));
		y += ROW;

		addRenderableWidget(CycleButton.<Integer>builder(v -> Component.literal(v + " blocks"), nearest(TorchPlannerConfig.HORIZONTAL_RADII, config.horizontalRadius))
				.withValues(TorchPlannerConfig.HORIZONTAL_RADII)
				.withTooltip(value -> Tooltip.create(Component.translatable("config.torchplanner.horizontal_radius.tooltip")))
				.create(x, y, WIDTH, 20, Component.translatable("config.torchplanner.horizontal_radius"),
						(button, value) -> {
							config.horizontalRadius = value;
							TorchPlannerConfig.save();
						}));
		y += ROW;

		addRenderableWidget(CycleButton.<Integer>builder(v -> Component.literal(v + " blocks"), nearest(TorchPlannerConfig.VERTICAL_RADII, config.verticalRadius))
				.withValues(TorchPlannerConfig.VERTICAL_RADII)
				.withTooltip(value -> Tooltip.create(Component.translatable("config.torchplanner.vertical_radius.tooltip")))
				.create(x, y, WIDTH, 20, Component.translatable("config.torchplanner.vertical_radius"),
						(button, value) -> {
							config.verticalRadius = value;
							TorchPlannerConfig.save();
						}));
		y += ROW + 8;

		Button rescan = addRenderableWidget(Button.builder(Component.translatable("config.torchplanner.rescan"), button -> {
					TorchPlannerClient.rescan();
					minecraft.gui.setScreen(null); // back to the game so the new plan is visible
				})
				.bounds(x, y, WIDTH, 20)
				.tooltip(Tooltip.create(Component.translatable("config.torchplanner.rescan.tooltip")))
				.build());
		rescan.active = minecraft.level != null;
		y += ROW;

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.bounds(x, y, WIDTH, 20)
				.build());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/** Keeps hand-edited config values usable with the fixed choices. */
	private static int nearest(java.util.List<Integer> options, int value) {
		int best = options.getFirst();
		for (int option : options) {
			if (Math.abs(option - value) < Math.abs(best - value)) best = option;
		}
		return best;
	}
}
