package com.yaoyihan.nikonconnect

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal enum class AiImageProvider(val title: String, val endpoint: String, val model: String, val docs: String) {
    DOUBAO("豆包 · 火山方舟", "https://ark.cn-beijing.volces.com/api/v3/images/generations",
        "doubao-seedream-5-0-pro-260628", "https://docs.volcengine.com/docs/ark/seedream-4-0-5-0?lang=zh"),
    QWEN("千问 · 阿里云百炼", "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
        "qwen-image-3.0", "https://help.aliyun.com/zh/model-studio/qwen-image-edit-guide"),
}

internal data class AiImageConfig(
    val provider: AiImageProvider = AiImageProvider.DOUBAO,
    val endpoint: String = provider.endpoint,
    val model: String = provider.model,
    val key: String = "",
    val profileId: String = "",
    val profileName: String = provider.title,
) {
    override fun toString() = "AiImageConfig(provider=$provider, model=$model, key=[redacted])"
    fun normalized() = copy(endpoint = endpoint.trim().trimEnd('/'), model = model.trim(), key = key.trim())
    fun validate() {
        require(key.isNotBlank() && key.none { it.isWhitespace() || it.isISOControl() }) { "请填写有效的 API Key" }
        require(model.isNotBlank() && model.length <= 200 && model.none { it.isWhitespace() || it.isISOControl() }) { "请填写有效的模型 ID" }
        require(aiHttpsUrl(endpoint, allowQuery = false)) { "请求地址须为完整 HTTPS 接口，不能包含账号、查询参数或占位符" }
    }
}

internal data class AiProviderModel(val id: String, val enabled: Boolean = true)

internal data class AiProviderProfile(
    val id: String,
    val name: String,
    val protocol: AiImageProvider = AiImageProvider.DOUBAO,
    val endpoint: String = protocol.endpoint,
    val key: String = "",
    val enabled: Boolean = true,
    val models: List<AiProviderModel> = listOf(AiProviderModel(protocol.model)),
) {
    override fun toString() = "AiProviderProfile(id=$id, name=$name, protocol=$protocol, models=$models, key=[redacted])"
    fun normalized() = copy(name = name.trim(), endpoint = endpoint.trim().trimEnd('/'), key = key.trim(),
        models = models.map { it.copy(id = it.id.trim()) })
    fun validate() {
        require(id.isNotBlank() && id.length <= 100) { "供应商标识无效" }
        require(name.isNotBlank() && name.length <= 60 && name.none { it.isISOControl() }) { "供应商名称须为 1–60 个字符" }
        require(aiHttpsUrl(endpoint, allowQuery = false)) { "请求地址须为完整 HTTPS 接口，不能包含账号、查询参数或占位符" }
        require(key.length <= 8192 && key.none { it.isWhitespace() || it.isISOControl() }) { "请填写有效的 API Key" }
        models.forEach { aiValidateModelId(it.id) }
        require(models.map { it.id }.distinct().size == models.size) { "同一供应商不能添加重复的模型 ID" }
    }
}

internal fun aiValidateModelId(id: String) {
    require(id.isNotBlank() && id.length <= 200 && id.none { it.isWhitespace() || it.isISOControl() }) { "请填写有效的模型 ID" }
}

internal fun aiProviderFieldErrors(profile: AiProviderProfile): Map<String, String> = buildMap {
    val clean = profile.normalized()
    if (clean.name.isBlank() || clean.name.length > 60 || clean.name.any { it.isISOControl() })
        put("name", "供应商名称须为 1–60 个字符")
    if (!aiHttpsUrl(clean.endpoint, allowQuery = false))
        put("endpoint", "请填写完整 HTTPS 接口，不能包含账号、查询参数或占位符")
    if (clean.key.length > 8192 || clean.key.any { it.isWhitespace() || it.isISOControl() })
        put("key", "API Key 不能包含空格或换行")
}

