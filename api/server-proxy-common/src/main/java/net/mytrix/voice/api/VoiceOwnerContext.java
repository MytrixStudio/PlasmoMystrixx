package net.mytrix.voice.api;

public interface VoiceOwnerContext {

    String namespace();

    String version();

    DynamicVoiceApi api();

    default VoiceSession createSession(VoiceSessionId sessionId, VoiceSessionOptions options) {
        ensureOwner(sessionId.namespace());
        return api().createSession(sessionId, options);
    }

    default VoiceRestrictionHandle applyRestriction(VoiceRestrictionRequest request) {
        ensureOwner(request.ownerNamespace());
        return api().applyRestriction(request);
    }

    default void closeOwnedSessions() {
        api().closeOwnedSessions(namespace());
    }

    private void ensureOwner(String namespace) {
        if (!namespace().equals(namespace)) {
            throw new VoiceOwnerMismatchException("Owner " + namespace() + " cannot operate on namespace " + namespace);
        }
    }
}
