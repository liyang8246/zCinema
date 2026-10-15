package com.zcinema.client;

import com.zcinema.CinemaCoreBlockEntity;
import com.zcinema.ScreenStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.players.MediaPlayer;

public class CinemaControlScreen extends Screen {
    private final BlockPos corePos;
    private EditBox urlBox;
    private Button playPauseBtn;
    private SeekSlider seekSlider;

    public CinemaControlScreen(BlockPos corePos) {
        super(Component.literal("zCinema 放映控制面板"));
        this.corePos = corePos;
    }

    private CinemaCoreBlockEntity getBlockEntity() {
        if (this.minecraft == null || this.minecraft.level == null) return null;
        if (this.minecraft.level.getBlockEntity(this.corePos) instanceof CinemaCoreBlockEntity be && !be.isRemoved()) {
            return be;
        }
        return null;
    }

    @Override
    protected void init() {
        CinemaCoreBlockEntity be = getBlockEntity();
        String prevText = this.urlBox != null ? this.urlBox.getValue() : (be != null ? be.url : "");

        int centerX = this.width / 2;
        int baseY = this.height / 2 - 55;

        this.urlBox = new EditBox(this.font, centerX - 140, baseY, 220, 20, Component.literal("视频 URL"));
        this.urlBox.setMaxLength(1024);
        this.urlBox.setValue(prevText);
        this.urlBox.setHint(Component.literal("输入 MP4 直链或 B 站链接 / BV号..."));
        this.addRenderableWidget(this.urlBox);

        this.addRenderableWidget(Button.builder(Component.literal("载入"), btn -> {
            String input = this.urlBox.getValue().trim();
            if (!input.isEmpty()) {
                ClientPlayNetworking.send(new ScreenStatePayload(this.corePos, input, false, 0L, 0L));
            }
        }).bounds(centerX + 85, baseY, 55, 20).build());

        this.seekSlider = new SeekSlider(centerX - 140, baseY + 30, 280, 20);
        this.addRenderableWidget(this.seekSlider);

        this.playPauseBtn = Button.builder(Component.literal("播放 / 暂停"), btn -> {
            CinemaCoreBlockEntity entity = getBlockEntity();
            if (entity == null || entity.url.isEmpty()) return;
            boolean nextPlaying = !entity.playing;
            long currentPos = getCurrentMediaPosMs(entity);
            ClientPlayNetworking.send(new ScreenStatePayload(this.corePos, entity.url, nextPlaying, currentPos, 0L));
        }).bounds(centerX - 140, baseY + 60, 135, 20).build();
        this.addRenderableWidget(this.playPauseBtn);

        this.addRenderableWidget(Button.builder(Component.literal("§c撤下屏幕"), btn -> {
            ClientPlayNetworking.send(new ScreenStatePayload(this.corePos, "", false, -1L, -1L));
            this.onClose();
        }).bounds(centerX + 5, baseY + 60, 135, 20).build());
    }

    private long getCurrentMediaPosMs(CinemaCoreBlockEntity be) {
        ScreenPlayerManager.Entry entry = ScreenPlayerManager.get(this.corePos);
        if (entry != null) {
            return entry.expectedMediaPosMs();
        }
        return be.playing ? be.mediaPosMs + Math.max(0L, ClientClock.now() - be.serverTimestampMs) : be.mediaPosMs;
    }

    private long getDurationMs() {
        ScreenPlayerManager.Entry entry = ScreenPlayerManager.get(this.corePos);
        if (entry != null) {
            if (entry.player != null && entry.player.duration() > 0) {
                return entry.player.duration();
            }
            if (entry.mrl != null && entry.mrl.sourceCount() > 0) {
                MRL.Source src = entry.mrl.source(0);
                if (src != null && src.metadata() != null && src.metadata().duration() > 0) {
                    return src.metadata().duration();
                }
            }
        }
        return 0L;
    }

