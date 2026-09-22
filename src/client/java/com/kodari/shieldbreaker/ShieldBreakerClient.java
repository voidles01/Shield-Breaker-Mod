package com.kodari.shieldbreaker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

public final class ShieldBreakerClient implements ClientModInitializer {
    private static final long ATTACK_DELAY_NANOS = 5_000_000L;
    private static final double ATTACK_RANGE_SQUARED = 9.0D;

    private KeyBinding menuKey;
    private boolean enabled;
    private int previousSlot = -1;
    private long attackAtNanos;
    private AbstractClientPlayerEntity pendingTarget;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("shieldbreaker", "general"));
        this.menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.shieldbreaker.menu",
                InputUtil.Type.KEYSYM,
                InputUtil.GLFW_KEY_RIGHT_CONTROL,
                category
        ));
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);
    }

    private void onEndClientTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        while (this.menuKey.wasPressed()) {
            client.setScreen(new ShieldBreakerScreen(this));
        }

        if (this.attackAtNanos != 0L && System.nanoTime() >= this.attackAtNanos) {
            if (this.pendingTarget != null && client.interactionManager != null) {
                client.interactionManager.attackEntity(player, this.pendingTarget);
                player.swingHand(Hand.MAIN_HAND);
            }
            this.restoreHeldItem(player);
            return;
        }

        if (!this.enabled || this.attackAtNanos != 0L || client.world == null || client.interactionManager == null) {
            return;
        }

        AbstractClientPlayerEntity target = this.findShieldingPlayer(player, client);
        if (target == null) {
            return;
        }

        int axeSlot = this.findAxeSlot(player.getInventory());
        if (axeSlot < 0) {
            return;
        }

        this.previousSlot = player.getInventory().getSelectedSlot();
        this.selectSlot(player, axeSlot);
        this.pendingTarget = target;
        this.attackAtNanos = System.nanoTime() + ATTACK_DELAY_NANOS;
    }

    private AbstractClientPlayerEntity findShieldingPlayer(ClientPlayerEntity player, MinecraftClient client) {
        for (AbstractClientPlayerEntity candidate : client.world.getPlayers()) {
            if (candidate == player || player.squaredDistanceTo(candidate) > ATTACK_RANGE_SQUARED) {
                continue;
            }

            if (candidate.isUsingItem() && candidate.getActiveItem().getItem() instanceof ShieldItem) {
                return candidate;
            }
        }

        return null;
    }

    private int findAxeSlot(PlayerInventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() instanceof AxeItem) {
                return slot;
            }
        }

        return -1;
    }

    private void restoreHeldItem(ClientPlayerEntity player) {
        if (this.previousSlot >= 0) {
            this.selectSlot(player, this.previousSlot);
        }
        this.previousSlot = -1;
        this.attackAtNanos = 0L;
        this.pendingTarget = null;
    }

    private void selectSlot(ClientPlayerEntity player, int slot) {
        player.getInventory().setSelectedSlot(slot);
        player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void toggleEnabled() {
        this.enabled = !this.enabled;
        if (!this.enabled && MinecraftClient.getInstance().player != null) {
            this.restoreHeldItem(MinecraftClient.getInstance().player);
        }
    }

    private static final class ShieldBreakerScreen extends Screen {
        private final ShieldBreakerClient shieldBreaker;

        private ShieldBreakerScreen(ShieldBreakerClient shieldBreaker) {
            super(Text.translatable("screen.shieldbreaker.title"));
            this.shieldBreaker = shieldBreaker;
        }

        @Override
        protected void init() {
            this.addDrawableChild(ButtonWidget.builder(this.getToggleText(), button -> {
                this.shieldBreaker.toggleEnabled();
                button.setMessage(this.getToggleText());
            }).dimensions(this.width / 2 - 75, this.height / 2 - 10, 150, 20).build());
        }

        private Text getToggleText() {
            return Text.translatable(this.shieldBreaker.enabled
                    ? "screen.shieldbreaker.disable"
                    : "screen.shieldbreaker.enable");
        }
    }
}