package com.zfy.zcinema.client.gui;

import com.zfy.zcinema.ZCinema;
import com.zfy.zcinema.blockentity.CinemaScreenBlockEntity;
import com.zfy.zcinema.client.playback.ClientPlayback;
import com.zfy.zcinema.client.playback.PlaybackSession;
import com.zfy.zcinema.gui.CinemaScreenMenu;
import com.zfy.zcinema.net.packets.C2SControlPacket;
import com.zfy.zcinema.net.packets.C2SSetUrlPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The screen's control panel: URL box, play/pause and a seek slider. Everyone who opens it drives
 * the same server-owned timeline, so a drag never just moves this client - it rewrites playback
 * for every viewer at once.
 */
public class CinemaScreenUI extends AbstractContainerScreen<CinemaScreenMenu> {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 118;

    private final CinemaScreenBlockEntity screen;

    private EditBox urlField;
    private Button playButton;
    private SeekSlider slider;

    public static CinemaScreenUI create(CinemaScreenMenu menu, net.minecraft.world.entity.player.Inventory inventory,
                                        Component title) {
        return new CinemaScreenUI(menu, inventory, title);
    }

    public CinemaScreenUI(CinemaScreenMenu menu, net.minecraft.world.entity.player.Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
        this.screen = ClientPlayback.find(menu.pos());
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        int left = this.leftPos;
        int top = this.topPos;

        urlField = new EditBox(this.font, left + 8, top + 20, 186, 18, Component.translatable("gui.zcinema.url"));
        urlField.setMaxLength(2048);
        if (screen != null) urlField.setValue(screen.clientUrl());
        addRenderableWidget(urlField);

        addRenderableWidget(Button.builder(Component.translatable("gui.zcinema.load"), button -> {
            if (screen != null) {
                ClientPacketDistributor.sendToServer(new C2SSetUrlPacket(screen.getBlockPos(), urlField.getValue()));
            }
        }).bounds(left + 198, top + 20, 50, 18).build());

        playButton = addRenderableWidget(Button.builder(Component.empty(), button -> togglePlay())
                .bounds(left + 8, top + 44, 54, 20).build());
        updatePlayLabel();

        addRenderableWidget(Button.builder(Component.translatable("gui.zcinema.remove"), button -> {
            if (screen != null) {
                ClientPlayback.control(screen.getBlockPos(), C2SControlPacket.Action.REMOVE);
            }
            onClose();
        }).bounds(left + 8, top + 68, 54, 18).build());

        slider = new SeekSlider(left + 66, top + 44, 182, 20);
        addRenderableWidget(slider);
    }

    private void togglePlay() {
        if (screen == null) return;
        boolean playing = screen.clientPlaying();
        ClientPlayback.control(screen.getBlockPos(), playing
                ? C2SControlPacket.Action.PAUSE
                : C2SControlPacket.Action.PLAY);
        updatePlayLabel();
    }

    private void updatePlayLabel() {
        if (playButton == null || screen == null) return;
        boolean playing = screen.clientPlaying();
        playButton.setMessage(Component.translatable(playing ? "gui.zcinema.pause" : "gui.zcinema.play"));
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (screen != null && ClientPlayback.find(screen.getBlockPos()) != screen) {
            // The screen was taken down while the panel was open.
            onClose();
            return;
        }
        if (urlField != null && !urlField.isFocused() && screen != null
                && !urlField.getValue().equals(screen.clientUrl())) {
            urlField.setValue(screen.clientUrl());
        }
        updatePlayLabel();
        if (slider != null) slider.refresh();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        urlField.render(graphics, mouseX, mouseY, partialTick);

        String status = ClientPlayback.status(screen.getBlockPos()) == PlaybackSession.Status.ERROR
                ? statusKey() : Component.translatable(statusKey()).getString();
        if (screen != null) {
            ClientPlayback.SeekStatus seek = ClientPlayback.seekStatus(screen.getBlockPos());
            if (seek.noRange()) {
                // Rewinding a source that ignores Range requests costs a full re-decode; say so
                // instead of looking like a hang.
                status = status + "  "
                        + Component.translatable("gui.zcinema.status.no_range").getString();
            }
        }
        graphics.drawString(this.font, net.minecraft.network.chat.Component.literal(status),
                this.leftPos + 8, this.topPos + 92, 0xFFAAAAAA, false);
        graphics.drawString(this.font, timeText(), this.leftPos + 8, this.topPos + 104, 0xFFCCCCCC, false);
    }

