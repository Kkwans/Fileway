package io.github.kkwans.fileway.remote

import org.json.JSONObject

data class HandoffMediaRef(
    val instanceId: String,
    val libraryId: String,
    val itemId: String,
    val itemRevision: Long,
    val editionId: String,
    val variantId: String,
    val bindingId: String,
    val sourceFileId: String,
    val fileRevision: Long,
    val identityEpoch: Long,
    val mappingRevision: Long? = null,
) {
    init {
        listOf(instanceId, libraryId, itemId, editionId, variantId, bindingId, sourceFileId).forEach {
            require(it.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "影视接力标识无效" }
        }
        require(itemRevision > 0 && fileRevision > 0 && identityEpoch > 0)
        require(mappingRevision == null || mappingRevision > 0)
    }

    fun json(): JSONObject = JSONObject()
        .put("intentVersion", 1)
        .put("instanceId", instanceId)
        .put("libraryId", libraryId)
        .put("itemId", itemId)
        .put("itemRevision", itemRevision)
        .put("editionId", editionId)
        .put("variantId", variantId)
        .put("bindingId", bindingId)
        .put("sourceFileId", sourceFileId)
        .put("fileRevision", fileRevision)
        .put("identityEpoch", identityEpoch)
        .apply { mappingRevision?.let { put("mappingRevision", it) } }

    companion object {
        fun from(value: JSONObject): HandoffMediaRef {
            require(value.getInt("intentVersion") == 1)
            val expected = setOf("intentVersion", "instanceId", "libraryId", "itemId", "itemRevision",
                "editionId", "variantId", "bindingId", "sourceFileId", "fileRevision", "identityEpoch")
            val keys = value.keys().asSequence().toSet()
            require(keys == expected || keys == expected + "mappingRevision")
            return HandoffMediaRef(
                instanceId = value.getString("instanceId"),
                libraryId = value.getString("libraryId"),
                itemId = value.getString("itemId"),
                itemRevision = value.getLong("itemRevision"),
                editionId = value.getString("editionId"),
                variantId = value.getString("variantId"),
                bindingId = value.getString("bindingId"),
                sourceFileId = value.getString("sourceFileId"),
                fileRevision = value.getLong("fileRevision"),
                identityEpoch = value.getLong("identityEpoch"),
                mappingRevision = if (value.has("mappingRevision")) value.getLong("mappingRevision") else null,
            )
        }
    }
}
