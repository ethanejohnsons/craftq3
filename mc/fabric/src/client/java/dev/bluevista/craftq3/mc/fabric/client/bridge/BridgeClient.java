package dev.bluevista.craftq3.mc.fabric.client.bridge;

import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudio;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import dev.bluevista.craftq3.mc.fabric.config.InstallationStatus;
import dev.bluevista.craftq3.mc.fabric.config.Q3Installation;
import dev.bluevista.craftq3.mc.fabric.net.BridgeRequestPayload;
import dev.bluevista.craftq3.mc.fabric.net.BridgeStatePayload;
import dev.bluevista.craftq3.mc.fabric.net.Q3DatagramPayload;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * The bridge as the client sees it: the server says when this player enters or leaves ({@link
 * BridgeStatePayload}); entering opens a {@link BridgeScreen} on the configured installation,
 * datagrams are routed to it, and closing it tells the server ({@link BridgeRequestPayload}). While
 * the bridge is open, Minecraft's own HUD (hotbar, bars, crosshair, held item names) is hidden
 * through Fabric's HUD element registry; chat and the player list stay. Render thread.
 */
public final class BridgeClient {
  private static final Logger LOGGER = LogUtils.getLogger();

  /** Minecraft HUD elements hidden while Quake's HUD is up. */
  private static final Identifier[] HIDDEN_HUD = {
    VanillaHudElements.CROSSHAIR,
    VanillaHudElements.HOTBAR,
    VanillaHudElements.ARMOR_BAR,
    VanillaHudElements.HEALTH_BAR,
    VanillaHudElements.FOOD_BAR,
    VanillaHudElements.AIR_BAR,
    VanillaHudElements.MOUNT_HEALTH,
    VanillaHudElements.INFO_BAR,
    VanillaHudElements.EXPERIENCE_LEVEL,
    VanillaHudElements.HELD_ITEM_TOOLTIP,
    VanillaHudElements.MOB_EFFECTS,
    VanillaHudElements.MISC_OVERLAYS
  };

  private final Q3Installation installation;
  private final Q3RenderContext renderContext;
  private final Supplier<MinecraftAudio> audio;
  private final Path gameDirectory;
  private final dev.bluevista.craftq3.mc.fabric.client.transfer.QuakeTransfers transfers;
  private final java.util.function.BiConsumer<
          Minecraft, dev.bluevista.craftq3.mc.fabric.client.screen.Q3Launch>
      openQuake;

  public BridgeClient(
      Q3Installation installation,
      Q3RenderContext renderContext,
      Supplier<MinecraftAudio> audio,
      Path gameDirectory,
      dev.bluevista.craftq3.mc.fabric.client.transfer.QuakeTransfers transfers,
      java.util.function.BiConsumer<
              Minecraft, dev.bluevista.craftq3.mc.fabric.client.screen.Q3Launch>
          openQuake) {
    this.transfers = Objects.requireNonNull(transfers);
    this.openQuake = Objects.requireNonNull(openQuake);
    this.installation = Objects.requireNonNull(installation);
    this.renderContext = Objects.requireNonNull(renderContext);
    this.audio = Objects.requireNonNull(audio);
    this.gameDirectory = Objects.requireNonNull(gameDirectory);
  }

  /** Registers the payload receivers and HUD replacements (client initialisation). */
  public void register() {
    ClientPlayNetworking.registerGlobalReceiver(
        Q3DatagramPayload.TYPE,
        (payload, context) -> {
          BridgeScreen screen = BridgeScreen.current(context.client());
          if (screen != null) screen.receive(payload.data());
        });
    ClientPlayNetworking.registerGlobalReceiver(
        BridgeStatePayload.TYPE, (payload, context) -> state(context.client(), payload));
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          BridgeScreen screen = BridgeScreen.current(client);
          if (screen != null) screen.ended();
        });
    for (Identifier id : HIDDEN_HUD)
      HudElementRegistry.replaceElement(
          id,
          original ->
              (graphics, delta) -> {
                if (BridgeScreen.current(Minecraft.getInstance()) == null)
                  original.extractRenderState(graphics, delta);
              });
  }

  private void state(Minecraft client, BridgeStatePayload payload) {
    BridgeScreen open = BridgeScreen.current(client);
    if (!payload.active()) {
      if (open != null) open.ended();
      if (!payload.message().isEmpty())
        client
            .gui
            .hud
            .getChat()
            .addClientSystemMessage(Component.literal("CraftQ3: " + payload.message()));
      return;
    }
    if (open != null) open.ended();
    CraftQ3Config config = installation.config();
    if (config.installationPath().isEmpty()) {
      client
          .gui
          .hud
          .getChat()
          .addClientSystemMessage(
              Component.literal("CraftQ3: no Quake III installation is configured (/q3 path)"));
      ClientPlayNetworking.send(
          new BridgeRequestPayload(BridgeRequestPayload.Kind.FAILED, "no installation"));
      return;
    }
    CoordinateTransform transform =
        new CoordinateTransform(
            payload.unitsPerBlock(), payload.originX(), payload.originY(), payload.originZ());
    String game = payload.game().isEmpty() ? config.game() : payload.game();
    LOGGER.info("CraftQ3: entering the bridge ({} at {})", game, transform);
    BridgeScreen screen =
        BridgeScreen.create(
            renderContext,
            audio.get(),
            InstallationStatus.installationRoot(config.installationPath().get()),
            game,
            gameDirectory,
            transform,
            (opened, session) -> transfers.registerQuake(client, opened, session, openQuake),
            () -> {
              if (ClientPlayNetworking.canSend(BridgeRequestPayload.TYPE))
                ClientPlayNetworking.send(
                    new BridgeRequestPayload(BridgeRequestPayload.Kind.LEAVE, ""));
            });
    client.gui.setScreen(screen);
  }
}
