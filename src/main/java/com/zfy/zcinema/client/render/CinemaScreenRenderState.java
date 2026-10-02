package com.zfy.zcinema.client.render;

import com.zfy.zcinema.client.playback.FrameView;
import com.zfy.zcinema.screen.ScreenArea;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

/** Render state carrying the current frame for one screen. */
public class CinemaScreenRenderState extends BlockEntityRenderState {
    public Direction facing = Direction.NORTH;
    public FrameView frame;
    public ScreenArea area;
}
