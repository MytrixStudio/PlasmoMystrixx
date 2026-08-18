package net.mytrix.voice.api.channel;

/**
 * Result category for channel membership operations.
 */
public enum MembershipStatus {
    ADDED,
    REMOVED,
    ALREADY_MEMBER,
    NOT_A_MEMBER,
    CHANNEL_NOT_FOUND,
    CHANNEL_CLOSED,
    MEMBER_LIMIT_REACHED,
    PLAYER_NOT_AVAILABLE,
    REJECTED,
    API_NOT_READY
}
