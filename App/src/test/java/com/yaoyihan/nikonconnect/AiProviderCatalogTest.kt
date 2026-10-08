package com.yaoyihan.nikonconnect

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiProviderCatalogTest {
    private val first = AiProviderProfile("one", "国内图片接口", endpoint = "https://one.example.com/images", key = "key-one",
        models = listOf(AiProviderModel("paint-fast"), AiProviderModel("paint-hq")))
    private val second = AiProviderProfile("two", "备用接口", endpoint = "https://two.example.com/images", key = "key-two",
        models = listOf(AiProviderModel("other-model")))

    @Test fun defaultsAreTwoIndependentEditableProfiles() {
        val profiles = aiDefaultProfiles()
        assertEquals(2, profiles.size)
        assertEquals(setOf(AiImageProvider.DOUBAO, AiImageProvider.QWEN), profiles.map { it.protocol }.toSet())
        assertEquals(2, profiles.map { it.id }.distinct().size)
        assertTrue(profiles.all { it.key.isBlank() && it.models.size == 1 })
    }

    @Test fun sameProtocolSupportsMultipleProvidersAndModels() {
        val catalog = AiProviderCatalog(listOf(first, second)).select("one", "paint-hq")
        assertEquals(3, catalog.choices().size)
        val config = catalog.activeConfig()
        assertEquals("one", config.profileId)
        assertEquals("paint-hq", config.model)
        assertEquals(first.endpoint, config.endpoint)
        assertEquals(first.key, config.key)
        assertEquals(second.key, catalog.select("two", "other-model").activeConfig().key)
        assertFalse(catalog.toString().contains("key-one"))
    }

    @Test fun disablingAProviderSelectsAnotherAndCannotBeSelected() {
        val catalog = AiProviderCatalog(listOf(first, second), "one", "paint-hq").upsert(first.copy(enabled = false))
        assertEquals("two", catalog.activeProfileId)
        assertThrows(IllegalArgumentException::class.java) { catalog.select("one", "paint-hq") }
    }

    @Test fun disablingASelectedModelFallsBackWithinTheProvider() {
        val catalog = AiProviderCatalog(listOf(first, second), "one", "paint-hq")
            .upsert(first.copy(models = listOf(AiProviderModel("paint-fast"), AiProviderModel("paint-hq", false))))
        assertEquals("paint-fast", catalog.activeModelId)
        assertThrows(IllegalArgumentException::class.java) { catalog.select("one", "paint-hq") }
    }

    @Test fun deletionAndEmptyModelListsNeverLeaveADanglingSelection() {
        val catalog = AiProviderCatalog(listOf(first, second), "one", "paint-hq").remove("one")
        assertEquals("two", catalog.activeProfileId)
        val empty = catalog.upsert(second.copy(models = emptyList()))
        assertTrue(empty.activeProfileId.isBlank())
        assertTrue(empty.activeConfig().profileId.isBlank())
        assertTrue(empty.activeConfig().model.isBlank())
        assertTrue(empty.remove("two").profiles.isEmpty())
    }

    @Test fun editingOneProfileDoesNotReplaceOtherCredentialsOrModels() {
        val changed = first.copy(name = "新名称", models = first.models + AiProviderModel("new-model"))
        val catalog = AiProviderCatalog(listOf(first, second), "two", "other-model").upsert(changed)
        assertEquals(second, catalog.profiles.last())
        assertEquals("two", catalog.activeProfileId)
        assertEquals(3, catalog.profiles.first().models.size)
    }

    @Test fun legacyMigrationPreservesBothKeysCustomEndpointsAndActiveModel() {
        val legacy = listOf(AiImageConfig(AiImageProvider.DOUBAO, first.endpoint, "legacy-a", first.key),
            AiImageConfig(AiImageProvider.QWEN, second.endpoint, "legacy-b", second.key))
        val migrated = aiMigrateProfiles(legacy, AiImageProvider.QWEN)
        assertEquals(2, migrated.profiles.size)
        assertEquals(first.key, migrated.profiles.first().key)
        assertEquals(second.endpoint, migrated.activeConfig().endpoint)
        assertEquals("legacy-b", migrated.activeConfig().model)
        assertEquals(second.key, migrated.activeConfig().key)
    }

    @Test fun persistenceEncodesKeysAndPreservesSelectionAndEnabledFlags() {
        val catalog = AiProviderCatalog(listOf(first, second.copy(enabled = false)), "one", "paint-hq")
        val values = mapOf("cipher-a" to first.key, "cipher-b" to second.key)
        val encoded = AiProviderCatalogCodec.encode(catalog) { key -> values.entries.first { it.value == key }.key }
        assertFalse(encoded.contains(first.key))
        assertFalse(encoded.contains(second.key))
        assertEquals(catalog, AiProviderCatalogCodec.decode(encoded) { values.getValue(it) })
        assertEquals("cipher-a", JSONObject(encoded).getJSONArray("profiles").getJSONObject(0).getString("key"))
    }

    @Test fun anUnavailableEncryptionKeyOnlyClearsThatProfilesKey() {
        val encoded = AiProviderCatalogCodec.encode(AiProviderCatalog(listOf(first, second))) { "encrypted-$it" }
        val restored = AiProviderCatalogCodec.decode(encoded) {
            if (it.endsWith("one")) error("unavailable") else "key-two"
        }
        assertEquals("", restored.profiles.first().key)
        assertEquals(second.key, restored.profiles.last().key)
        assertEquals(first.models, restored.profiles.first().models)
    }

    @Test fun configurationCanBeSavedWithoutAKeyButRejectsInvalidValues() {
        first.copy(key = "").validate()
        assertThrows(IllegalArgumentException::class.java) { first.copy(name = "").validate() }
        assertThrows(IllegalArgumentException::class.java) { first.copy(key = "a\nb").validate() }
        assertThrows(IllegalArgumentException::class.java) { first.copy(endpoint = "http://example.com/api").validate() }
        assertThrows(IllegalArgumentException::class.java) { first.copy(models = listOf(AiProviderModel("x"), AiProviderModel("x"))).validate() }
        assertThrows(IllegalArgumentException::class.java) { first.copy(models = listOf(AiProviderModel("chat model"))).validate() }
    }
}