internal data class AiProviderCatalog(
    val profiles: List<AiProviderProfile>,
    val activeProfileId: String = "",
    val activeModelId: String = "",
) {
    fun choices(): List<Pair<AiProviderProfile, AiProviderModel>> = profiles.filter { it.enabled }
        .flatMap { profile -> profile.models.filter { it.enabled }.map { profile to it } }

    fun reconcile(): AiProviderCatalog {
        val choices = choices()
        val selected = choices.firstOrNull { (profile, model) -> profile.id == activeProfileId && model.id == activeModelId }
            ?: choices.firstOrNull()
        return copy(activeProfileId = selected?.first?.id.orEmpty(), activeModelId = selected?.second?.id.orEmpty())
    }

    fun select(profileId: String, modelId: String): AiProviderCatalog {
        require(choices().any { (profile, model) -> profile.id == profileId && model.id == modelId }) { "该供应商或模型已停用，请重新选择" }
        return copy(activeProfileId = profileId, activeModelId = modelId)
    }

    fun activeConfig(): AiImageConfig {
        val selected = reconcile()
        val profile = profiles.firstOrNull { it.id == selected.activeProfileId }
            ?: return AiImageConfig(model = "", profileName = "未选择模型")
        return AiImageConfig(profile.protocol, profile.endpoint, selected.activeModelId, profile.key, profile.id, profile.name)
    }

    fun upsert(profile: AiProviderProfile): AiProviderCatalog {
        val clean = profile.normalized().also { it.validate() }
        val rows = if (profiles.any { it.id == clean.id }) profiles.map { if (it.id == clean.id) clean else it }
            else profiles + clean
        return copy(profiles = rows).reconcile()
    }

    fun remove(profileId: String) = copy(profiles = profiles.filterNot { it.id == profileId }).reconcile()
}

internal fun aiDefaultProfiles() = AiImageProvider.entries.map {
    AiProviderProfile("builtin-${it.name.lowercase()}", it.title, it)
}

internal fun aiMigrateProfiles(configs: List<AiImageConfig>, active: AiImageProvider): AiProviderCatalog {
    val profiles = configs.map { config ->
        AiProviderProfile("builtin-${config.provider.name.lowercase()}", config.provider.title, config.provider,
            config.endpoint, config.key, models = listOf(AiProviderModel(config.model)))
    }
    return AiProviderCatalog(profiles, "builtin-${active.name.lowercase()}",
        configs.firstOrNull { it.provider == active }?.model.orEmpty()).reconcile()
}

internal object AiProviderCatalogCodec {
    fun encode(catalog: AiProviderCatalog, encrypt: (String) -> String): String {
        val profiles = JSONArray()
        catalog.profiles.forEach { profile ->
            val models = JSONArray()
            profile.models.forEach { models.put(JSONObject().put("id", it.id).put("enabled", it.enabled)) }
            profiles.put(JSONObject().put("id", profile.id).put("name", profile.name).put("protocol", profile.protocol.name)
                .put("endpoint", profile.endpoint).put("key", if (profile.key.isBlank()) "" else encrypt(profile.key))
                .put("enabled", profile.enabled).put("models", models))
        }
        return JSONObject().put("version", 2).put("profiles", profiles)
            .put("activeProfileId", catalog.activeProfileId).put("activeModelId", catalog.activeModelId).toString()
    }

    fun decode(value: String, decrypt: (String) -> String): AiProviderCatalog {
        val json = JSONObject(value)
        require(json.getInt("version") == 2)
        val rows = json.getJSONArray("profiles")
        val profiles = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val models = row.getJSONArray("models")
            val encoded = row.optString("key")
            AiProviderProfile(row.getString("id"), row.getString("name"), AiImageProvider.valueOf(row.getString("protocol")),
                row.getString("endpoint"), if (encoded.isBlank()) "" else runCatching { decrypt(encoded) }.getOrDefault(""),
                row.optBoolean("enabled", true), (0 until models.length()).map { modelIndex ->
                    models.getJSONObject(modelIndex).let { AiProviderModel(it.getString("id"), it.optBoolean("enabled", true)) }
                }).normalized().also { it.validate() }
        }
        require(profiles.map { it.id }.distinct().size == profiles.size)
        return AiProviderCatalog(profiles, json.optString("activeProfileId"), json.optString("activeModelId")).reconcile()
    }
}

internal fun aiHttpsUrl(value: String, allowQuery: Boolean = true): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawFragment == null && (allowQuery || uri.rawQuery == null) &&
        !value.contains('{') && !value.contains('}')
}.getOrDefault(false)

internal enum class AiPhotoStyle(val title: String, val assetName: String, val repository: String) {
    WATERCOLOR("水彩旅行卡片", "watercolor-travel-card", "GZ-L/photo-to-poster-skills"),
    IMPASTO("厚涂微缩景观", "impasto-miniature-landscape", "GZ-L/photo-to-poster-skills"),
    PIXEL("像素消融风景", "pixel-dissolve-landscape", "GZ-L/photo-to-poster-skills"),
    PEN_WASH("观察式钢笔淡彩", "observational-pen-wash", "GZ-L/photo-to-poster-skills"),
    PAPERCRAFT("纸艺旅行微缩场景", "papercraft-travel-diorama", "GZ-L/photo-to-poster-skills"),
    GATHERED_ZINE("实景拼贴纸刊", "scenes-gathered-zine-v1-3", "Zeejay0/gathered-scenes-zine-skill"),
    ZINE_POSTCARD("Zine 明信片", "photo-to-zine-postcard", "Whiplashzeb/photo-to-zine-postcard"),
    MEMORY_ARCHIVE("照片记忆档案", "compose-photo-memory-archive", "sbj61188-lab/compose-photo-memory-archive"),
}

