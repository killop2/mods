package com.example.huggynpc;

import com.example.huggynpc.client.ClientSetup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * Animated skinned NPC models (Huggy Wuggy, Mommy Long Legs, Doey, CatNap), each registered as a living entity.
 * Custom NPCs lists every living entity in NPC Wand -> Model -> Entities; choosing one there makes a normal
 * Custom NPC look like it (hitbox from the entity type), while AI, stats and physics stay Custom NPCs'.
 * The spawn eggs create such a Custom NPC directly.
 */
@Mod(HuggyNpcMod.MOD_ID)
public final class HuggyNpcMod {
    public static final String MOD_ID = "huggynpc";

    /** One NPC model: assets/huggynpc/models/entity/{id}.skin and textures/entity/{id}.png. */
    public record NpcModel(String id, String name, float width, float height, float eyeHeight, int eggColor, int eggSpots,
                           DeferredHolder<EntityType<?>, EntityType<HuggyEntity>> type, DeferredItem<HuggySpawnItem> egg) {
        public String texture() {
            return MOD_ID + ":textures/entity/" + this.id + ".png";
        }
    }

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final List<NpcModel> MODELS = new ArrayList<>();

    public static final NpcModel HUGGY = add("huggy", "Huggy Wuggy", 0.9F, 2.8F, 2.45F, 0x2E3FA6, 0xE9C53A);
    public static final NpcModel MOMMY = add("mommy", "Mommy Long Legs", 3.5F, 3.2F, 2.9F, 0xE0457B, 0x5BC8D8);
    public static final NpcModel DOEY = add("doey", "Doey", 1.6F, 2.8F, 2.4F, 0x22B7C9, 0xF2A20C);
    public static final NpcModel CATNAP = add("catnap", "CatNap", 3.0F, 3.4F, 2.6F, 0x4A2C6B, 0xE8D04A);
    // masked guards in red jumpsuits (models and textures made by tools/GuardGen.java)
    public static final NpcModel GUARD_CIRCLE = add("guard_circle", "Guard O", 0.6F, 1.9F, 1.68F, 0xD6324A, 0x161616);
    public static final NpcModel GUARD_TRIANGLE = add("guard_triangle", "Guard Triangle", 0.6F, 1.9F, 1.68F, 0xD6324A, 0xF0F0F0);
    public static final NpcModel GUARD_SQUARE = add("guard_square", "Guard Square", 0.6F, 1.9F, 1.68F, 0x161616, 0xD6324A);
    // the two dolls (same generator); the girl's eyes turn red now and then (HuggyModel)
    // 3 blocks: a little taller than Huggy (2.8)
    public static final NpcModel DOLL_BOY = add("doll_boy", "Doll Boy", 0.9F, 3.0F, 2.58F, 0x1E4B33, 0xB39874);
    // the eight plush critters (converted from the user's CleanSmilingCritters.fbx: split per character by
    // tools/fbxconv/SplitCritters.java, animated by CritterAnim.java, voxelised by SkinToMc.java)
    public static final NpcModel CRITTER_BEAR = add("critter_bear", "Critter Bear", 0.9F, 1.8F, 1.35F, 0xD81E24, 0xFF8A9A);
    public static final NpcModel CRITTER_CAT = add("critter_cat", "Critter Cat", 0.9F, 1.8F, 1.35F, 0x7E4FC0, 0xF2D33C);
    public static final NpcModel CRITTER_CHICKEN = add("critter_chicken", "Critter Chicken", 0.9F, 1.8F, 1.35F, 0xFFD93A, 0xF07D30);
    public static final NpcModel CRITTER_DOG = add("critter_dog", "Critter Dog", 1.0F, 1.8F, 1.35F, 0xE87A22, 0xC9401C);
    public static final NpcModel CRITTER_ELEPHANT = add("critter_elephant", "Critter Elephant", 1.0F, 1.8F, 1.35F, 0x6FD6F2, 0x2E8FD8);
    public static final NpcModel CRITTER_PIG = add("critter_pig", "Critter Pig", 0.9F, 1.8F, 1.35F, 0xF57AB8, 0xC4245A);
    public static final NpcModel CRITTER_RABBIT = add("critter_rabbit", "Critter Rabbit", 0.9F, 1.8F, 1.35F, 0x8CD84E, 0x2E9E2A);
    public static final NpcModel CRITTER_UNICORN = add("critter_unicorn", "Critter Unicorn", 0.9F, 1.8F, 1.35F, 0xF2F2F2, 0x77D1CC);
    // a player in the green tracksuit with number 456
    public static final NpcModel PLAYER_456 = add("player_456", "Player 456", 0.6F, 1.9F, 1.7F, 0x2F6E7C, 0xEDEFEE);
    public static final NpcModel DOLL_GIRL = add("doll_girl", "Doll Girl", 0.9F, 3.0F, 2.58F, 0xE5661C, 0xF0D02C);
    // Lily Lovebraids (Poppy Playtime 5): a tall ball-jointed doll with three long braids (tools/LilyGen.java)
    public static final NpcModel LILY = add("lily", "Lily Lovebraids", 0.7F, 2.0F, 1.72F, 0x8E3CE0, 0x1D1B22);
    // Verity: a pixel ball with a Pac-Man mouth, the size of a head (voxel model built in code: client/VerityMesh)
    public static final NpcModel VERITY = add("verity", "Verity", 0.5F, 0.5F, 0.34F, 0xFFC81E, 0x2A1208, VerityEntity::new);

    private static NpcModel add(String id, String name, float w, float h, float eye, int c1, int c2) {
        return add(id, name, w, h, eye, c1, c2, HuggyEntity::new);
    }

    private static NpcModel add(String id, String name, float w, float h, float eye, int c1, int c2, EntityType.EntityFactory<HuggyEntity> factory) {
        DeferredHolder<EntityType<?>, EntityType<HuggyEntity>> type = ENTITIES.register(id,
                () -> EntityType.Builder.of(factory, MobCategory.CREATURE)
                        .sized(w, h).eyeHeight(eye).clientTrackingRange(10).build(MOD_ID + ":" + id));
        DeferredItem<HuggySpawnItem> egg = ITEMS.register(id + "_spawn_egg", () -> new HuggySpawnItem(byId(id), new Item.Properties()));
        NpcModel m = new NpcModel(id, name, w, h, eye, c1, c2, type, egg);
        MODELS.add(m);
        return m;
    }

    public static NpcModel byId(String id) {
        for (NpcModel m : MODELS) if (m.id().equals(id)) return m;
        throw new IllegalStateException(id);
    }

    public HuggyNpcMod(IEventBus modBus, ModContainer container) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(HuggyNpcMod::attributes);
        modBus.addListener(HuggyNpcMod::creativeTabs);
        modBus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent e) -> {
            var r = e.registrar("1");
            r.playToServer(NpcHitPayload.TYPE, NpcHitPayload.STREAM_CODEC, NpcCombat::handleHit);
            r.playToClient(VerityPayload.TYPE, VerityPayload.STREAM_CODEC, (p, ctx) -> {
                if (FMLEnvironment.dist == Dist.CLIENT) com.example.huggynpc.client.VerityClient.onPayload(p);
            });
        });
        container.registerConfig(ModConfig.Type.CLIENT, HuggyConfig.SPEC);
        NpcCombat.init();
        VerityBrain.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modBus);
        }
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        for (NpcModel m : MODELS) event.put(m.type().get(), HuggyEntity.createAttributes().build());
    }

    private static void creativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            for (NpcModel m : MODELS) event.accept(m.egg().get());
        }
    }
}
