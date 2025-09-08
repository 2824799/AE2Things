package com.asdflj.ae2thing.coremod.mixin.nei;

import static codechicken.nei.NEIClientUtils.translate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.asdflj.ae2thing.api.AE2ThingAPI;
import com.asdflj.ae2thing.api.adapter.terminal.ICraftingTerminalAdapter;
import com.asdflj.ae2thing.client.event.CraftTracking;
import com.asdflj.ae2thing.util.Ae2ReflectClient;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IDisplayRepo;
import appeng.api.storage.data.IItemList;
import appeng.client.gui.AEBaseGui;
import appeng.client.me.ItemRepo;
import appeng.util.item.AEItemStack;
import codechicken.nei.PositionedStack;
import codechicken.nei.api.IGuiContainerOverlay;
import codechicken.nei.recipe.GuiOverlayButton;
import codechicken.nei.recipe.IRecipeHandler;

@Mixin(GuiOverlayButton.class)
public abstract class MixinGuiOverlayButton {

    // 注意：不要 shadow handler / recipeIndex —— 新版类已经移除它们，会导致 mixin 失败。
    // 保留 firstGui shadow（该字段在旧/新版都存在）。
    @Shadow(remap = false)
    @Final
    public GuiContainer firstGui;

    // ---------- 运行时兼容封装 ----------
    private static class HandlerEntry {

        final IRecipeHandler handler;
        final int index;
        final Object handlerRef; // 若需要可访问原 handlerRef object

        HandlerEntry(IRecipeHandler handler, int index, Object handlerRef) {
            this.handler = handler;
            this.index = index;
            this.handlerRef = handlerRef;
        }
    }

    /**
     * 运行时解析 handler + index：
     * 1) 先尝试旧版字段（this.handler / this.recipeIndex）
     * 2) 找不到的话尝试新版字段 this.handlerRef 并从中提取 handler + recipeIndex
     */
    private HandlerEntry resolveHandlerEntry() {
        try {
            Class<?> cls = this.getClass();

            // 尝试旧版字段：handler + recipeIndex
            Field fHandler = findFieldRecursive(cls, "handler");
            Field fIndex = findFieldRecursive(cls, "recipeIndex");
            if (fHandler != null && fIndex != null) {
                fHandler.setAccessible(true);
                fIndex.setAccessible(true);
                Object handlerObj = fHandler.get(this);
                int idx = fIndex.getInt(this);
                if (handlerObj instanceof IRecipeHandler) {
                    return new HandlerEntry((IRecipeHandler) handlerObj, idx, null);
                }
            }

            // 尝试新版字段 handlerRef（并从中提取 inner handler + recipeIndex）
            Field fHandlerRef = findFieldRecursive(cls, "handlerRef");
            if (fHandlerRef != null) {
                fHandlerRef.setAccessible(true);
                Object refObj = fHandlerRef.get(this);
                if (refObj != null) {
                    // inner field names in RecipeHandlerRef: "handler" and "recipeIndex"
                    Field innerHandler = findFieldRecursive(refObj.getClass(), "handler");
                    Field innerIndex = findFieldRecursive(refObj.getClass(), "recipeIndex");
                    if (innerHandler != null && innerIndex != null) {
                        innerHandler.setAccessible(true);
                        innerIndex.setAccessible(true);
                        Object handlerObj = innerHandler.get(refObj);
                        int idx = innerIndex.getInt(refObj);
                        if (handlerObj instanceof IRecipeHandler) {
                            return new HandlerEntry((IRecipeHandler) handlerObj, idx, refObj);
                        }
                    }
                    // 若没有 inner 字段，也可尝试 getter 方法（可按需补充）
                }
            }
        } catch (Throwable t) {
            // 忽略反射异常，最终返回 null 表示无法解析
        }
        return null;
    }

