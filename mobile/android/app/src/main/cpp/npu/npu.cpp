// Runs Nirovi and Syvenu on the phone's NPU: MediaTek through the Neuron adapter, Snapdragon
// through the QNN add-on's runner. Rust calls `kVtable`; Kotlin opens and detects through JNI.
#include "neuron.h"
#include "util.h"
#include <android/log.h>
#include <cmath>
#include <dlfcn.h>
#include <jni.h>
#include <memory>
#include <stdexcept>
#include <string>

namespace {

constexpr size_t kFrames = 1500, kBins = 128;
// Model 0 is Nirovi (beat, downbeat interleaved per frame), 1 is Syvenu.
constexpr size_t kOutputs[] = {kFrames * 2, kFrames};

thread_local std::string lastError;

struct QnnApi {
    void* (*open)(const char*, const char*, const char*, char*, size_t);
    int (*run)(void*, const float*, size_t, float*, size_t, char*, size_t);
    void (*close)(void*);
};

struct Backend {
    virtual ~Backend() = default;
    virtual void run(int model, const float* in, float* out) = 0;
};

struct NeuronBackend : Backend {
    std::unique_ptr<npu::NeuronModelRunner> models[2];
    void run(int model, const float* in, float* out) override { models[model]->run(in, out); }
};

struct QnnBackend : Backend {
    void* lib = nullptr;
    QnnApi api{};
    void* models[2] = {};
    ~QnnBackend() override {
        for (void* m : models) if (m) api.close(m);
        // The runner keeps ONNX Runtime and the DSP session loaded; unloading them is unsafe.
    }
    void run(int model, const float* in, float* out) override {
        char err[256] = {};
        if (api.run(models[model], in, kFrames * kBins, out, kOutputs[model], err, sizeof err) != 0)
            throw std::runtime_error(err);
    }
};

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = c;
    env->ReleaseStringUTFChars(s, c);
    return r;
}

std::string escape(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        if (static_cast<unsigned char>(c) >= 32) o += c;
    }
    return o;
}

void checkSizes(npu::NeuronModelRunner& m, int model) {
    if (m.inputSize() != kFrames * kBins || m.outputSize() != kOutputs[model]) throw std::runtime_error("NPU graph has unexpected IO");
}

int vtableRun(void* handle, int32_t model, const float* in, float* out) {
    try {
        if (model < 0 || model > 1) return -1;
        static_cast<Backend*>(handle)->run(model, in, out);
        for (size_t i = 0; i < kOutputs[model]; i++) if (!std::isfinite(out[i])) return -2;
        return 0;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_WARN, "OrchardNpu", "run: %s", e.what());
        return -3;
    }
}

void vtableClose(void* handle) { delete static_cast<Backend*>(handle); }

}  // namespace

extern "C" {

// Read by orchard-adaptive-mix's `npu::Vtable`; keep the layout in step.
struct OrchardNpuVtable {
    int (*run)(void*, int32_t, const float*, float*);
    void (*close)(void*);
};
static const OrchardNpuVtable kVtable{vtableRun, vtableClose};

JNIEXPORT jlong JNICALL Java_dev_sfg_orchard_mobile_playback_smart_npu_NpuNative_nativeVtable(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(&kVtable);
}

JNIEXPORT jstring JNICALL Java_dev_sfg_orchard_mobile_playback_smart_npu_NpuNative_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(lastError.c_str());
}

// {"detected":bool,"library","version","devices":[...],"supported":bool,"reason"}
JNIEXPORT jstring JNICALL Java_dev_sfg_orchard_mobile_playback_smart_npu_NpuNative_nativeDetectNeuron(
    JNIEnv* env, jclass, jstring jbeat, jstring jvocal) {
    std::string out;
    std::string error;
    auto rt = npu::openNeuron(error);
    if (!rt) {
        out = "{\"detected\":false,\"supported\":false,\"reason\":\"" + escape(error) + "\"}";
        return env->NewStringUTF(out.c_str());
    }
    std::string devices;
    for (auto& d : rt->accelerators) devices += (devices.empty() ? "\"" : ",\"") + escape(d.name) + "\"";
    bool supported = true;
    std::string reason = "Every op runs on the NPU";
    try {
        for (const std::string& graph : {str(env, jbeat), str(env, jvocal)}) {
            npu::NeuronModelRunner m(rt, graph);
            if (m.fullSupport().empty()) {
                supported = false;
                reason = "This NPU does not run every op of " + graph.substr(graph.rfind('/') + 1);
            }
        }
    } catch (const std::exception& e) {
        supported = false;
        reason = e.what();
    }
    out = "{\"detected\":true,\"library\":\"" + escape(rt->library) + "\",\"version\":\"" + rt->version +
        "\",\"devices\":[" + devices + "],\"supported\":" + (supported ? "true" : "false") +
        ",\"reason\":\"" + escape(reason) + "\"}";
    return env->NewStringUTF(out.c_str());
}

