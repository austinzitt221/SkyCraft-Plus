// SkyCraft+ : Skyrim food for animals
package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyBreeding;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.combat.SkyFoods;
import dev.skycraft.combat.SkyrimActorEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

/**
 * Skyrim food given to animals: a Skyrim food or ingredient shortcut (or a Skyrim bone) used on an
 * animal is given to it instead of eaten. A Skyrim animal eats the Skyrim food its kind would eat
 * (SkyBreeding: in love, a baby growing, bones taming a dog or a wolf); a Minecraft animal eats the
 * Skyrim food standing for its own (seeds, wheat, carrots, apples, berries, meat, fish, a bone for
 * a wolf), exactly as if it were given that. Eaten, the giver's Skyrim gives one up.
 */
public final class SkyFeeding {
	private SkyFeeding() {
	}

	/** Client -> server: the player gave this Skyrim thing (SkyFoods bits) to that animal. */
	public record Feed(int entity, int foods, String plugin, int localId) implements CustomPacketPayload {
		public static final Type<Feed> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "feed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Feed> CODEC = StreamCodec.of((buf, f) -> {
			buf.writeVarInt(f.entity);
			buf.writeVarInt(f.foods);
			buf.writeUtf(f.plugin, 64);
			buf.writeInt(f.localId);
		}, buf -> new Feed(buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: it was eaten: one of it goes from the player's Skyrim. */
	public record Eaten(String plugin, int localId) implements CustomPacketPayload {
		public static final Type<Eaten> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "fed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Eaten> CODEC = StreamCodec.of((buf, e) -> {
			buf.writeUtf(e.plugin, 64);
			buf.writeInt(e.localId);
		}, buf -> new Eaten(buf.readUtf(64), buf.readInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static void feed(ServerPlayer player, Feed feed) {
		Entity entity = player.level().getEntity(feed.entity());
		if (entity == null || player.distanceToSqr(entity) > 8.0 * 8.0 || feed.foods() == 0) {
			return;
		}
		boolean eaten = false;
		if (entity instanceof SkyrimActorEntity proxy) {
			eaten = SkyBreeding.feedSkyrimFood(proxy, player, feed.foods(), SkyCombat.actor(proxy.formId()));
		} else if (entity instanceof Animal animal) {
			eaten = feedMinecraft(player, animal, feed.foods());
		}
		if (eaten && !player.hasInfiniteMaterials() && ServerPlayNetworking.canSend(player, Eaten.TYPE)) {
			ServerPlayNetworking.send(player, new Eaten(feed.plugin(), feed.localId()));
		}
		SkyCraft.LOG.info("SkyCraft: {} gave Skyrim food ({}) to {}: {}", player.getPlainTextName(), Integer.toHexString(feed.foods()), entity.getName().getString(),
			eaten ? "eaten" : "not wanted");
	}

	/**
	 * A Minecraft animal is given the Minecraft food the Skyrim one stands for, through its own use
	 * (breeding, a baby growing, a wolf's taming, a horse's): the hand holds that a moment. Eaten if
	 * it took it.
	 */
	private static boolean feedMinecraft(ServerPlayer player, Animal animal, int foods) {
		ItemStack food = SkyFoods.minecraftFood(animal, foods);
		if (food == null) {
			return false;
		}
		InteractionHand hand = InteractionHand.MAIN_HAND;
		ItemStack held = player.getItemInHand(hand);
		player.setItemInHand(hand, food);
		boolean took;
		try {
			var result = animal.interact(player, hand, animal.position());
			took = food.isEmpty() || player.hasInfiniteMaterials() && result.consumesAction();
		} finally {
			player.setItemInHand(hand, held);
		}
		return took;
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Feed.TYPE, Feed.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Eaten.TYPE, Eaten.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Feed.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> feed(player, payload));
		});
	}
}
