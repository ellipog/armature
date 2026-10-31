package dev.ellipog.armature.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import dev.ellipog.armature.Constants;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

/**
 * The game renderer's one-pixel lines: a pipeline that draws fills as the game's GUI pipeline does, and a
 * segment as one quad whose shader keeps exactly {@link Hairlines}' pixels.
 *
 * <h2>Why fills share the pipeline</h2>
 *
 * <p>A line is drawn between other fills, an arrowhead after it and a shape over it. Two pipelines would be
 * two elements for every change between them, and the game sorts elements of one layer by pipeline, so the
 * order would only hold by splitting the batch at every line. One pipeline keeps the batch whole. A fill in it
 * carries no run, and the shader draws its colour and discards it at zero alpha, which is what the game's
 * {@code gui.fsh} does, with the same blend.
 *
 * <h2>Off, on purpose or by necessity</h2>
 *
 * <p>{@code -Darmature.gpuLines=false} turns this off. A pipeline the device will not compile turns it off for
 * the session with one warning, and every line is drawn as fills, which is what was drawn before it existed.
 */
final class GpuLines {

    private static final boolean REQUESTED =
            !"false".equalsIgnoreCase(System.getProperty("armature.gpuLines"));

    /** The pipeline, laid out as the game's GUI pipeline is, with the text pipeline's vertex format. */
    static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withLocation(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "pipeline/gui_hairline"))
            .withVertexShader(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "core/gui_hairline"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "core/gui_hairline"))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build();

    private static boolean disabled;
    private static CompiledRenderPipeline compiled;

    private GpuLines() {
    }

    /**
     * Whether lines can be drawn by the pipeline this frame. Asked per line: after the first answer it is a
     * lookup in the game's own pipeline cache, which also notices a resource reload that dropped the pipeline.
     */
    static boolean ready() {
        if (!REQUESTED || disabled || !RenderSystem.isOnRenderThread()) {
            return false;
        }
        try {
            CompiledRenderPipeline now = RenderSystem.getCompiledPipelineNullable(PIPELINE);
            if (now == null) {
                disable(new IllegalStateException("the line pipeline did not compile"));
                return false;
            }
            if (now != compiled) {
                if (compiled == null) {
                    Constants.LOG.info("Drawing one-pixel lines with a GPU shader");
                }
                compiled = now;
            }
            return true;
        }
        catch (Throwable failure) {
            disable(failure);
            return false;
        }
    }

    /** Turns lines off for the session, saying why once. */
    static void disable(Throwable failure) {
        if (!disabled) {
            disabled = true;
            Constants.LOG.warn("GPU lines are off for this session; lines draw as fills", failure);
        }
    }
}