internal const val AI_PROMPT_MAX_LENGTH = 60000

internal fun aiPhotoPrompt(prompt: String, title: String, width: Int, height: Int): String {
    require(prompt.isNotBlank() && prompt.length <= AI_PROMPT_MAX_LENGTH) { "提示词须为 1–60000 个字符" }
    require(title.length <= 80 && title.none { it.isISOControl() }) { "标题最多 80 个字符" }
    require(width > 0 && height > 0)
    val caption = if (title.isBlank()) "不要添加标题、邮戳文字或虚构地点名称。" else "卡片标题使用用户提供的文字：${title.trim()}。不要添加其他虚构文字。"
    return "$prompt\n$caption\n以参考照片为依据，保留主要主体，画面宽高比保持为 $width:$height。只生成一张图片。"
}

internal sealed interface AiImageReference {
    data class Remote(val url: String) : AiImageReference
    data class Encoded(val base64: String) : AiImageReference
}

internal object AiImageProtocol {
    fun request(config: AiImageConfig, image: String, prompt: String, styleReference: String? = null): JSONObject = when (config.provider) {
        AiImageProvider.DOUBAO -> JSONObject().put("model", config.model)
            .put("image", if (styleReference == null) image else JSONArray().put(image).put(styleReference))
            .put("prompt", prompt).put("size", "2K").put("response_format", "url")
            .put("stream", false).put("watermark", true)
        AiImageProvider.QWEN -> JSONObject().put("model", config.model)
            .put("input", JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("image", image)).apply {
                    styleReference?.let { put(JSONObject().put("image", it)) }
                }.put(JSONObject().put("text", prompt))))))
            .put("parameters", JSONObject().put("n", 1).put("prompt_extend", false).put("watermark", true))
    }

    fun response(provider: AiImageProvider, json: JSONObject): AiImageReference {
        val error = json.optJSONObject("error")
        if (error != null || json.optString("code").isNotBlank()) {
            throw AiImageException(aiImageError(400, error?.optString("code") ?: json.optString("code")))
        }
        val item = when (provider) {
            AiImageProvider.DOUBAO -> json.optJSONArray("data")?.optJSONObject(0)
            AiImageProvider.QWEN -> json.optJSONObject("output")?.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optJSONArray("content")?.let { content ->
                    (0 until content.length()).mapNotNull { content.optJSONObject(it) }
                        .firstOrNull { it.optString("image").isNotBlank() }
                }
        } ?: throw AiImageException("服务商未返回图片，请检查模型是否支持图像编辑")
        item.optJSONObject("error")?.let { throw AiImageException(aiImageError(400, it.optString("code"))) }
        val url = item.optString(if (provider == AiImageProvider.DOUBAO) "url" else "image")
        if (url.isNotBlank()) {
            if (!aiHttpsUrl(url)) throw AiImageException("服务商返回的图片地址不是有效 HTTPS 链接")
            return AiImageReference.Remote(url)
        }
        val base64 = item.optString("b64_json")
        if (base64.isNotBlank()) return AiImageReference.Encoded(base64)
        throw AiImageException("服务商未返回可读取的图片")
    }
}

internal class AiImageException(message: String) : Exception(message)

internal fun aiImageSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "返回内容不是可解码的图片" }
    var sample = 1
    while (width / sample > 4096 || height / sample > 4096 ||
        width.toLong() * height / sample / sample > 16L * 1024 * 1024) sample *= 2
    return sample
}

internal fun aiImageError(status: Int, code: String = ""): String {
    val lower = code.lowercase()
    return when {
        status == 401 || "apikey" in lower || "authentication" in lower -> "API Key 无效，请检查密钥与所属地域"
        status == 402 || "quota" in lower || "balance" in lower || "arrear" in lower -> "服务商余额或额度不足，请检查控制台"
        status == 403 || "accessdenied" in lower -> "调用被拒绝，请检查模型开通状态、密钥权限与地域"
        status == 429 || "limit" in lower || "throttl" in lower -> "调用频率受限，请稍后手动重试"
        "content" in lower || "safety" in lower || "sensitive" in lower -> "照片或提示词未通过服务商审核"
        "model" in lower || status == 404 -> "模型或请求地址不可用，请检查完整接口地址和模型 ID"
        status in 300..399 -> "接口发生跳转，请填写服务商的最终 HTTPS 请求地址"
        status >= 500 -> "服务商暂不可用，请稍后重试"
        else -> "图片请求失败（HTTP $status），请检查模型、参数和调用权限"
    }
}
