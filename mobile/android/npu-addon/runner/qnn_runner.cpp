// liborchard_qnn.so: the add-on's C entry points over its own ONNX Runtime with the QNN EP.
// Orchard dlopens this by path; ONNX Runtime is opened by path too, so the app's minimal
// libonnxruntime.so (same soname) never satisfies it.
#include "onnxruntime_c_api.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <unistd.h>
#include <dirent.h>
#include <dlfcn.h>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace {

const OrtApi* ort = nullptr;
OrtEnv* env = nullptr;
std::mutex initLock;

void ck(OrtStatus* s) {
    if (!s) return;
    std::string m = ort->GetErrorMessage(s);
    ort->ReleaseStatus(s);
    throw std::runtime_error(m);
}

void init(const std::string& libDir) {
    std::lock_guard<std::mutex> guard(initLock);
    if (env) return;
    // The DSP loads the skel through this path; vendor directories come after the add-on's.
    std::string adsp = libDir + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
    void* h = dlopen((libDir + "/libonnxruntime.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) throw std::runtime_error(std::string("ONNX Runtime: ") + dlerror());
    auto base = reinterpret_cast<const OrtApiBase* (*)()>(dlsym(h, "OrtGetApiBase"));
    if (!base || !(ort = base()->GetApi(ORT_API_VERSION))) throw std::runtime_error("ONNX Runtime API mismatch");
    ck(ort->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "orchard-qnn", &env));
}

struct Model {
    OrtSession* session = nullptr;
    OrtMemoryInfo* memory = nullptr;
    std::string input;
    std::vector<std::string> outputs;
    std::mutex lock;
};

bool exists(const std::string& p) {
    struct stat st{};
    return stat(p.c_str(), &st) == 0;
}

void removeTree(const std::string& dir) {
    if (DIR* d = opendir(dir.c_str())) {
        while (dirent* e = readdir(d)) if (e->d_name[0] != '.') unlink((dir + "/" + e->d_name).c_str());
        closedir(d);
    }
    rmdir(dir.c_str());
}

OrtSession* open(const std::string& libDir, const std::string& model, const std::string& ctxDir, bool save) {
    OrtSessionOptions* o = nullptr;
    ck(ort->CreateSessionOptions(&o));
    std::unique_ptr<OrtSessionOptions, void (*)(OrtSessionOptions*)> opts(o, ort->ReleaseSessionOptions);
    ck(ort->SetIntraOpNumThreads(o, 1));
    ck(ort->AddSessionConfigEntry(o, "session.disable_cpu_ep_fallback", "1"));
    ck(ort->AddSessionConfigEntry(o, "session.intra_op.allow_spinning", "0"));
    if (save) {
        ck(ort->AddSessionConfigEntry(o, "ep.context_enable", "1"));
        ck(ort->AddSessionConfigEntry(o, "ep.context_file_path", (ctxDir + "/ctx.onnx").c_str()));
        // One file: the context binary rides inside the ONNX model.
        ck(ort->AddSessionConfigEntry(o, "ep.context_embed_mode", "1"));
    }
    std::string backend = libDir + "/libQnnHtp.so";
    const char* keys[] = {"backend_path", "htp_performance_mode", "htp_graph_finalization_optimization_mode"};
    const char* values[] = {backend.c_str(), "burst", "3"};
    ck(ort->SessionOptionsAppendExecutionProvider(o, "QNN", keys, values, 3));
    OrtSession* s = nullptr;
    ck(ort->CreateSession(env, model.c_str(), o, &s));
    return s;
}

void fail(const std::exception& e, char* err, size_t len) {
    if (err && len) snprintf(err, len, "%s", e.what());
}

}  // namespace

extern "C" {

// `cache` is a directory holding the compiled context; it is created on the first open.
__attribute__((visibility("default"))) void* orchard_qnn_open(const char* libDir, const char* model, const char* cache,
                                                              char* err, size_t len) {
    try {
        init(libDir);
        auto m = std::make_unique<Model>();
        std::string ctx = std::string(cache) + "/ctx.onnx";
        if (exists(ctx)) {
            try {
                m->session = open(libDir, ctx, cache, false);
            } catch (const std::exception&) {
                removeTree(cache);
            }
        }
        if (!m->session) {
            // Compile into a scratch directory, then rename: the context file names its bin.
            std::string tmp = std::string(cache) + ".tmp";
            removeTree(tmp);
            mkdir(tmp.c_str(), 0700);
            m->session = open(libDir, model, tmp, true);
            if (!exists(tmp + "/ctx.onnx") || rename(tmp.c_str(), cache) != 0) removeTree(tmp);
        }
        OrtAllocator* alloc = nullptr;
        ck(ort->GetAllocatorWithDefaultOptions(&alloc));
        char* name = nullptr;
        ck(ort->SessionGetInputName(m->session, 0, alloc, &name));
        m->input = name;
        alloc->Free(alloc, name);
        size_t count = 0;
        ck(ort->SessionGetOutputCount(m->session, &count));
        for (size_t i = 0; i < count; i++) {
            ck(ort->SessionGetOutputName(m->session, i, alloc, &name));
            m->outputs.emplace_back(name);
            alloc->Free(alloc, name);
        }
        ck(ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &m->memory));
        return m.release();
    } catch (const std::exception& e) {
        fail(e, err, len);
        return nullptr;
    }
}

// Input is [1, frames, 128]; outputs are interleaved per frame in session output order.
__attribute__((visibility("default"))) int orchard_qnn_run(void* handle, const float* in, size_t inCount, float* out,
                                                           size_t outCount, char* err, size_t len) {
    auto* m = static_cast<Model*>(handle);
    std::lock_guard<std::mutex> guard(m->lock);
    std::vector<OrtValue*> values(m->outputs.size(), nullptr);
    OrtValue* x = nullptr;
    try {
        int64_t shape[] = {1, int64_t(inCount / 128), 128};
        ck(ort->CreateTensorWithDataAsOrtValue(m->memory, const_cast<float*>(in), inCount * 4, shape, 3,
                                               ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &x));
        std::vector<const char*> names;
        for (auto& n : m->outputs) names.push_back(n.c_str());
        const char* input = m->input.c_str();
        ck(ort->Run(m->session, nullptr, &input, &x, 1, names.data(), names.size(), values.data()));
        size_t k = values.size(), frames = outCount / k;
        for (size_t j = 0; j < k; j++) {
            OrtTensorTypeAndShapeInfo* info = nullptr;
            size_t n = 0;
            ck(ort->GetTensorTypeAndShape(values[j], &info));
            OrtStatus* counted = ort->GetTensorShapeElementCount(info, &n);
            ort->ReleaseTensorTypeAndShapeInfo(info);
            ck(counted);
            if (n != frames) throw std::runtime_error("Unexpected NPU output size");
            float* y = nullptr;
            ck(ort->GetTensorMutableData(values[j], reinterpret_cast<void**>(&y)));
            for (size_t i = 0; i < frames; i++) out[i * k + j] = y[i];
        }
    } catch (const std::exception& e) {
        fail(e, err, len);
        for (auto* v : values) if (v) ort->ReleaseValue(v);
        if (x) ort->ReleaseValue(x);
        return -1;
    }
    for (auto* v : values) ort->ReleaseValue(v);
    ort->ReleaseValue(x);
    return 0;
}

__attribute__((visibility("default"))) void orchard_qnn_close(void* handle) {
    auto* m = static_cast<Model*>(handle);
    if (m->session) ort->ReleaseSession(m->session);
    if (m->memory) ort->ReleaseMemoryInfo(m->memory);
    delete m;
}

}  // extern "C"
