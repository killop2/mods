@echo off
chcp 65001 > nul
mkdir src\main\java\com\example\playermodels\client 2>nul
mkdir src\main\java\com\example\playermodels\mixin 2>nul
mkdir src\main\java\com\example\playermodels\net 2>nul

echo package com.example.playermodels;> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo import net.neoforged.bus.api.IEventBus;>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo import net.neoforged.fml.ModContainer;>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo import net.neoforged.fml.common.Mod;>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo import net.neoforged.neoforge.common.NeoForge;>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo @Mod(PlayerModelsMod.MOD_ID)>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo public final class PlayerModelsMod {>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo     public static final String MOD_ID = "playermodels";>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo     public PlayerModelsMod(IEventBus modBus, ModContainer container) {>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo         AppliedModelAttachment.ATTACHMENTS.register(modBus);>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo         modBus.addListener(PlayerModelsMod::registerPayloads);>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo         NeoForge.EVENT_BUS.addListener(PlayerModelEvents::onPlayerClone);>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo         NeoForge.EVENT_BUS.addListener(PlayerModelEvents::onPlayerLoggedIn);>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo         NeoForge.EVENT_BUS.addListener(PlayerModelEvents::onRegisterCommands);>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo     }>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo     private static void registerPayloads(RegisterPayloadHandlersEvent event) {>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo         var r = event.registrar("1");>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo         r.playToServer(com.example.playermodels.net.ChooseModelPayload.TYPE, com.example.playermodels.net.ChooseModelPayload.STREAM_CODEC, PlayerModelEvents::handleChoose);>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo         r.playToClient(com.example.playermodels.net.ModelSyncPayload.TYPE, com.example.playermodels.net.ModelSyncPayload.STREAM_CODEC, PlayerModelEvents::handleSync);>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo     }>> src\main\java\com\example\playermodels\PlayerModelsMod.java
echo }>> src\main\java\com\example\playermodels\PlayerModelsMod.java

echo package com.example.playermodels;> src\main\java\com\example\playermodels\ModelRegistry.java
echo import net.neoforged.fml.ModList;>> src\main\java\com\example\playermodels\ModelRegistry.java
echo import java.util.List;>> src\main\java\com\example\playermodels\ModelRegistry.java
echo public final class ModelRegistry {>> src\main\java\com\example\playermodels\ModelRegistry.java
echo     public record ModelEntry(String id, String name, float width, float height) {}>> src\main\java\com\example\playermodels\ModelRegistry.java
echo     private static List^<ModelEntry^> MODELS;>> src\main\java\com\example\playermodels\ModelRegistry.java
echo     public static List^<ModelEntry^> models() {>> src\main\java\com\example\playermodels\ModelRegistry.java
echo         if (MODELS == null) {>> src\main\java\com\example\playermodels\ModelRegistry.java
echo             MODELS = ModList.get().isLoaded("huggynpc") ? List.of(>> src\main\java\com\example\playermodels\ModelRegistry.java
echo                 new ModelEntry("huggy", "Huggy", 0.9F, 2.8F),>> src\main\java\com\example\playermodels\ModelRegistry.java
echo                 new ModelEntry("mommy", "Mommy", 3.5F, 3.2F),>> src\main\java\com\example\playermodels\ModelRegistry.java
echo                 new ModelEntry("doey", "Doey", 1.6F, 2.8F)>> src\main\java\com\example\playermodels\ModelRegistry.java
echo             ) : List.of();>> src\main\java\com\example\playermodels\ModelRegistry.java
echo         }>> src\main\java\com\example\playermodels\ModelRegistry.java
echo         return MODELS;>> src\main\java\com\example\playermodels\ModelRegistry.java
echo     }>> src\main\java\com\example\playermodels\ModelRegistry.java
echo     public static ModelEntry byId(String id) {>> src\main\java\com\example\playermodels\ModelRegistry.java
echo         for (ModelEntry m : models()) if (m.id().equals(id)) return m;>> src\main\java\com\example\playermodels\ModelRegistry.java
echo         return null;>> src\main\java\com\example\playermodels\ModelRegistry.java
echo     }>> src\main\java\com\example\playermodels\ModelRegistry.java
echo }>> src\main\java\com\example\playermodels\ModelRegistry.java

