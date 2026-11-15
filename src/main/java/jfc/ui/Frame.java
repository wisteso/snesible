package jfc.ui;

/**
 * One finished picture from the PPU, handed to a {@link VideoSink}.
 *
 * <p>Pixels are the PPU's raw output, {@code brightness << 15 | bgr15} (see
 * {@link jfc.ui.video.SnesColor}), row after row, {@code width} pixels per row. The array is the
 * PPU's own frame buffer, not a copy: it is only valid while {@link VideoSink#present} runs,
 * because the emulator starts drawing the next frame into it as soon as that call returns.
 *
 * @param pixels packed pixels; the picture is the first {@code width * height} of them
 * @param width  pixels per row (256 today; 512 once hi-res modes are emulated)
 * @param height picture lines: 224, or 239 when the game turns on overscan
 * @param number counts frames from 1 since the console was reset. The NTSC filter uses it to
 *               pick the frame's color-burst phase.
 */
public record Frame(int[] pixels, int width, int height, long number) {
}
