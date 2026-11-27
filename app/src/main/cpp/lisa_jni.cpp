#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "Lisa"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool> stop{false};
    std::mutex lock;
    int n_ctx = 0;
};

Session * session(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

std::string toString(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJString(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

void fail(JNIEnv * env, const char * className, const char * message) {
    jclass clazz = env->FindClass(className);
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
        env->DeleteLocalRef(clazz);
    }
}

std::vector<llama_token> encode(const llama_vocab * vocab, const std::string & text) {
    int32_t count = llama_tokenize(
        vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, false, true);
    if (count < 0) count = -count;
    if (count <= 0) return {};

    std::vector<llama_token> tokens(static_cast<size_t>(count));
    const int32_t written = llama_tokenize(
        vocab, text.c_str(), static_cast<int32_t>(text.size()),
        tokens.data(), count, false, true);
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string decodePiece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int32_t written = llama_token_to_piece(
        vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    if (written < 0) {
        buffer.resize(static_cast<size_t>(-written));
        written = llama_token_to_piece(
            vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    }
    if (written <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(written));
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_lisaai_inference_LlamaNative_systemInfo(JNIEnv * env, jobject /* this */) {
    const char * info = llama_print_system_info();
    return toJString(env, info != nullptr ? info : "llama.cpp");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lisaai_inference_LlamaNative_nativeLoad(
    JNIEnv * env, jobject /* this */, jstring path, jint nCtx, jint threads) {
    static std::once_flag backend_once;
    std::call_once(backend_once, []() { llama_backend_init(); });

    const std::string modelPath = toString(env, path);
    if (modelPath.empty()) {
        fail(env, "java/lang/IllegalArgumentException", "Model path is empty");
        return 0;
    }

    auto * s = new Session();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    modelParams.check_tensors = false;

    LOGI("Loading model: %s (n_ctx=%d, threads=%d)", modelPath.c_str(), nCtx, threads);
    s->model = llama_model_load_from_file(modelPath.c_str(), modelParams);
    if (s->model == nullptr) {
        delete s;
        fail(env, "java/lang/IllegalStateException",
             "Could not load the GGUF model. It may be corrupt, unsupported, or too large for memory.");
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(std::max(256, static_cast<int>(nCtx)));
    ctxParams.n_batch = 256;
    ctxParams.n_ubatch = 128;
    ctxParams.n_threads = std::max(1, static_cast<int>(threads));
    ctxParams.n_threads_batch = ctxParams.n_threads;
    ctxParams.abort_callback = [](void * data) -> bool {
        auto * s = static_cast<Session *>(data);
        return s != nullptr && s->stop.load();
    };
    ctxParams.abort_callback_data = s;

    s->ctx = llama_init_from_model(s->model, ctxParams);
    if (s->ctx == nullptr) {
        llama_model_free(s->model);
        delete s;
        fail(env, "java/lang/IllegalStateException",
             "Model loaded but the inference context could not be created. Try a smaller context.");
        return 0;
    }

    s->vocab = llama_model_get_vocab(s->model);
    s->n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    LOGI("Model ready: n_ctx=%d", s->n_ctx);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lisaai_inference_LlamaNative_nativeFree(
    JNIEnv * /* env */, jobject /* this */, jlong handle) {
    Session * s = session(handle);
    if (s == nullptr) return;
    s->stop.store(true);
    {
        std::lock_guard<std::mutex> guard(s->lock);
        if (s->ctx != nullptr) llama_free(s->ctx);
        if (s->model != nullptr) llama_model_free(s->model);
        s->ctx = nullptr;
        s->model = nullptr;
        s->vocab = nullptr;
    }
    delete s;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lisaai_inference_LlamaNative_nativeCancel(
    JNIEnv * /* env */, jobject /* this */, jlong handle) {
    Session * s = session(handle);
    if (s != nullptr) s->stop.store(true);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lisaai_inference_LlamaNative_nativeTokenCount(
    JNIEnv * env, jobject /* this */, jlong handle, jstring text) {
    Session * s = session(handle);
    if (s == nullptr || s->vocab == nullptr) return -1;
    const std::string value = toString(env, text);
    if (value.empty()) return 0;
    std::lock_guard<std::mutex> guard(s->lock);
    return static_cast<jint>(encode(s->vocab, value).size());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lisaai_inference_LlamaNative_nativeGenerate(
    JNIEnv * env, jobject /* this */, jlong handle, jstring prompt,
    jfloat temperature, jint topK, jfloat topP, jint maxTokens, jint seed, jobject callback) {
    Session * s = session(handle);
    if (s == nullptr || s->ctx == nullptr || s->vocab == nullptr) return -1;

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "([B)Z");
    env->DeleteLocalRef(callbackClass);
    if (onToken == nullptr) return -1;

    std::lock_guard<std::mutex> guard(s->lock);
    s->stop.store(false);

    auto tokens = encode(s->vocab, toString(env, prompt));
    if (tokens.empty()) {
        LOGE("Prompt produced no tokens");
        return -1;
    }
    if (static_cast<int>(tokens.size()) >= s->n_ctx - 4) {
        LOGE("Prompt too long: %zu tokens for context %d", tokens.size(), s->n_ctx);
        return -2;
    }

    int budget = std::max(1, static_cast<int>(maxTokens));
    budget = std::min(budget, s->n_ctx - static_cast<int>(tokens.size()) - 4);

    llama_memory_clear(llama_get_memory(s->ctx), true);

    const int batchSize = 256;
    for (size_t offset = 0; offset < tokens.size(); offset += batchSize) {
        if (s->stop.load()) return 2;
        const int count = static_cast<int>(std::min<size_t>(batchSize, tokens.size() - offset));
        if (llama_decode(s->ctx, llama_batch_get_one(tokens.data() + offset, count)) != 0) {
            LOGE("Prompt decode failed");
            return -1;
        }
    }

    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    samplerParams.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(samplerParams);

    llama_sampler_chain_add(
        sampler,
        llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, 1.1f, 0.0f, 0.0f));

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        if (topK > 0) llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
        if (topP > 0.0f && topP < 1.0f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
    }

    int reason = 1; // length limit
    for (int generated = 0; generated < budget; ++generated) {
        if (s->stop.load()) {
            reason = 2;
            break;
        }

        const llama_token id = llama_sampler_sample(sampler, s->ctx, -1);
        if (id < 0) {
            reason = -1;
            break;
        }
        llama_sampler_accept(sampler, id);

        if (llama_vocab_is_eog(s->vocab, id)) {
            reason = 0;
            break;
        }

        const std::string piece = decodePiece(s->vocab, id);
        if (!piece.empty()) {
            jbyteArray bytes = env->NewByteArray(static_cast<jsize>(piece.size()));
            if (bytes == nullptr) {
                reason = -1;
                break;
            }
            env->SetByteArrayRegion(
                bytes, 0, static_cast<jsize>(piece.size()),
                reinterpret_cast<const jbyte *>(piece.data()));
            const jboolean keepGoing = env->CallBooleanMethod(callback, onToken, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                reason = -1;
                break;
            }
            if (keepGoing == JNI_FALSE) {
                reason = 2;
                break;
            }
        }

        llama_token next = id;
        if (llama_decode(s->ctx, llama_batch_get_one(&next, 1)) != 0) {
            reason = -1;
            break;
        }
    }

    llama_sampler_free(sampler);
    return reason;
}