// Compiles on a cache miss, which takes seconds; callers show the one-time compiling notice.
JNIEXPORT jlong JNICALL Java_dev_sfg_orchard_mobile_playback_smart_npu_NpuNative_nativeOpenNeuron(
    JNIEnv* env, jclass, jstring jbeat, jstring jvocal, jstring jcache) {
    try {
        std::string error;
        auto rt = npu::openNeuron(error);
        if (!rt) throw std::runtime_error(error);
        auto backend = std::make_unique<NeuronBackend>();
        std::string cache = str(env, jcache);
        const std::string graphs[] = {str(env, jbeat), str(env, jvocal)};
        const char* names[] = {"/nirovi.neuron", "/syvenu.neuron"};
        for (int i = 0; i < 2; i++) {
            backend->models[i] = std::make_unique<npu::NeuronModelRunner>(rt, graphs[i]);
            checkSizes(*backend->models[i], i);
            backend->models[i]->prepare(cache + names[i]);
        }
        return reinterpret_cast<jlong>(backend.release());
    } catch (const std::exception& e) {
        lastError = e.what();
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_dev_sfg_orchard_mobile_playback_smart_npu_NpuNative_nativeOpenQnn(
    JNIEnv* env, jclass, jstring jlibDir, jstring jbeat, jstring jvocal, jstring jcache) {
    try {
        std::string libDir = str(env, jlibDir), cache = str(env, jcache);
        auto backend = std::make_unique<QnnBackend>();
        // By path: the add-on's runner and ONNX Runtime stay apart from the app's minimal copy.
        backend->lib = dlopen((libDir + "/liborchard_qnn.so").c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!backend->lib) throw std::runtime_error(std::string("Add-on runner: ") + dlerror());
        backend->api.open = reinterpret_cast<decltype(backend->api.open)>(dlsym(backend->lib, "orchard_qnn_open"));
        backend->api.run = reinterpret_cast<decltype(backend->api.run)>(dlsym(backend->lib, "orchard_qnn_run"));
        backend->api.close = reinterpret_cast<decltype(backend->api.close)>(dlsym(backend->lib, "orchard_qnn_close"));
        if (!backend->api.open || !backend->api.run || !backend->api.close) throw std::runtime_error("Add-on runner is incomplete");
        const std::string models[] = {str(env, jbeat), str(env, jvocal)};
        const char* names[] = {"/nirovi", "/syvenu"};
        for (int i = 0; i < 2; i++) {
            char err[512] = {};
            backend->models[i] = backend->api.open(libDir.c_str(), models[i].c_str(), (cache + names[i]).c_str(), err, sizeof err);
            if (!backend->models[i]) throw std::runtime_error(err);
        }
        return reinterpret_cast<jlong>(backend.release());
    } catch (const std::exception& e) {
        lastError = e.what();
        return 0;
    }
}

// Runs silence through both models; "" on success.
JNIEXPORT jstring JNICALL Java_dev_sfg_orchard_mobile_playback_smart_npu_NpuNative_nativeSelfTest(JNIEnv* env, jclass, jlong handle) {
    std::vector<float> in(kFrames * kBins, 0.0f), out(kOutputs[0]);
    for (int model = 0; model < 2; model++) {
        int code = vtableRun(reinterpret_cast<void*>(handle), model, in.data(), out.data());
        if (code != 0) return env->NewStringUTF(code == -2 ? "NPU returned non-finite output" : "NPU run failed");
    }
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL Java_dev_sfg_orchard_mobile_playback_smart_npu_NpuNative_nativeClose(JNIEnv*, jclass, jlong handle) {
    vtableClose(reinterpret_cast<void*>(handle));
}

}  // extern "C"