    private static Field findFieldRecursive(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
    // ---------- 结束 运行时兼容封装 ----------

    @Inject(method = "handleHotkeys", at = @At("TAIL"), remap = false)
    private void handleHotkeys(GuiContainer gui, int mousex, int mousey, Map<String, String> hotkeys,
        CallbackInfoReturnable<Map<String, String>> cir) {
        if (gui instanceof IGuiContainerOverlay gur) {
            if (gur.getFirstScreen() != null && AE2ThingAPI.instance()
                .terminal()
                .isCraftingTerminal(gur.getFirstScreen())) {
                hotkeys.put(translate("gui.request_missing_item.key"), translate("gui.request_missing_item"));
                hotkeys.put(
                    translate("gui.request_missing_item_no_preview.key"),
                    translate("gui.request_missing_item_no_preview"));
            }
        }
    }

    private IItemList<IAEItemStack> items = null;

    @Inject(method = "overlayRecipe", at = @At("TAIL"), remap = false)
    public void overlayRecipe(boolean shift, CallbackInfo ci) {
        moveItems();

        if (!GuiScreen.isCtrlKeyDown() || !(firstGui instanceof AEBaseGui gui)) return;

        HandlerEntry he = resolveHandlerEntry();
        if (he == null) return; // 无法取得 handler/index，直接返回（安全失败）

        final List<PositionedStack> ingredients = he.handler.getIngredientStacks(he.index);
        IItemList<IAEItemStack> list = null;
        if (AE2ThingAPI.instance()
            .terminal()
            .isCraftingTerminal(gui)) {
            IDisplayRepo repo = AE2ThingAPI.instance()
                .terminal()
                .getCraftingTerminal()
                .get(gui.inventorySlots.getClass())
                .gerRepo(gui);
            if (repo instanceof ItemRepo) {
                list = copyItemList(Ae2ReflectClient.getList((ItemRepo) repo));
            }
        }
        final List<ItemStack> invStacks = firstGui.inventorySlots.inventorySlots.stream()
            .filter(
                s -> s != null && s.getStack() != null
                    && s.getStack().stackSize > 0
                    && s.isItemValid(s.getStack())
                    && s.canTakeStack(firstGui.mc.thePlayer))
            .map(
                s -> s.getStack()
                    .copy())
            .collect(Collectors.toCollection(ArrayList::new));

        out: for (PositionedStack stack : ingredients) {
            Optional<ItemStack> used = invStacks.stream()
                .filter(is -> is.stackSize > 0 && stack.contains(is))
                .findAny();
            if (used.isPresent()) {
                ItemStack is = used.get();
                is.stackSize -= 1;
            } else if (list != null) {
                IAEItemStack item = AEItemStack.create(stack.item);
                IAEItemStack stored = list.findPrecise(item);
                if (stored != null) {
                    if (stored.getStackSize() > 0) {
                        stored.decStackSize(1);
                        continue;
                    }
                }
                for (IAEItemStack is : list.findFuzzy(item, FuzzyMode.IGNORE_ALL)) {
                    if (is.getStackSize() > 0 && stack.contains(is.getItemStack())) {
                        is.decStackSize(1);
                        continue out;
                    }
                }
                if (stored != null && stored.isCraftable()) {
                    addMissingItem(stored);
                    continue;
                }
                for (IAEItemStack is : list.findFuzzy(item, FuzzyMode.IGNORE_ALL)) {
                    if (is.isCraftable() && stack.contains(is.getItemStack())) {
                        addMissingItem(is);
                        break;
                    }
                }
            }
        }
        if (this.items != null) {
            MinecraftForge.EVENT_BUS.post(new CraftTracking(this.items));
        }
    }

    private void moveItems() {
        if (AE2ThingAPI.instance()
            .terminal()
            .isCraftingTerminal(this.firstGui)) {
            HandlerEntry he = resolveHandlerEntry();
            if (he == null) return;
            ICraftingTerminalAdapter adapter = AE2ThingAPI.instance()
                .terminal()
                .getCraftingTerminal()
                .get(this.firstGui.inventorySlots.getClass());
            adapter.moveItems(this.firstGui, he.handler, he.index);
        }
    }

    private void addMissingItem(IAEItemStack stored) {
        if (this.items == null) {
            this.items = AEApi.instance()
                .storage()
                .createPrimitiveItemList();
        }
        IAEItemStack is = stored.copy();
        is.setStackSize(1);
        this.items.add(is);
    }

    private IItemList<IAEItemStack> copyItemList(IItemList<IAEItemStack> list) {
        IItemList<IAEItemStack> result = AEApi.instance()
            .storage()
            .createItemList();
        if (list == null) return null;
        for (IAEItemStack is : list) {
            result.add(is.copy());
        }
        return result;
    }
}
