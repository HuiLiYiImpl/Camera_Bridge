package com.yaoyihan.nikonconnect

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiImageProtocolTest {
    private fun config(provider: AiImageProvider) = AiImageConfig(provider, key = "test-secret")

    @Test fun doubaoUsesSampleParametersAndRequiredReferenceImage() {
        val json = AiImageProtocol.request(config(AiImageProvider.DOUBAO), "data:image/jpeg;base64,abc", "paint")
        assertEquals("data:image/jpeg;base64,abc", json.getString("image"))
        assertEquals("paint", json.getString("prompt"))
        assertEquals("doubao-seedream-5-0-pro-260628", json.getString("model"))
        assertEquals(setOf("model", "prompt", "image", "response_format", "size", "stream", "watermark"), json.keys().asSequence().toSet())
        assertEquals("url", json.getString("response_format"))
        assertEquals("2K", json.getString("size"))
        assertTrue(json.getBoolean("watermark"))
        assertFalse(json.getBoolean("stream"))
        assertFalse(json.toString().contains("test-secret"))
    }

    @Test fun qwenUsesNativeImageEditProtocolAndOneOutput() {
        val json = AiImageProtocol.request(config(AiImageProvider.QWEN), "data:image/jpeg;base64,abc", "paint")
        val message = json.getJSONObject("input").getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("data:image/jpeg;base64,abc", message.getJSONArray("content").getJSONObject(0).getString("image"))
        assertEquals("paint", message.getJSONArray("content").getJSONObject(1).getString("text"))
        assertEquals(1, json.getJSONObject("parameters").getInt("n"))
        assertFalse(json.getJSONObject("parameters").getBoolean("prompt_extend"))
        assertFalse(json.toString().contains("test-secret"))
    }

    @Test fun decodesBothProviderResponsesAndBase64Fallback() {
        assertEquals(AiImageReference.Remote("https://example.com/a.png?Expires=123"), AiImageProtocol.response(AiImageProvider.DOUBAO,
            JSONObject("""{"data":[{"url":"https://example.com/a.png?Expires=123"}]}""")))
        assertEquals(AiImageReference.Encoded("abcd"), AiImageProtocol.response(AiImageProvider.DOUBAO,
            JSONObject("""{"data":[{"b64_json":"abcd"}]}""")))
        assertEquals(AiImageReference.Remote("https://example.com/b.png"), AiImageProtocol.response(AiImageProvider.QWEN,
            JSONObject("""{"output":{"choices":[{"message":{"content":[{"text":"done"},{"image":"https://example.com/b.png"}]}}]}}""")))
    }

    @Test fun rejectsMissingImagesAndUnsafeDownloadUrls() {
        for (json in listOf("""{"data":[]}""", """{"data":[{"url":"http://example.com/a.png"}]}""",
            """{"data":[{"url":"https://user:secret@example.com/a.png"}]}""")) {
            assertThrows(AiImageException::class.java) { AiImageProtocol.response(AiImageProvider.DOUBAO, JSONObject(json)) }
        }
    }

    @Test fun providerErrorsNeverEchoSecretsOrRawServerMessages() {
        val error = assertThrows(AiImageException::class.java) { AiImageProtocol.response(AiImageProvider.QWEN,
            JSONObject("""{"code":"InvalidApiKey","message":"test-secret"}""")) }
        assertTrue(error.message!!.contains("API Key"))
        assertFalse(error.message!!.contains("test-secret"))
        assertFalse(aiImageError(400, "test-secret").contains("test-secret"))
        assertTrue(aiImageError(429).contains("频率"))
        assertTrue(aiImageError(403).contains("权限"))
    }

    @Test fun settingsRequireHttpsAndDoNotLeakKeyInDebugString() {
        config(AiImageProvider.DOUBAO).validate()
        config(AiImageProvider.QWEN).copy(endpoint = "https://workspace.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation").validate()
        for (url in listOf("http://example.com/api", "https://user:pass@example.com/api", "https://example.com/api?key=abc", "https://{WorkspaceId}.example.com/api")) {
            assertThrows(IllegalArgumentException::class.java) { config(AiImageProvider.QWEN).copy(endpoint = url).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { config(AiImageProvider.DOUBAO).copy(key = "secret\r\nheader").validate() }
        assertFalse(config(AiImageProvider.DOUBAO).toString().contains("test-secret"))
    }

    @Test fun titlesAndAspectRatioComeFromUserAndSource() {
        val noTitle = aiPhotoPrompt("watercolor", "", 1200, 800)
        assertTrue(noTitle.contains("1200:800"))
        assertTrue(noTitle.contains("不要添加标题"))
        assertTrue(aiPhotoPrompt("watercolor", "MY TRIP", 800, 1200).contains("MY TRIP"))
        assertThrows(IllegalArgumentException::class.java) { aiPhotoPrompt("", "", 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { aiPhotoPrompt("paint", "x".repeat(81), 1, 1) }
        assertEquals(9, AiPhotoStyle.entries.size)
    }

    @Test fun decodingStartsAtOneAndBoundsLargeImageMemory() {
        assertEquals(1, aiImageSampleSize(2048, 2048))
        assertEquals(1, aiImageSampleSize(4096, 4096))
        assertEquals(2, aiImageSampleSize(8192, 4096))
        assertEquals(8, aiImageSampleSize(24000, 16000))
        assertThrows(IllegalArgumentException::class.java) { aiImageSampleSize(0, -1) }
    }
}
