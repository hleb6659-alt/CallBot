package me.prime.callbot;

import java.util.UUID;

public record BotType(String id, String name, String skin, Factory factory) {

    public interface Factory {
        PvpBot create(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner);
    }
}