echo package com.example.playermodels;> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo import net.minecraft.world.entity.player.Player;>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo import net.neoforged.neoforge.attachment.AttachmentType;>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo import net.neoforged.neoforge.registries.DeferredRegister;>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo import net.neoforged.neoforge.registries.NeoForgeRegistries;>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo import java.util.function.Supplier;>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo public final class AppliedModelAttachment {>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo     public static final DeferredRegister^<AttachmentType^<?^>^> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PlayerModelsMod.MOD_ID);>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo     public static final Supplier^<AttachmentType^<String^>^> MODEL = ATTACHMENTS.register("model", () -^> AttachmentType.string("").build());>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo     public static String get(Player player) {>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo         String s = player.getData(MODEL);>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo         return s.isEmpty() ? null : s;>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo     }>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo     public static void set(Player player, String modelId) {>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo         player.setData(MODEL, modelId == null ? "" : modelId);>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo     }>> src\main\java\com\example\playermodels\AppliedModelAttachment.java
echo }>> src\main\java\com\example\playermodels\AppliedModelAttachment.java

echo package com.example.playermodels;> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo import com.example.playermodels.net.ChooseModelPayload;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo import com.example.playermodels.net.ModelSyncPayload;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo import net.minecraft.network.chat.Component;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo import net.minecraft.server.level.ServerPlayer;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo import net.minecraft.world.entity.player.Player;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo import net.neoforged.neoforge.event.entity.player.PlayerEvent;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo import net.neoforged.neoforge.event.RegisterCommandsEvent;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo import net.neoforged.neoforge.network.PacketDistributor;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo import net.neoforged.neoforge.network.handling.IPayloadContext;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo import com.mojang.brigadier.arguments.StringArgumentType;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo public final class PlayerModelEvents {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo     public static void onPlayerClone(PlayerEvent.Clone event) {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         String id = AppliedModelAttachment.get(event.getOriginal());>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         if (id != null) AppliedModelAttachment.set(event.getEntity(), id);>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo     }>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo     public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         if (!(event.getEntity() instanceof ServerPlayer sp)) return;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         String id = AppliedModelAttachment.get(sp);>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         if (id != null) PacketDistributor.sendToAllPlayers(new ModelSyncPayload(sp.getUUID(), id));>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo     }>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo     public static void handleChoose(ChooseModelPayload payload, IPayloadContext ctx) {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         if (!(ctx.player() instanceof ServerPlayer sp)) return;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         String id = payload.modelId();>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         if (id == null || id.isEmpty()) {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo             AppliedModelAttachment.set(sp, null);>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo             PacketDistributor.sendToAllPlayers(new ModelSyncPayload(sp.getUUID(), ""));>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo             return;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         }>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         if (ModelRegistry.byId(id) == null) {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo             sp.displayClientMessage(Component.literal("Unknown: " + id), true);>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo             return;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         }>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         AppliedModelAttachment.set(sp, id);>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         sp.refreshDimensions();>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         PacketDistributor.sendToAllPlayers(new ModelSyncPayload(sp.getUUID(), id));>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo     }>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo     public static void handleSync(ModelSyncPayload payload, IPayloadContext ctx) {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         com.example.playermodels.client.ClientModelCache.set(payload.player(), payload.modelId());>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo     }>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo     public static void onRegisterCommands(RegisterCommandsEvent event) {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         event.getDispatcher().register(>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo             net.minecraft.commands.Commands.literal("playermodels")>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                 .requires(s -^> s.hasPermission(0))>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                 .then(net.minecraft.commands.Commands.literal("set")>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                     .then(net.minecraft.commands.Commands.argument("model", StringArgumentType.word())>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                         .executes(c -^> {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                             String id = StringArgumentType.getString(c, "model");>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                             Player p = c.getSource().getPlayerOrException();>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                             if (ModelRegistry.byId(id) == null) {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                                 c.getSource().sendFailure(Component.literal("Unknown: " + id));>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                                 return 0;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                             }>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                             AppliedModelAttachment.set(p, id);>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                             p.refreshDimensions();>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                             PacketDistributor.sendToAllPlayers(new ModelSyncPayload(p.getUUID(), id));>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                             return 1;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                         })))>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                 .then(net.minecraft.commands.Commands.literal("clear").executes(c -^> {>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                     Player p = c.getSource().getPlayerOrException();>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                     AppliedModelAttachment.set(p, null);>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                     p.refreshDimensions();>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                     PacketDistributor.sendToAllPlayers(new ModelSyncPayload(p.getUUID(), ""));>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                     return 1;>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo                 }))>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo         );>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo     }>> src\main\java\com\example\playermodels\PlayerModelEvents.java
echo }>> src\main\java\com\example\playermodels\PlayerModelEvents.java

