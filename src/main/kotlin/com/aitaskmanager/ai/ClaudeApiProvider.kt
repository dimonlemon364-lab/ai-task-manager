package com.aitaskmanager.ai

import com.aitaskmanager.settings.CredentialStore
import com.aitaskmanager.settings.ProviderConfig
import com.aitaskmanager.task.AiTask
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ClaudeApiProvider(private val config: ProviderConfig) : AiProvider {

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    }

    override fun searchFiles(prompt: String, onLog: (String) -> Unit, isCancelled: () -> Boolean): List<String> {
        val text = call(prompt, onLog, isCancelled) ?: return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('.') }
            .distinct()
            .toList()
    }

    override fun executeTask(task: AiTask, onLog: (String) -> Unit, isCancelled: () -> Boolean): TaskResult {
        val fullPrompt = "File: ${task.file}\n\n${task.prompt}"
        val text = call(fullPrompt, onLog, isCancelled)
        return if (text != null) TaskResult(true, text) else TaskResult(false, "", "API call failed")
    }

    override fun test(onLog: (String) -> Unit): TestResult {
        val token = CredentialStore.getSecret(config.credentialKey)
        if (token.isNullOrBlank()) {
            return TestResult(false, "No API token set. Paste it into the Token field on the Configuration tab and save.")
        }
        return try {
            val text = call("Reply with the single word PONG.", onLog) { false }
            if (text != null && text.contains("PONG", ignoreCase = true)) {
                TestResult(true, "OK. Authenticated against ${config.apiUrl} (model: ${config.model}).")
            } else {
                TestResult(false, "Got unexpected response:\n${text ?: "<empty>"}")
            }
        } catch (e: Throwable) {
            TestResult(false, "Request failed: ${e.message}")
        }
    }

    private fun call(prompt: String, onLog: (String) -> Unit, isCancelled: () -> Boolean): String? {
        if (isCancelled()) return null
        val token = CredentialStore.getSecret(config.credentialKey)
            ?: return null.also { onLog("[claude-api] no token configured") }

        val body = buildRequestJson(config.model, prompt)
        onLog("[claude-api] POST ${config.apiUrl} model=${config.model}")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(config.apiUrl))
            .timeout(Duration.ofSeconds(120))
            .header("x-api-key", token)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            onLog("[claude-api] HTTP ${response.statusCode()}: ${response.body().take(500)}")
            return null
        }
        return extractText(response.body())
    }

    private fun buildRequestJson(model: String, prompt: String): String {
        val safePrompt = jsonEscape(prompt)
        val safeModel = jsonEscape(model)
        return """{"model":"$safeModel","max_tokens":4096,"messages":[{"role":"user","content":"$safePrompt"}]}"""
    }

    /**
     * Minimal extraction of `content[0].text` from Anthropic messages API JSON. Uses a tolerant
     * regex rather than pulling in a JSON parser. If the shape changes upstream, we fall back to
     * returning the full body so the user can see what came back.
     */
    private fun extractText(json: String): String {
        val m = TEXT_PATTERN.find(json) ?: return json
        return unescapeJsonString(m.groupValues[1])
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescapeJsonString(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < s.length) {
                            sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    companion object {
        private val TEXT_PATTERN =
            Regex("\"type\"\\s*:\\s*\"text\"\\s*,\\s*\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    }
}
