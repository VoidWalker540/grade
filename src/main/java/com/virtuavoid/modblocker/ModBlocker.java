package com.virtuavoid.modblocker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class ModBlocker implements DedicatedServerModInitializer {
    public static final String MOD_ID = "modblocker";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ModBlockerConfig config;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTING.register(ModBlocker::loadConfig);

        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if (config == null) loadConfig(server);
            if (config == null || !config.enabled) return;

            sendResourcePack(handler, config);
            checkRules(handler);
        });

        LOGGER.info("ModBlocker initialized.");
    }

    private static void loadConfig(MinecraftServer server) {
        Path path = server.getRunDirectory().resolve("config").resolve("modblocker.json");
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                config = new ModBlockerConfig();
                Files.writeString(path, GSON.toJson(config));
                return;
            }
            config = GSON.fromJson(Files.readString(path), ModBlockerConfig.class);
            if (config == null) config = new ModBlockerConfig();
            if (config.rules == null) config.rules = new ArrayList<>();
            if (config.resourcePack == null) config.resourcePack = new ResourcePackConfig();
            LOGGER.info("Loaded {} ModBlocker rules.", config.rules.size());
        } catch (IOException | JsonParseException e) {
            LOGGER.error("Failed to load ModBlocker config. Using defaults.", e);
            config = new ModBlockerConfig();
        }
    }

    private static void sendResourcePack(ServerConfigurationNetworkHandler handler, ModBlockerConfig cfg) {
        ResourcePackConfig pack = cfg.resourcePack;
        if (pack == null || !pack.enabled || pack.url == null || pack.url.isBlank()) return;

        String hash = pack.sha1 == null ? "" : pack.sha1.trim();
        try {
            handler.sendPacket(new ResourcePackSendS2CPacket(
                    UUID.randomUUID(),
                    pack.url,
                    hash,
                    pack.required,
                    Optional.ofNullable(pack.prompt)
                            .filter(s -> !s.isBlank())
                            .map(Text::literal)
            ));
        } catch (Exception e) {
            LOGGER.error("Failed to send the configured resource pack.", e);
        }
    }

    private static void checkRules(ServerConfigurationNetworkHandler handler) {
        Set<Identifier> channels = ServerConfigurationNetworking.getSendable(handler);
        List<String> observed = channels.stream().map(Identifier::toString).sorted().toList();

        if (config.logDetections && !observed.isEmpty()) {
            LOGGER.info("Observed client channels: {}", observed);
        }

        for (Rule rule : config.rules) {
            if (rule == null || !rule.enabled || rule.value == null || rule.value.isBlank()) continue;
            for (String channel : observed) {
                if (matches(rule, channel)) {
                    String reason = rule.reason == null || rule.reason.isBlank()
                            ? "A blocked client mod/channel was detected."
                            : rule.reason;
                    LOGGER.warn("Blocked channel '{}' matched {} '{}'.", channel, rule.type, rule.value);
                    handler.disconnect(Text.literal(formatKickMessage(reason)));
                    return;
                }
            }
        }
    }

    private static boolean matches(Rule rule, String observed) {
        String type = rule.type == null ? "ID" : rule.type.trim().toUpperCase(Locale.ROOT);
        String value = rule.value.trim();

        return switch (type) {
            case "ID", "CHANNEL", "NAME" ->
                    observed.equalsIgnoreCase(value) ||
                    observed.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
            case "REGEX" -> {
                try {
                    yield Pattern.compile(value).matcher(observed).find();
                } catch (PatternSyntaxException e) {
                    LOGGER.warn("Invalid ModBlocker regex '{}': {}", value, e.getMessage());
                    yield false;
                }
            }
            case "LINK" -> {
                String slug = extractSlug(value);
                yield !slug.isBlank() && observed.toLowerCase(Locale.ROOT).contains(slug.toLowerCase(Locale.ROOT));
            }
            default -> false;
        };
    }

    private static String extractSlug(String value) {
        String cleaned = value.toLowerCase(Locale.ROOT)
                .replace("https://", "")
                .replace("http://", "");
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < cleaned.length()) cleaned = cleaned.substring(slash + 1);
        int query = cleaned.indexOf('?');
        if (query >= 0) cleaned = cleaned.substring(0, query);
        return cleaned.replaceAll("[^a-z0-9._-]", "");
    }

    private static String formatKickMessage(String reason) {
        String message = config.kickMessage == null
                ? "You have been disconnected: %reason%"
                : config.kickMessage;
        return message.replace("%reason%", reason);
    }

    public static final class ModBlockerConfig {
        public boolean enabled = true;
        public String mode = "blacklist";
        public String kickMessage = "You have been disconnected: %reason%";
        public boolean logDetections = true;
        public ResourcePackConfig resourcePack = new ResourcePackConfig();
        public List<Rule> rules = new ArrayList<>();
    }

    public static final class ResourcePackConfig {
        public boolean enabled = false;
        public String url = "";
        public String sha1 = "";
        public boolean required = true;
        public String prompt = "This server requires its resource pack.";
    }

    public static final class Rule {
        public String type = "ID";
        public String value = "";
        public String reason = "A blocked client mod/channel was detected.";
        public boolean enabled = true;
    }
}
