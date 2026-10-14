package com.thumbstick.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraft.util.ScreenShotHelper;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import com.thumbstick.ThumbstickConfig;
import com.thumbstick.binding.Action;
import com.thumbstick.client.gui.GuiControllerSettings;
import com.thumbstick.controller.ControllerManager;
import com.thumbstick.controller.GamepadButton;
import com.thumbstick.util.Reflect;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Drives the game from the controller: polls and moves the camera or menu cursor every frame, runs button actions
 * every tick. Gameplay actions press the vanilla KeyBindings, so vanilla and other mods react like to a keyboard.
 */
public final class ControllerInput {

    public static final ControllerInput INSTANCE = new ControllerInput();

    /** Full deflection camera speed in degrees per second */
    private static final float LOOK_SPEED_YAW = 280F, LOOK_SPEED_PITCH = 200F;
    /** Holding drop this many ticks throws the whole stack */
    private static final int DROP_STACK_TICKS = 10;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final ControllerManager controller = ControllerManager.INSTANCE;
    private final MenuNavigator menu = new MenuNavigator();

    /** Key bindings held down by us, so exactly those get released */
    private final Set<KeyBinding> heldKeys = new HashSet<>();
    private final boolean[] keyWasDown = new boolean[Keyboard.KEYBOARD_SIZE], mouseWasDown = new boolean[3];
    private GuiScreen lastScreen, detectScreen;
    private long lastFrameNanos;
    private int settleFrames, sprintGraceTicks, dropHeldTicks;
    private boolean sneakToggled, sprintLatched;

    private ControllerInput() {}

    public MenuNavigator getMenuNavigator() { return menu; }

    /** True when the controller should move the player */
    public boolean isGameplayInputActive() {
        return controller.isConnected() && Display.isActive() && mc.currentScreen == null && mc.thePlayer != null;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        long now = System.nanoTime();
        float delta = lastFrameNanos == 0L ? 0F : Math.min((now - lastFrameNanos) / 1.0E9F, 0.1F);
        lastFrameNanos = now;

        controller.poll();
        if (!controller.isConnected()) {
            menu.releaseCursor();
            return;
        }
        if (!Display.isActive()) return;
        detectKeyboardAndMouse();
        if (mc.currentScreen != null) menu.onFrame(mc.currentScreen, delta);
        else if (mc.thePlayer != null && !mc.isGamePaused()) turnCamera(delta);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        controller.tick();
        GuiScreen screen = mc.currentScreen;
        if (screen != lastScreen) {
            // One press must not act in both the old and the new context
            controller.suppressHeldButtons();
            menu.onScreenChanged(screen);
            lastScreen = screen;
        }
        if (!controller.isConnected() || !Display.isActive()) releaseKeys();
        else if (screen != null) {
            releaseKeys();
            menu.onTick(screen);
        } else if (mc.thePlayer != null && mc.theWorld != null) gameTick(mc.thePlayer);
        else releaseKeys();
    }

    /**
     * Notices keyboard and mouse use so hints and the controller cursor only show while the controller is used.
     * Only new presses count, so a key held while using the pad does not flicker. MenuNavigator detects mouse
     * movement in menus.
     */
    private void detectKeyboardAndMouse() {
        boolean used = false;
        for (int key = 1; key < keyWasDown.length; key++) {
            boolean down = Keyboard.isKeyDown(key);
            if (down && !keyWasDown[key]) used = true;
            keyWasDown[key] = down;
        }
        for (int b = 0; b < mouseWasDown.length; b++) {
            boolean down = Mouse.isButtonDown(b);
            if (down && !mouseWasDown[b]) used = true;
            mouseWasDown[b] = down;
        }
        // Mouse look in game, as computed for the previous frame. Grabbing the mouse when a screen closes can
        // cause a jump, so the first frames after a screen change are ignored
        if (mc.currentScreen != detectScreen) {
            detectScreen = mc.currentScreen;
            settleFrames = 3;
        }
        if (settleFrames > 0) settleFrames--;
        else if (mc.currentScreen == null && (mc.mouseHelper.deltaX != 0 || mc.mouseHelper.deltaY != 0)) used = true;
        if (used) controller.markOtherInputUsed();
    }

    private void turnCamera(float delta) {
        float[] stick = controller.getRightStick();
        if (stick[0] == 0F && stick[1] == 0F) return;
        float magnitude = (float) Math.sqrt(stick[0] * stick[0] + stick[1] * stick[1]);
        // Ease in: precise aiming near the center, full speed at the edge
        float curved = magnitude * magnitude * 0.7F + magnitude * 0.3F;
        float yaw = stick[0] / magnitude * curved * LOOK_SPEED_YAW * ThumbstickConfig.lookSpeedX * delta;
        float pitch = stick[1] / magnitude * curved * LOOK_SPEED_PITCH * ThumbstickConfig.lookSpeedY * delta;
        if (ThumbstickConfig.invertLook) pitch = -pitch;
        // setAngles scales its input by 0.15 and subtracts pitch
        mc.thePlayer.setAngles(yaw / 0.15F, -pitch / 0.15F);
    }