    /**
     * This menu holds no inventory, so the vanilla player-inventory label has nothing to say.
     * Draw the panel title alone - and in a light colour that is actually readable on the dark
     * backdrop (the vanilla title colour assumes a stone-grey inventory panel).
     */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xFFE0E0E0, false);
    }

    private String statusKey() {
        if (screen == null) return "gui.zcinema.status.idle";
        BlockPos pos = screen.getBlockPos();
        PlaybackSession.Status status = ClientPlayback.status(pos);
        if (status == PlaybackSession.Status.ERROR) {
            String reason = ClientPlayback.errorMessage(pos);
            return reason == null || reason.isBlank() ? "gui.zcinema.status.error" : reason;
        }
        if (status == PlaybackSession.Status.LOADING && ClientPlayback.seeking(pos)) {
            return "gui.zcinema.status.seeking";
        }
        return switch (status) {
            case LOADING -> "gui.zcinema.status.loading";
            case PLAYING -> "gui.zcinema.status.playing";
            case PAUSED -> "gui.zcinema.status.paused";
            case ENDED -> "gui.zcinema.status.ended";
            case ERROR -> "gui.zcinema.status.error";
            default -> "gui.zcinema.status.idle";
        };
    }

    private Component timeText() {
        if (screen == null) return Component.literal("00:00 / 00:00");
        return Component.literal(formatSeconds(ClientPlayback.displaySeconds(screen.getBlockPos()))
                + " / " + formatSeconds(ClientPlayback.displayDurationSeconds(screen.getBlockPos())));
    }

    private static String formatSeconds(double seconds) {
        if (seconds <= 0.0) return "00:00";
        int total = (int) Math.round(seconds);
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight,
                0xC0101010);
        graphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + 1, 0xFF3C3C3C);
    }

    /**
     * Slider that only commits on release, so dragging never spams the shared timeline.
     *
     * <p>The commit is owned by the press-release pair, not by drag events: the mouse handler
     * aggregates movement per frame, so a fast flick can go press-move-release without ever
     * delivering an {@code onDrag}. Relying on those events let the per-tick refresh yank the
     * handle back to the old position before the release committed it, and the seek then went to
     * the position the film was already at - a drag that visibly did nothing.
     *
     * <p>After a commit the handle also holds the position the user picked until the shared clock
     * has actually caught up with it (a couple of seconds at most), so the round trip to the
     * server cannot make it look like the drag failed.
     */
    private class SeekSlider extends AbstractSliderButton {
        private static final long HOLD_MILLIS = 2_000L;

        /** True from the mouse going down on the slider until the seek is committed. */
        private boolean pressed;
        /** Value we last asked the server to play, held on screen until the clock arrives there. */
        private double committedValue = Double.NaN;
        private long committedAt;

        SeekSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0.0);
        }

        void refresh() {
            if (screen == null) return;
            if (this.pressed && !minecraft.mouseHandler.isLeftPressed()) {
                // The release was never delivered to this widget (it landed outside the window or
                // the focus moved): commit what the user picked instead of leaving it hanging.
                commit();
            }
            if (this.pressed) return; // the user owns the handle while the mouse is down
            if (holdingCommit()) return; // the seek we just sent owns it until the clock arrives
            double duration = ClientPlayback.displayDurationSeconds(screen.getBlockPos());
            double master = ClientPlayback.displaySeconds(screen.getBlockPos());
            this.value = duration > 0
                    ? Mth.clamp(master / duration, 0.0, 1.0)
                    : 0.0;
            this.updateMessage();
        }

        /** True while the handle stays where the user dropped it, waiting for the timeline. */
        private boolean holdingCommit() {
            if (Double.isNaN(this.committedValue)) return false;
            if (System.currentTimeMillis() - this.committedAt > HOLD_MILLIS) {
                this.committedValue = Double.NaN;
                return false;
            }
            double duration = ClientPlayback.displayDurationSeconds(screen.getBlockPos());
            double master = ClientPlayback.displaySeconds(screen.getBlockPos());
            if (duration > 0 && Math.abs(master - this.committedValue * duration) < 0.5) {
                this.committedValue = Double.NaN;
                return false;
            }
            return true;
        }

        /** Commits the current handle position as a seek for everyone. */
        private void commit() {
            if (!this.pressed) return;
            this.pressed = false;
            if (screen == null) return;
            double duration = ClientPlayback.displayDurationSeconds(screen.getBlockPos());
            if (duration <= 0.0) return;
            this.committedValue = this.value;
            this.committedAt = System.currentTimeMillis();
            ClientPlayback.seek(screen.getBlockPos(), this.value * duration);
        }

        @Override
        protected void updateMessage() {
            double duration = ClientPlayback.displayDurationSeconds(screen.getBlockPos());
            double seconds = duration > 0 ? this.value * duration : 0.0;
            setMessage(Component.literal(formatSeconds(seconds)));
        }

        @Override
        protected void applyValue() {
            // Only the drag is shown; the seek itself happens on release.
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            this.pressed = true;
            super.onClick(event, doubleClick);
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double mouseX, double mouseY) {
            this.pressed = true;
            super.onDrag(event, mouseX, mouseY);
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            commit();
            super.onRelease(event);
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            // Focus can move away mid-gesture (another widget clicked, the panel closing): commit
            // what the user picked instead of holding the handle hostage.
            if (!focused) commit();
        }
    }
}
