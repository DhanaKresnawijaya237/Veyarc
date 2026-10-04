package com.veyarc.feature.assistant

import com.veyarc.model.AssetId
import com.veyarc.model.AssetKind
import com.veyarc.model.AssetReference
import com.veyarc.model.NormalizedRect
import kotlinx.serialization.Serializable

@Serializable
enum class AssistantImagePurpose { TARGET, BACKGROUND }

/** Names a submitted image and a crop, never a URL or provider-supplied image payload. */
@Serializable
data class AssistantImageAddition(
    val sourceAttachmentId: String,
    val assetId: String,
    val name: String,
    val purpose: AssistantImagePurpose = AssistantImagePurpose.TARGET,
    val crop: NormalizedRect = NormalizedRect(0.0, 0.0, 1.0, 1.0),
) {
    fun reference() = AssetReference(AssetId(assetId), AssetKind.IMAGE_TEMPLATE, name)
    fun validate() {
        require(name.isNotBlank() && name.length <= 64)
        require(java.util.UUID.fromString(sourceAttachmentId).toString() == sourceAttachmentId)
        reference()
        if (purpose == AssistantImagePurpose.BACKGROUND) require(crop == NormalizedRect(0.0, 0.0, 1.0, 1.0))
    }
}
