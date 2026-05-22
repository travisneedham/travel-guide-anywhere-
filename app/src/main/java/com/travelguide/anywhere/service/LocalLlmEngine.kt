package com.travelguide.anywhere.service

import android.content.Context
import android.util.Log
import com.travelguide.anywhere.data.remote.dto.ClaudeMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.suspendCancellableCoroutine
import org.nehuatl.llamacpp.LlamaHelper
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LocalLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: LocalLlmModelManager,
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<LlamaHelper.LLMEvent>(extraBufferCapacity = 128)
    private val helper = LlamaHelper(context.contentResolver, engineScope, _events)

    @Volatile private var loadedModel: LocalLlmModelManager.LocalModel? = null

    private val generationMutex = Mutex()

    suspend fun ensureLoaded(model: LocalLlmModelManager.LocalModel) = withContext(Dispatchers.IO) {
        if (loadedModel == model) return@withContext
        val file = modelManager.modelFile(model)
        if (!file.exists()) throw Exception("Model not downloaded: ${model.displayName}")
        val uri = android.net.Uri.fromFile(file).toString()
        suspendCancellableCoroutine<Unit> { cont ->
            helper.load(uri, 4096) { _ ->
                loadedModel = model
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    suspend fun generate(
        model: LocalLlmModelManager.LocalModel,
        systemPrompt: String,
        messages: List<ClaudeMessage>,
        userMessage: String,
    ): String = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            ensureLoaded(model)
            val prompt = buildPrompt(model.chatTemplate, systemPrompt, messages, userMessage)
            Log.d(TAG, "generate: model=${model.displayName}, prompt length=${prompt.length}")
            val resultChannel = Channel<Result<String>>(1)
            val job = engineScope.launch {
                _events.collect { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Done -> resultChannel.trySend(Result.success(event.fullText.trim()))
                        is LlamaHelper.LLMEvent.Error -> resultChannel.trySend(Result.failure(Exception(event.message)))
                        else -> {}
                    }
                }
            }
            yield() // let collector start
            helper.predict(prompt)
            val result = try {
                resultChannel.receive()
            } finally {
                job.cancel()
            }
            result.getOrThrow()
        }
    }

    private fun buildPrompt(
        template: LocalLlmModelManager.ChatTemplate,
        systemPrompt: String,
        messages: List<ClaudeMessage>,
        userMessage: String,
    ): String {
        val history = messages.takeLast(6)
        return when (template) {
            LocalLlmModelManager.ChatTemplate.PHI -> buildString {
                append("<|system|>\n$systemPrompt<|end|>\n")
                for (msg in history) {
                    if (msg.role == "user") {
                        append("<|user|>\n${msg.content}<|end|>\n")
                    } else {
                        append("<|assistant|>\n${msg.content}<|end|>\n")
                    }
                }
                append("<|user|>\n$userMessage<|end|>\n<|assistant|>\n")
            }
            LocalLlmModelManager.ChatTemplate.CHATML -> buildString {
                append("<|im_start|>system\n$systemPrompt<|im_end|>\n")
                for (msg in history) {
                    if (msg.role == "user") {
                        append("<|im_start|>user\n${msg.content}<|im_end|>\n")
                    } else {
                        append("<|im_start|>assistant\n${msg.content}<|im_end|>\n")
                    }
                }
                append("<|im_start|>user\n$userMessage<|im_end|>\n<|im_start|>assistant\n")
            }
        }
    }

    companion object {
        private const val TAG = "LocalLlmEngine"
    }
}