    private String getStatusText(CinemaCoreBlockEntity be) {
        if (be == null) return "屏幕已移除";
        if (be.url.isEmpty()) return "空闲 (未载入视频)";
        ScreenPlayerManager.Entry entry = ScreenPlayerManager.get(this.corePos);
        if (entry == null || entry.mrl == null) return "等待同步...";
        MRL.Status mrlStatus = entry.mrl.status();
        if (mrlStatus == MRL.Status.FETCHING) return "正在解析视频源...";
        if (mrlStatus.failed()) return "§c解析失败: " + (entry.mrl.exception() != null ? entry.mrl.exception().getMessage() : mrlStatus);
        if (entry.player == null) return "正在初始化播放器...";
        MediaPlayer.Status pStatus = entry.player.status();
        return switch (pStatus) {
            case WAITING, LOADING -> "正在加载媒体流...";
            case BUFFERING -> be.playing ? "缓冲中..." : "预加载缓冲中 (暂停待命)...";
            case PLAYING -> "§a播放中";
            case PAUSED -> "§e已暂停 (可点播放全员同步开始)";
            case ENDED -> "播放已结束";
            case STOPPED -> "已停止";
            case ERROR -> "§c解码播放出错";
        };
    }

    @Override
    public void tick() {
        CinemaCoreBlockEntity be = getBlockEntity();
        if (be == null) {
            this.onClose();
            return;
        }
        if (this.playPauseBtn != null) {
            this.playPauseBtn.setMessage(Component.literal(be.playing ? "暂停" : "播放"));
            this.playPauseBtn.active = !be.url.isEmpty();
        }
        if (this.seekSlider != null) {
            this.seekSlider.syncFromPlayback(be);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        CinemaCoreBlockEntity be = getBlockEntity();
        int centerX = this.width / 2;
        int baseY = this.height / 2 - 55;

        graphics.drawCenteredString(this.font, this.title, centerX, baseY - 28, 0xFFFFFF);
        if (be != null) {
            long posMs = getCurrentMediaPosMs(be);
            long durMs = getDurationMs();
            String statusLine = "状态: " + getStatusText(be) + "  |  时间: " + formatTime(posMs) + " / " + (durMs > 0 ? formatTime(durMs) : "--:--");
            graphics.drawCenteredString(this.font, statusLine, centerX, baseY + 92, 0xCCCCCC);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String formatTime(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long hours = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, mins, secs);
        }
        return String.format("%02d:%02d", mins, secs);
    }

    private class SeekSlider extends AbstractSliderButton {
        private boolean dragging;

        public SeekSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal("进度: 00:00 / --:--"), 0.0);
        }

        public void syncFromPlayback(CinemaCoreBlockEntity be) {
            long dur = getDurationMs();
            this.active = !be.url.isEmpty() && dur > 0;
            if (!this.dragging) {
                long cur = getCurrentMediaPosMs(be);
                this.value = dur > 0 ? Math.clamp((double) cur / (double) dur, 0.0, 1.0) : 0.0;
                updateMessage();
            }
        }

        @Override
        protected void updateMessage() {
            long dur = getDurationMs();
            long target = dur > 0 ? Math.round(this.value * dur) : 0L;
            this.setMessage(Component.literal("进度: " + formatTime(target) + " / " + (dur > 0 ? formatTime(dur) : "--:--")));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            this.dragging = true;
            super.onClick(mouseX, mouseY);
        }

        @Override
        protected void applyValue() {
            this.dragging = true;
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            if (this.dragging) {
                this.dragging = false;
                CinemaCoreBlockEntity be = getBlockEntity();
                long dur = getDurationMs();
                if (be != null && !be.url.isEmpty() && dur > 0) {
                    long targetMs = Math.clamp(Math.round(this.value * dur), 0L, dur);
                    ClientPlayNetworking.send(new ScreenStatePayload(corePos, be.url, false, targetMs, 0L));
                }
            }
        }
    }
}