    private void gameTick(EntityClientPlayerMP player) {
        GameSettings s = mc.gameSettings;
        if (player.movementInput != null && player.movementInput.getClass() == MovementInputFromOptions.class) {
            player.movementInput = new MovementInput(s);
        }
        Set<KeyBinding> wanted = new HashSet<>(), tapped = new HashSet<>();
        if (down(Action.JUMP)) wanted.add(s.keyBindJump);
        if (down(Action.ATTACK)) wanted.add(s.keyBindAttack);
        if (down(Action.USE_ITEM)) wanted.add(s.keyBindUseItem);
        if (down(Action.PLAYER_LIST)) wanted.add(s.keyBindPlayerList);

        if (!ThumbstickConfig.toggleSneak) sneakToggled = false;
        else if (pressed(Action.SNEAK)) sneakToggled = !sneakToggled;
        if (sneakToggled || !ThumbstickConfig.toggleSneak && down(Action.SNEAK)) wanted.add(s.keyBindSneak);

        // Sprint latches like on consoles: click once and keep sprinting while moving forward
        if (pressed(Action.SPRINT)) {
            sprintLatched = true;
            sprintGraceTicks = 5;
        }
        if (sprintLatched) {
            if (sprintGraceTicks > 0) sprintGraceTicks--;
            else if (player.movementInput.moveForward < 0.8F || player.isSneaking()) sprintLatched = false;
        }
        if (sprintLatched || down(Action.SPRINT)) wanted.add(s.keyBindSprint);

        if (pressed(Action.PICK_BLOCK)) tapped.add(s.keyBindPickBlock);
        if (pressed(Action.INVENTORY)) tapped.add(s.keyBindInventory);
        if (pressed(Action.CHAT)) tapped.add(s.keyBindChat);
        if (pressed(Action.DROP_ITEM)) tapped.add(s.keyBindDrop);
        if (!down(Action.DROP_ITEM)) dropHeldTicks = 0;
        else if (++dropHeldTicks == DROP_STACK_TICKS) player.dropOneItem(true);

        if (pressed(Action.PREVIOUS_ITEM)) player.inventory.changeCurrentItem(1);
        if (pressed(Action.NEXT_ITEM)) player.inventory.changeCurrentItem(-1);
        if (pressed(Action.TOGGLE_PERSPECTIVE)) s.thirdPersonView = (s.thirdPersonView + 1) % 3;
        if (pressed(Action.SCREENSHOT)) {
            mc.ingameGUI.getChatGUI().printChatMessage(
                ScreenShotHelper.saveScreenshot(mc.mcDataDir, mc.displayWidth, mc.displayHeight, mc.getFramebuffer()));
        }

        boolean fireKeyEvent = false;
        if (!ThumbstickConfig.keyBindings.isEmpty()) {
            Map<String, KeyBinding> byName = new HashMap<>();
            for (KeyBinding key : s.keyBindings) byName.put(key.getKeyDescription(), key);
            for (Map.Entry<String, GamepadButton> e : ThumbstickConfig.keyBindings.entrySet()) {
                KeyBinding key = byName.get(e.getKey());
                if (key == null) continue;
                if (controller.isDown(e.getValue())) wanted.add(key);
                if (controller.isPressed(e.getValue())) fireKeyEvent = true;
            }
        }

        for (KeyBinding key : heldKeys) if (!wanted.contains(key)) Reflect.setPressed(key, false);
        for (KeyBinding key : wanted) {
            // Applied again every tick since opening a screen calls KeyBinding.unPressAllKeys
            Reflect.setPressed(key, true);
            if (!heldKeys.contains(key)) Reflect.addPress(key);
        }
        for (KeyBinding key : tapped) Reflect.addPress(key);
        heldKeys.clear();
        heldKeys.addAll(wanted);

        // Mods usually check their keys in KeyInputEvent, which only fires for real keyboard events
        if (fireKeyEvent) FMLCommonHandler.instance().fireKeyInput();

        // Actions opening a screen go last so the keys above apply in this context
        if (pressed(Action.PAUSE)) mc.displayInGameMenu();
        else if (pressed(Action.OPEN_SETTINGS)) mc.displayGuiScreen(new GuiControllerSettings(null));
    }

    private void releaseKeys() {
        for (KeyBinding key : heldKeys) Reflect.setPressed(key, false);
        heldKeys.clear();
        dropHeldTicks = 0;
        sprintLatched = false;
    }

    private boolean down(Action a) { return controller.isDown(ThumbstickConfig.getBinding(a)); }

    private boolean pressed(Action a) { return controller.isPressed(ThumbstickConfig.getBinding(a)); }

    /** Vanilla keyboard movement plus the left stick, installed only when nothing else replaced the vanilla one */
    public static class MovementInput extends MovementInputFromOptions {

        public MovementInput(GameSettings settings) { super(settings); }

        @Override
        public void updatePlayerMoveState() {
            super.updatePlayerMoveState();
            if (!INSTANCE.isGameplayInputActive()) return;
            float[] stick = ControllerManager.INSTANCE.getLeftStick();
            float forward = -stick[1], strafe = -stick[0];
            if (forward == 0F && strafe == 0F) return;
            if (!ThumbstickConfig.analogMovement) {
                forward = Math.abs(forward) > 0.3F ? Math.signum(forward) : 0F;
                strafe = Math.abs(strafe) > 0.3F ? Math.signum(strafe) : 0F;
            }
            if (sneak) {
                forward *= 0.3F;
                strafe *= 0.3F;
            }
            // Keyboard input wins when stronger, so both work at once
            if (Math.abs(forward) > Math.abs(moveForward)) moveForward = forward;
            if (Math.abs(strafe) > Math.abs(moveStrafe)) moveStrafe = strafe;
        }
    }
}
