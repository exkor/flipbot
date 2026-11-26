package com.exkor.flipbot.flipper.data;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/** SkyBlock item id of a stack (ExtraAttributes.id). */
public final class SkyblockId {
    private SkyblockId() {}

    public static String of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CompoundTag extra = extraAttributes(stack);
        return extra == null ? null : extra.getString("id").orElse("").toUpperCase(Locale.ROOT);
    }

    private static CompoundTag extraAttributes(ItemStack stack) {
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return null;
        }
        CompoundTag root = customData.copyTag();
        CompoundTag extra = root.getCompound("ExtraAttributes").orElse(null);
        if (extra != null) {
            return extra;
        }
        CompoundTag components = root.getCompound("components").orElse(null);
        if (components != null) {
            CompoundTag custom = components.getCompound("minecraft:custom_data").orElse(null);
            if (custom != null) {
                extra = custom.getCompound("ExtraAttributes").orElse(null);
                if (extra != null) {
                    return extra;
                }
            }
        }
        return root;
    }
}