echo package com.example.playermodels.client;> src\main\java\com\example\playermodels\client\ClientModelCache.java
echo import java.util.HashMap;>> src\main\java\com\example\playermodels\client\ClientModelCache.java
echo import java.util.Map;>> src\main\java\com\example\playermodels\client\ClientModelCache.java
echo import java.util.UUID;>> src\main\java\com\example\playermodels\client\ClientModelCache.java
echo public final class ClientModelCache {>> src\main\java\com\example\playermodels\client\ClientModelCache.java
echo     private static final Map^<UUID, String^> MODELS = new HashMap^<^>();>> src\main\java\com\example\playermodels\client\ClientModelCache.java
echo     public static void set(UUID uuid, String modelId) { MODELS.put(uuid, modelId); }>> src\main\java\com\example\playermodels\client\ClientModelCache.java
echo     public static String get(UUID uuid) { return MODELS.get(uuid); }>> src\main\java\com\example\playermodels\client\ClientModelCache.java
echo }>> src\main\java\com\example\playermodels\client\ClientModelCache.java

echo package com.example.playermodels.client;> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo import com.example.huggynpc.client.HuggyModel;>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo import com.example.playermodels.PlayerModelsMod;>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo import net.minecraft.client.player.AbstractClientPlayer;>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo import net.minecraft.resources.ResourceLocation;>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo import net.neoforged.api.distmarker.Dist;>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo import net.neoforged.bus.api.SubscribeEvent;>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo import net.neoforged.fml.common.EventBusSubscriber;>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo import net.neoforged.neoforge.client.event.RenderPlayerEvent;>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo import com.mojang.math.Axis;>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo @EventBusSubscriber(modid = PlayerModelsMod.MOD_ID, value = Dist.CLIENT)>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo public final class PlayerModelRenderer {>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo     @SubscribeEvent>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo     public static void onRenderPlayer(RenderPlayerEvent.Pre event) {>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         AbstractClientPlayer player = (AbstractClientPlayer) event.getEntity();>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         String id = ClientModelCache.get(player.getUUID());>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         if (id == null || id.isEmpty()) return;>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         event.setCanceled(true);>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         ResourceLocation skin = ResourceLocation.fromNamespaceAndPath("huggynpc", "models/entity/" + id + ".skin");>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         ResourceLocation texture = ResourceLocation.fromNamespaceAndPath("huggynpc", "textures/entity/" + id + ".png");>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         HuggyModel^<AbstractClientPlayer^> model = new HuggyModel^<^>(skin);>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         float partial = event.getPartialTick();>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         model.setupAnim(player, player.walkAnimation.position(partial), player.walkAnimation.speed(partial), player.tickCount + partial, player.getYHeadRot() - player.yBodyRot, player.getXRot());>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         event.getPoseStack().pushPose();>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         event.getPoseStack().mulPose(Axis.YP.rotationDegrees(180.0F - player.yBodyRot));>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         model.renderToBuffer(event.getPoseStack(), event.getMultiBufferSource().getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(texture)), event.getPackedLight(), net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo         event.getPoseStack().popPose();>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo     }>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java
echo }>> src\main\java\com\example\playermodels\client\PlayerModelRenderer.java

