package com.quiettee.utils.modules.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.quiettee.utils.QuietteeUtils;
import com.quiettee.utils.render.QuietteePipelines;
import meteordevelopment.meteorclient.events.render.RenderAfterWorldEvent;
import meteordevelopment.meteorclient.renderer.FixedUniformStorage;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gl.DynamicUniformStorage;

import java.nio.ByteBuffer;

public class HighContrast extends Module {

    public enum Mode {
        BlackWhite,
        Grayscale,
        Colors
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final SettingGroup sgEdges = settings.createGroup("Edges");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Black and white, grayscale or colours.")
        .defaultValue(Mode.BlackWhite)
        .onChanged(v -> dirty = true)
        .build()
    );

    private final Setting<Double> threshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("threshold")
        .description("Brightness cutoff for white.")
        .defaultValue(0.4)
        .min(0.02)
        .max(0.98)
        .sliderRange(0.05, 0.95)
        .visible(() -> mode.get() == Mode.BlackWhite)
        .onChanged(v -> dirty = true)
        .build()
    );

    private final Setting<Integer> levels = sgGeneral.add(new IntSetting.Builder()
        .name("levels")
        .description("Brightness or colour steps to keep.")
        .defaultValue(3)
        .min(2)
        .max(10)
        .sliderRange(2, 8)
        .visible(() -> mode.get() != Mode.BlackWhite)
        .onChanged(v -> dirty = true)
        .build()
    );

    private final Setting<Boolean> invert = sgGeneral.add(new BoolSetting.Builder()
        .name("invert")
        .description("Dark world on a light screen.")
        .defaultValue(false)
        .onChanged(v -> dirty = true)
        .build()
    );

    private final Setting<Boolean> edges = sgEdges.add(new BoolSetting.Builder()
        .name("edge-lines")
        .description("Draw lines where brightness changes sharply.")
        .defaultValue(false)
        .onChanged(v -> dirty = true)
        .build()
    );

    private final Setting<Double> edgeThreshold = sgEdges.add(new DoubleSetting.Builder()
        .name("edge-sensitivity")
        .description("How sharp a change draws a line.")
        .defaultValue(0.15)
        .min(0.02)
        .max(0.8)
        .sliderRange(0.05, 0.5)
        .visible(edges::get)
        .onChanged(v -> dirty = true)
        .build()
    );

    private GpuTextureView fbo;
    private GpuBufferSlice ubo;
    private boolean dirty = true;

    public HighContrast() {
        super(QuietteeUtils.CATEGORY, "high-contrast", "High contrast world for low vision.");
    }

    @Override
    public void onActivate() {
        dirty = true;
    }

    @Override
    public void onDeactivate() {
        if (fbo != null) {
            fbo.close();
            fbo = null;
        }

        dirty = true;
    }

    @EventHandler
    private void onRenderAfterWorld(RenderAfterWorldEvent event) {
        if (mc.world == null) return;

        int width = mc.getWindow().getFramebufferWidth();
        int height = mc.getWindow().getFramebufferHeight();
        if (width <= 0 || height <= 0) return;

        if (fbo == null || fbo.getWidth(0) != width || fbo.getHeight(0) != height) {
            if (fbo != null) fbo.close();
            fbo = RenderSystem.getDevice().createTextureView(RenderSystem.getDevice().createTexture("Quiettee Utils - HighContrast", 15, TextureFormat.RGBA8, width, height, 1, 1));
            dirty = true;
        }

        if (dirty || ubo == null) {
            UNIFORM_STORAGE.clear();
            ubo = UNIFORM_STORAGE.write(new ContrastUniformData(
                1f / width, 1f / height,
                mode.get().ordinal(),
                threshold.get().floatValue(),
                levels.get(),
                invert.get() ? 1 : 0,
                edges.get() ? 1 : 0,
                edgeThreshold.get().floatValue()
            ));
            dirty = false;
        }

        MeshRenderer.begin()
            .attachments(fbo, null)
            .pipeline(QuietteePipelines.POST_CONTRAST)
            .fullscreen()
            .uniform("ContrastData", ubo)
            .sampler("u_Texture", mc.getFramebuffer().getColorAttachmentView(), RenderSystem.getSamplerCache().get(FilterMode.NEAREST))
            .end();

        MeshRenderer.begin()
            .attachments(mc.getFramebuffer())
            .pipeline(MeteorRenderPipelines.BLUR_PASSTHROUGH)
            .fullscreen()
            .sampler("u_Texture", fbo, RenderSystem.getSamplerCache().get(FilterMode.NEAREST))
            .end();
    }

    private static final int UNIFORM_SIZE = new Std140SizeCalculator()
        .putVec2()
        .putFloat()
        .putFloat()
        .putFloat()
        .putFloat()
        .putFloat()
        .putFloat()
        .get();

    private static final FixedUniformStorage<ContrastUniformData> UNIFORM_STORAGE = new FixedUniformStorage<>("Quiettee Utils - HighContrast UBO", UNIFORM_SIZE, 1);

    private record ContrastUniformData(float texelSizeX, float texelSizeY, float mode, float threshold, float levels, float invert, float edges, float edgeThreshold) implements DynamicUniformStorage.Uploadable {
        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer)
                .putVec2(texelSizeX, texelSizeY)
                .putFloat(mode)
                .putFloat(threshold)
                .putFloat(levels)
                .putFloat(invert)
                .putFloat(edges)
                .putFloat(edgeThreshold);
        }
    }
}
