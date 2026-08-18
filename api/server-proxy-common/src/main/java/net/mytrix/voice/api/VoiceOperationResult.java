package net.mytrix.voice.api;

public record VoiceOperationResult(
        boolean success,
        VoiceErrorCode errorCode,
        String message
) {

    public static VoiceOperationResult ok(String message) {
        return new VoiceOperationResult(true, VoiceErrorCode.NONE, message);
    }

    public static VoiceOperationResult error(VoiceErrorCode errorCode, String message) {
        return new VoiceOperationResult(false, errorCode, message);
    }
}