echo package com.example.playermodels.mixin;> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo import com.example.playermodels.AppliedModelAttachment;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo import com.example.playermodels.ModelRegistry;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo import net.minecraft.world.entity.EntityDimensions;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo import net.minecraft.world.entity.player.Player;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo import org.spongepowered.asm.mixin.Mixin;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo import org.spongepowered.asm.mixin.injection.At;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo import org.spongepowered.asm.mixin.injection.Inject;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo @Mixin(Player.class)>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo public abstract class PlayerDimensionsMixin {>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo     @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true, require = 0)>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo     private void playermodels$dimensions(CallbackInfoReturnable^<EntityDimensions^> cir) {>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo         Player self = (Player) (Object) this;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo         String id = AppliedModelAttachment.get(self);>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo         if (id == null) return;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo         ModelRegistry.ModelEntry m = ModelRegistry.byId(id);>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo         if (m == null) return;>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo         cir.setReturnValue(EntityDimensions.scalable(m.width(), m.height()));>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo     }>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java
echo }>> src\main\java\com\example\playermodels\mixin\PlayerDimensionsMixin.java

echo package com.example.playermodels.net;> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo import com.example.playermodels.PlayerModelsMod;>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo import net.minecraft.network.FriendlyByteBuf;>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo import net.minecraft.network.codec.ByteBufCodecs;>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo import net.minecraft.network.codec.StreamCodec;>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo import net.minecraft.network.protocol.common.custom.CustomPacketPayload;>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo import net.minecraft.resources.ResourceLocation;>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo public record ChooseModelPayload(String modelId) implements CustomPacketPayload {>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo     public static final Type^<ChooseModelPayload^> TYPE = new Type^<^>(ResourceLocation.fromNamespaceAndPath(PlayerModelsMod.MOD_ID, "choose"));>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo     public static final StreamCodec^<FriendlyByteBuf, ChooseModelPayload^> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ChooseModelPayload::modelId, ChooseModelPayload::new);>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo     @Override public Type^<? extends CustomPacketPayload^> type() { return TYPE; }>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java
echo }>> src\main\java\com\example\playermodels\net\ChooseModelPayload.java

echo package com.example.playermodels.net;> src\main\java\com\example\playermodels\net\ModelSyncPayload.java
echo import com.example.playermodels.PlayerModelsMod;>> src\main\java\com\example\playermodels\net\ModelSyncPayload.java
echo import net.minecraft.network.FriendlyByteBuf;>> src\main\java\com\example\playermodels\net\ModelSyncPayload.java
echo import net.minecraft.network.codec.ByteBufCodecs;>> src\main\java\com\example\playermodels\net\ModelSyncPayload.java
echo import net.minecraft.network.codec.StreamCodec;>> src\main\java\com\example\playermodels\net\ModelSyncPayload.java
echo import net.minecraft.network.protocol.common.custom.CustomPacketPayload;>> src\main\java\com\example\playermodels\net\ModelSyncPayload.java
echo import net.minecraft.resources.ResourceLocation;>> src\main\java\com\example\playermodels\net\ModelSyncPayload.java
echo import java.util.UUID;>> src\main\java\com\example\playermodels\net\ModelSyncPayload.java
echo public record ModelSyncPayload(UUID player, String modelId) implements CustomPacketPayload {>> src\main\java\com\example\playermodels\net\ModelSyncPayload.java
echo     public static final Type^<ModelSyncPayload^> TYPE = new Type^<^>(ResourceLocation.fromNamespaceAndPath(PlayerModelsMod.MOD_ID, "sync"));>> src\main\java\com\example\playermodels\net\ModelSyncPayload.java
echo     public static final StreamCodec^<FriendlyByteBuf, ModelSyncPayload^> STREAM_CODEC = StreamCodec.composite(net.minecraft.core.UUIDUtil.STREAM_CODEC, ModelSyncPayload::player, ByteBufCodecs.STRING_UTF8, ModelSyncPayload::modelId, ModelSyncPayload::new);>> src\main\java\com\example\playermodels\net\ModelSync
