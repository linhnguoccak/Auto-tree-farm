package com.albscript.autotreefarmer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Auto Tree Farmer - Fabric Mod cho Minecraft 1.20.4
 * Tự động trồng, bón bột xương và chặt cây.
 *
 * Keybind mặc định: K (đổi được trong Controls)
 */
public class AutoTreeFarmerMod implements ClientModInitializer {

    public static final String MOD_ID  = "auto-tree-farmer";
    /** Tiền tố hiển thị trong chat (màu xanh lá) */
    public static final String PREFIX  = "§a[AutoTreeFarmer]§r ";

    private static KeyBinding      toggleKey;
    public  static TreeFarmerController controller;

    @Override
    public void onInitializeClient() {
        // Đăng ký keybind bật/tắt (mặc định phím K)
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autotreefarmer.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "category.autotreefarmer"
        ));

        controller = new TreeFarmerController();

        // Đăng ký tick event
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Xử lý toggle
            while (toggleKey.wasPressed()) {
                controller.toggle(client);
            }

            // Chạy logic nếu đang hoạt động
            if (controller.isActive()) {
                controller.tick(client);
            }
        });
    }

    /**
     * Gửi thông báo vào chat của player.
     * @param client   MinecraftClient instance
     * @param message  Nội dung (hỗ trợ § color codes)
     */
    public static void sendMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(PREFIX + message), false);
        }
    }
}
