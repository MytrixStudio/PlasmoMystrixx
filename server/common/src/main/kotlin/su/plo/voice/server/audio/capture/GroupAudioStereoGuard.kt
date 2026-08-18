package su.plo.voice.server.audio.capture

internal class GroupAudioStereoGuard<K> {

    private val stereoByKey: MutableMap<K, Boolean> = HashMap()

    @Synchronized
    fun resolve(
        key: K,
        requestedStereo: Boolean,
        active: Boolean
    ): Decision {
        val previousStereo = stereoByKey[key]
        if (previousStereo == null) {
            stereoByKey[key] = requestedStereo
            return Decision(
                sourceStereo = requestedStereo,
                previousStereo = null,
                recreateSource = false,
                dropPacket = false
            )
        }

        if (previousStereo == requestedStereo) {
            return Decision(
                sourceStereo = previousStereo,
                previousStereo = previousStereo,
                recreateSource = false,
                dropPacket = false
            )
        }

        if (active) {
            return Decision(
                sourceStereo = previousStereo,
                previousStereo = previousStereo,
                recreateSource = false,
                dropPacket = true
            )
        }

        return Decision(
            sourceStereo = requestedStereo,
            previousStereo = previousStereo,
            recreateSource = true,
            dropPacket = false
        )
    }

    @Synchronized
    fun remember(key: K, stereo: Boolean) {
        stereoByKey[key] = stereo
    }

    @Synchronized
    fun remove(key: K) {
        stereoByKey.remove(key)
    }

    @Synchronized
    fun clear() {
        stereoByKey.clear()
    }

    data class Decision(
        val sourceStereo: Boolean,
        val previousStereo: Boolean?,
        val recreateSource: Boolean,
        val dropPacket: Boolean
    )
}
