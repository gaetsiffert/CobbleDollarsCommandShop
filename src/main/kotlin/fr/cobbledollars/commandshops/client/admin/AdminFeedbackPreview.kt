package fr.cobbledollars.commandshops.client.admin

import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource

/** Sound previews use the unsaved draft and never send anything to the server. */
object AdminFeedbackPreview {
    enum class PreviewStatus { PLAYED, NO_SOUND, INVALID_SOUND, UNKNOWN_SOUND, UNAVAILABLE_SOUND }
    data class PreviewSound(val id: ResourceLocation, val volume: Float, val pitch: Float)
    data class PreviewResult(val status: PreviewStatus, val soundId: String? = null)
    data class PreparedPreview(val result: PreviewResult, val sound: PreviewSound? = null)

    /** Injectable registry/resource lookups let tests exercise invalid and unavailable sounds. */
    fun prepare(
        root: JsonObject,
        eventKey: String,
        isKnown: (ResourceLocation) -> Boolean,
        hasResources: (ResourceLocation) -> Boolean
    ): PreparedPreview {
        val value = runCatching { AdminFeedbackSettings.effectiveField(root, eventKey, "sound") }.getOrElse {
            return PreparedPreview(PreviewResult(PreviewStatus.INVALID_SOUND))
        }
        if (value.isJsonNull) return PreparedPreview(PreviewResult(PreviewStatus.NO_SOUND))
        val sound = runCatching {
            require(value.isJsonObject)
            val objectValue = value.asJsonObject
            val idValue = objectValue.get("id")
            require(idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isString)
            val id = ResourceLocation.parse(idValue.asString)
            val volume = objectValue.get("volume").asString.toFloat()
            val pitch = objectValue.get("pitch").asString.toFloat()
            require(volume.isFinite() && volume >= 0 && pitch.isFinite() && pitch > 0)
            PreviewSound(id, volume, pitch)
        }.getOrElse { return PreparedPreview(PreviewResult(PreviewStatus.INVALID_SOUND)) }
        if (!isKnown(sound.id)) return PreparedPreview(PreviewResult(PreviewStatus.UNKNOWN_SOUND, sound.id.toString()))
        if (!hasResources(sound.id)) return PreparedPreview(PreviewResult(PreviewStatus.UNAVAILABLE_SOUND, sound.id.toString()))
        return PreparedPreview(PreviewResult(PreviewStatus.PLAYED, sound.id.toString()), sound)
    }

    fun play(root: JsonObject, eventKey: String, minecraft: Minecraft): PreviewResult {
        val manager = minecraft.soundManager
        val preview = prepare(root, eventKey, BuiltInRegistries.SOUND_EVENT::containsKey) { id ->
            (manager.getSoundEvent(id)?.weight ?: 0) > 0
        }
        preview.sound?.let { sound ->
            manager.play(SimpleSoundInstance(
                sound.id, SoundSource.PLAYERS, sound.volume, sound.pitch, SoundInstance.createUnseededRandom(),
                false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true
            ))
        }
        return preview.result
    }
}
