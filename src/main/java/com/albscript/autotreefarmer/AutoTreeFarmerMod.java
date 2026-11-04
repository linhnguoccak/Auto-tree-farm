package com.albscript.autotreefarmer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class AutoTreeFarmerMod implements ClientModInitializer {

    public static final String MOD_ID  = "auto-tree-farmer";
    public static final String PREFIX  = "§a[AutoTreeFarmer]§r ";

    private static KeyBinding toggleKey;
    public  static TreeFarmerController controller;

    @Override
    public void onInitializeClient() {
        // Dùng category có sẵn MISC thay vì register() (không tồn tại trong 1.21.11)
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autotreefarmer.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            KeyBinding.Category.MISC
        ));

        controller = new TreeFarmerController();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            while (toggleKey.wasPressed()) {
                controller.toggle(client);
            }
            if (controller.isActive()) {
                controller.tick(client);
            }
        });
    }

    public static void sendMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(PREFIX + message), false);
        }
    }
}
