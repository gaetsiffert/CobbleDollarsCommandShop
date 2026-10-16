package fr.cobbledollars.commandshops.gametest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.client.ClientUiState;
import fr.cobbledollars.commandshops.network.CommandShopPayloads;
import fr.cobbledollars.commandshops.network.payload.ClientOverlayMessagePayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Matrix4f;

/** Exercises the registered payload bridge and real GUI rendering against an isolated framebuffer. */
public final class ClientFeedbackSmokeTest {
    private ClientFeedbackSmokeTest() {
    }

    public static void verify(Minecraft minecraft) throws Exception {
        Screen previousScreen = minecraft.screen;
        TextureTarget target = new TextureTarget(
                minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(), true, Minecraft.ON_OSX);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.getModelViewStack().pushMatrix();
        ClientUiState.clear();
        try {
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(
                    0.0F, minecraft.getWindow().getGuiScaledWidth(),
                    minecraft.getWindow().getGuiScaledHeight(), 0.0F, 1000.0F, 21000.0F), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.getModelViewStack().identity().translate(0.0F, 0.0F, -11000.0F);
            RenderSystem.applyModelViewMatrix();

            Screen fixture = new Screen(Component.literal("NPC dialogue feedback fixture")) { };
            fixture.init(minecraft, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
            minecraft.screen = fixture;
            dispatch("This shop is disabled.");
            Bounds shortMessage;
            try (NativeImage image = render(minecraft, target, false)) {
                shortMessage = assertTopLeft(minecraft, image);
                save(minecraft, image, "feedback-screen.png");
            }

            // The HUD event must not duplicate the screen's notification while any screen is open.
            try (NativeImage image = render(minecraft, target, true)) {
                require(bounds(image).pixels() == 0, "The HUD rendered feedback over an open screen.");
            }

            minecraft.screen = null;
            try (NativeImage image = render(minecraft, target, true)) {
                Bounds hudMessage = assertTopLeft(minecraft, image);
                require(hudMessage.equals(shortMessage), "HUD and arbitrary-screen feedback have different rendered bounds.");
                save(minecraft, image, "feedback-hud.png");
            }

            minecraft.screen = fixture;
            dispatch("This shop is temporarily disabled while the administrator updates its offers. "
                    + "Please try again later or visit another shop. Your items and money have not been changed.");
            try (NativeImage image = render(minecraft, target, false)) {
                Bounds multiline = assertTopLeft(minecraft, image);
                require(multiline.bottom() > shortMessage.bottom() + 10,
                        "Long feedback did not wrap into a taller notification.");
                save(minecraft, image, "feedback-multiline.png");
            }

            expireCurrentMessage();
            try (NativeImage image = render(minecraft, target, false)) {
                require(bounds(image).pixels() == 0, "Expired feedback remained visible.");
                require(overlayField().get(null) == null, "Rendering did not discard the expired message.");
            }
            CobbleDollarsCommandShopsMod.LOGGER.info(
                    "CLIENT_FEEDBACK_SMOKE_RESULT: PASS; actual payload dispatch, arbitrary screen and HUD pixels, upper-left anchoring, multiline wrapping and expiry.");
        } finally {
            minecraft.screen = previousScreen;
            ClientUiState.clear();
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            target.destroyBuffers();
            minecraft.getMainRenderTarget().bindWrite(true);
        }
    }

    private static void dispatch(String text) throws Exception {
        Method dispatch = CommandShopPayloads.class.getDeclaredMethod("dispatchOverlayMessage", ClientOverlayMessagePayload.class);
        dispatch.setAccessible(true);
        dispatch.invoke(null, new ClientOverlayMessagePayload(Component.literal(text), 0xFFFFAA66, 10_000));
        Object overlay = overlayField().get(null);
        require(overlay != null, "The real payload dispatcher did not populate client feedback.");
        Method message = overlay.getClass().getDeclaredMethod("message");
        message.setAccessible(true);
        require(((Component) message.invoke(overlay)).getString().equals(text), "The dispatcher retained stale feedback.");
    }

    private static NativeImage render(Minecraft minecraft, TextureTarget target, boolean hud) {
        target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        if (hud) {
            ClientUiState.onHudRender(new RenderGuiLayerEvent.Post(
                    graphics, DeltaTracker.ZERO, VanillaGuiLayers.OVERLAY_MESSAGE, (ignoredGraphics, ignoredDelta) -> { }));
        } else {
            ClientUiState.onScreenRender(new ScreenEvent.Render.Post(minecraft.screen, graphics, -1, -1, 0.0F));
        }
        graphics.flush();
        return Screenshot.takeScreenshot(target);
    }

    private static Bounds assertTopLeft(Minecraft minecraft, NativeImage image) {
        Bounds actual = bounds(image);
        require(actual.pixels() > 100, "Feedback did not paint the framebuffer.");
        double scaleX = (double) image.getWidth() / minecraft.getWindow().getGuiScaledWidth();
        double scaleY = (double) image.getHeight() / minecraft.getWindow().getGuiScaledHeight();
        require(Math.abs(actual.left() - (10.0D * scaleX)) <= 3.0D,
                "Feedback is not anchored to the left edge: " + actual);
        require(Math.abs(actual.top() - (10.0D * scaleY)) <= 3.0D,
                "Feedback is not anchored to the top edge: " + actual);
        require(actual.left() >= 0 && actual.top() >= 0 && actual.right() < image.getWidth() && actual.bottom() < image.getHeight(),
                "Feedback overflowed the viewport.");
        return actual;
    }

    private static Bounds bounds(NativeImage image) {
        int left = image.getWidth();
        int top = image.getHeight();
        int right = -1;
        int bottom = -1;
        int pixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getPixelRGBA(x, y) & 0x00FFFFFF) != 0) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                    pixels++;
                }
            }
        }
        return new Bounds(left, top, right, bottom, pixels);
    }

    private static void expireCurrentMessage() throws Exception {
        Field overlayField = overlayField();
        Object overlay = overlayField.get(null);
        Constructor<?> constructor = overlay.getClass().getDeclaredConstructor(Component.class, int.class, long.class);
        constructor.setAccessible(true);
        overlayField.set(null, constructor.newInstance(Component.literal("Expired feedback"), 0xFFFFAA66, System.currentTimeMillis() - 1L));
    }

    private static Field overlayField() throws Exception {
        Field field = ClientUiState.class.getDeclaredField("overlayMessage");
        field.setAccessible(true);
        return field;
    }

    private static void save(Minecraft minecraft, NativeImage image, String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR).resolve(name);
        Files.createDirectories(path.getParent());
        image.writeToFile(path);
        CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_FEEDBACK_SMOKE_CAPTURE: {}", path.toAbsolutePath());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Bounds(int left, int top, int right, int bottom, int pixels) { }
}
