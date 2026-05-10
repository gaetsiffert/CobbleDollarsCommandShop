package fr.cobbledollars.commandshops.feedback;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

public record FeedbackConfig(
        EventConfig buySuccess,
        EventConfig buyFailure,
        EventConfig sellSuccess,
        EventConfig sellFailure,
        EventConfig shopDenied
) {
    public static FeedbackConfig defaults() {
        SoundSpec successSound = new SoundSpec(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.25F, 1.8F);
        SoundSpec failSound = new SoundSpec(SoundEvents.VILLAGER_NO, 0.8F, 1.0F);
        return new FeedbackConfig(
                new EventConfig(EnumSet.of(FeedbackChannel.ACTION_BAR, FeedbackChannel.SOUND), successSound),
                new EventConfig(EnumSet.of(FeedbackChannel.ACTION_BAR, FeedbackChannel.SOUND), failSound),
                new EventConfig(EnumSet.of(FeedbackChannel.ACTION_BAR, FeedbackChannel.SOUND), successSound),
                new EventConfig(EnumSet.of(FeedbackChannel.ACTION_BAR, FeedbackChannel.SOUND), failSound),
                new EventConfig(EnumSet.of(FeedbackChannel.CHAT, FeedbackChannel.SOUND), failSound)
        );
    }

    public record EventConfig(Set<FeedbackChannel> channels, SoundSpec sound) {
        public EventConfig {
            if (channels == null || channels.isEmpty()) {
                channels = Set.of();
            } else {
                channels = Set.copyOf(channels);
            }
        }
    }

    public record SoundSpec(SoundEvent sound, float volume, float pitch) {
    }
}
