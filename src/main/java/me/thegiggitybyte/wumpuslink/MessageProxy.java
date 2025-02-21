package me.thegiggitybyte.wumpuslink;

import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.Placeholders;
import me.thegiggitybyte.wumpuslink.config.JsonConfiguration;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.intent.Intent;
import org.javacord.api.entity.message.WebhookMessageBuilder;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.message.mention.AllowedMentionsBuilder;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.util.logging.ExceptionLogger;

import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class MessageProxy {
    public final static URL DEFAULT_AVATAR_URL;

    private static DiscordApi discordApi;
    private static MinecraftServer minecraftServer;

    static {
        DEFAULT_AVATAR_URL = WumpusLink.createUrl("https://i.imgur.com/x4IwajC.png"); // Repeating command block
        ServerLifecycleEvents.SERVER_STARTING.register(server -> minecraftServer = server);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> connectToDiscord());
    }

    public static CompletableFuture<Void> sendMessageToDiscord(String authorName, URL avatarUrl, String message, EmbedBuilder embed) {
        if ((message == null || message.isBlank()) && embed == null)
            throw new RuntimeException("message and embed cannot both be empty");

        var webhookMessage = new WebhookMessageBuilder()
                .setDisplayName((authorName == null || authorName.isBlank()) ? "Minecraft" : authorName)
                .setDisplayAvatar(avatarUrl == null ? DEFAULT_AVATAR_URL : avatarUrl);

        if (message != null && !message.isBlank())
            webhookMessage.setContent(message);

        if (embed != null)
            webhookMessage.addEmbed(embed);

        var allowedMentions = new AllowedMentionsBuilder()
                .setMentionEveryoneAndHere(false)
                .setMentionRoles(false)
                .setMentionUsers(false)
                .build();

        webhookMessage.setAllowedMentions(allowedMentions);

        var webhookUrl = JsonConfiguration.getUserInstance().getValue("discord-webhook-url").getAsString();
        return webhookMessage.sendSilently(discordApi, webhookUrl)
                .exceptionally(ExceptionLogger.get());
    }

    public static CompletableFuture<Void> sendMessageToDiscord(String authorName, URL avatarUrl, String message) {
        return sendMessageToDiscord(authorName, avatarUrl, message, null);
    }

    public static CompletableFuture<Void> sendMessageToDiscord(String authorName, URL avatarUrl, EmbedBuilder embed) {
        return sendMessageToDiscord(authorName, avatarUrl, null, embed);
    }

    public static CompletableFuture<Void> sendMessageToDiscord(String authorName, URL avatarUrl, Text messageText) {
        return sendMessageToDiscord(authorName, avatarUrl, messageText.getString());
    }

    public static CompletableFuture<Void> sendPlayerMessageToDiscord(PlayerEntity author, String message) {
        return sendMessageToDiscord(
                author.getDisplayName().getString(),
                WumpusLink.getMinecraftPlayerHeadUrl(author.getUuid()),
                message
        );
    }

    public static CompletableFuture<Void> sendPlayerMessageToDiscord(PlayerEntity author, EmbedBuilder embed) {
        return sendMessageToDiscord(
                author.getDisplayName().getString(),
                WumpusLink.getMinecraftPlayerHeadUrl(author.getUuid()),
                embed
        );
    }

    public static CompletableFuture<Void> sendPlayerMessageToDiscord(PlayerEntity author, Text messageText) {
        return sendPlayerMessageToDiscord(author, messageText.getString());
    }

    public static CompletableFuture<Void> sendServerMessageToDiscord(String message) {
        return sendMessageToDiscord(null, null, message);
    }

    public static CompletableFuture<Void> sendServerMessageToDiscord(EmbedBuilder embed) {
        return sendMessageToDiscord(null, null, embed);
    }

    public static CompletableFuture<Void> sendServerMessageToDiscord(Text messageText) {
        return sendMessageToDiscord(null, null, messageText);
    }

     static void connectToDiscord() {
        disconnectFromDiscord();

        var token = JsonConfiguration.getUserInstance().getValue("discord-bot-token").getAsString();
        discordApi = new DiscordApiBuilder()
                .setToken(token)
                .addIntents(Intent.MESSAGE_CONTENT)
                .login()
                .join();

        var channelId = JsonConfiguration.getUserInstance().getValue("discord-channel-id").getAsString();
        var channel = discordApi.getServerTextChannelById(channelId).orElseThrow();

        channel.addMessageCreateListener(MessageProxy::sendMessageToMinecraft);
    }

    static void disconnectFromDiscord() {
        if (discordApi != null)
            discordApi.disconnect().join();
    }

    private static void sendMessageToMinecraft(MessageCreateEvent event) {
        var author = event.getMessage().getAuthor();
        if (author.isBotUser() || author.isWebhook()) return;

        var userInfo = author.getDiscriminatedName() + " (" + author.getIdAsString() + ")";
        var userInfoText = Text.literal(userInfo).setStyle(Style.EMPTY.withItalic(true));
        var userHoverEvent = HoverEvent.Action.SHOW_TEXT.buildHoverEvent(userInfoText);
        var userStyle = Style.EMPTY.withHoverEvent(userHoverEvent);

        author.getRoleColor().ifPresent(color -> userInfoText.setStyle(Style.EMPTY.withColor(color.getRGB())));

        MutableText replyText = Text.literal("");
        var optionalMessageReference = event.getMessage().getMessageReference();
        if (optionalMessageReference.isPresent() && optionalMessageReference.get().getMessage().isPresent()) {
            var replyMessage = optionalMessageReference.get().getMessage().get();
            var replyAuthor = replyMessage.getAuthor();
            var replyInfoText = replyAuthor.getDiscriminatedName() + " (" + replyAuthor.getIdAsString() + ")\n\n" + replyMessage.getReadableContent();
            var replyHoverEvent = HoverEvent.Action.SHOW_TEXT.buildHoverEvent(Text.literal(replyInfoText));
            var replyStyle = Style.EMPTY.withHoverEvent(replyHoverEvent).withColor(Formatting.AQUA);
            replyText = Text.literal("[↩] ").setStyle(replyStyle); // hope that emoji works
        }

        var senderText = Text.literal(author.getDisplayName()).setStyle(userStyle);
        var originalText = Text.literal(event.getReadableMessageContent());
        var parsedText = Placeholders.parseText(originalText, PlaceholderContext.of(minecraftServer)); // TODO: limit parsing to only markdown

        var messageText = Text.literal("")
                .append(replyText)
                .append(senderText)
                .append(" » ")
                .append(parsedText);

        minecraftServer.getPlayerManager().broadcast(messageText, false);
    }
}
