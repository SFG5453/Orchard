#include "neuron.h"
#include "util.h"
#include <algorithm>
#include <cstring>
#include <dlfcn.h>
#include <stdexcept>

namespace npu {
namespace {

const char* kLibraries[] = {"libneuronusdk_adapter.mtk.so", "libneuronusdk_adapter.9.mtk.so",
    "libneuronusdk_adapter.10.mtk.so", "libneuronusdk_adapter.so", "libneuron_adapter_mgvi.so",
    "libneuron_adapter.so"};

void ck(int code, const char* phase) {
    if (code != kNeuronNoError) throw std::runtime_error(std::string(phase) + " failed (Neuron " + std::to_string(code) + ")");
}

bool isAccelerator(std::string label) {
    std::transform(label.begin(), label.end(), label.begin(), [](unsigned char c) { return std::tolower(c); });
    bool hit = false;
    for (const char* k : {"mdla", "vpu", "npu", "apu"}) hit |= label.find(k) != std::string::npos;
    for (const char* k : {"cpu", "gpu", "dsp", "reference", "cmodel", "simulat", "emulat"})
        if (label.find(k) != std::string::npos) return false;
    return hit;
}

bool load(void* h, NeuronApi& api) {
#define NEURON_LOAD(name, ...) api.name = reinterpret_cast<decltype(api.name)>(dlsym(h, #name)); if (!api.name) return false;
    NEURON_REQUIRED(NEURON_LOAD)
#undef NEURON_LOAD
#define NEURON_SOFT(name, ...) api.name = reinterpret_cast<decltype(api.name)>(dlsym(h, #name));
    NEURON_OPTIONAL(NEURON_SOFT)
#undef NEURON_SOFT
    return true;
}

// Little-endian reader over the .npug layout written by mobile/tools/npu/pack.py.
struct Reader {
    const uint8_t* p;
    const uint8_t* end;
    template <class T> T get() {
        if (size_t(end - p) < sizeof(T)) throw std::runtime_error("Truncated NPU graph");
        T v;
        std::memcpy(&v, p, sizeof(T));
        p += sizeof(T);
        return v;
    }
    std::vector<uint32_t> list(uint32_t n) {
        std::vector<uint32_t> v(n);
        for (auto& x : v) x = get<uint32_t>();
        return v;
    }
};

}  // namespace

std::shared_ptr<NeuronRuntime> openNeuron(std::string& error) {
    for (const char* name : kLibraries) {
        void* h = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (!h) continue;
        auto rt = std::make_shared<NeuronRuntime>();
        if (!load(h, rt->api)) {
            error = std::string(name) + " lacks required functions";
            dlclose(h);
            continue;
        }
        NeuronRuntimeVersion v{};
        uint32_t count = 0;
        if (rt->api.Neuron_getVersion(&v) != kNeuronNoError || rt->api.Neuron_getDeviceCount(&count) != kNeuronNoError || count > 64) {
            error = std::string(name) + " did not enumerate devices";
            dlclose(h);
            continue;
        }
        rt->version = std::to_string(v.major) + "." + std::to_string(v.minor) + "." + std::to_string(v.patch);
        for (uint32_t i = 0; i < count; i++) {
            NeuronDevice* d = nullptr;
            const char* n = nullptr;
            if (rt->api.Neuron_getDevice(i, &d) == kNeuronNoError && rt->api.NeuronDevice_getName(d, &n) == kNeuronNoError && n && isAccelerator(n))
                rt->accelerators.push_back({n, d});
        }
        if (rt->accelerators.empty()) {
            error = std::string(name) + " " + rt->version + " has no NPU device";
            dlclose(h);
            continue;
        }
        rt->handle = h;
        rt->library = name;
        return rt;
    }
    if (error.empty()) error = "No Neuron adapter";
    return nullptr;
}

NeuronModelRunner::NeuronModelRunner(std::shared_ptr<NeuronRuntime> runtime, const std::string& graph)
    : rt_(std::move(runtime)), file_(readFile(graph)) {
    build();
}

NeuronModelRunner::~NeuronModelRunner() {
    if (compilation_) rt_->api.NeuronCompilation_free(compilation_);
    if (model_) rt_->api.NeuronModel_free(model_);
}

// Values over 128 bytes are referenced in place, so `file_` must outlive the compilation.
void NeuronModelRunner::build() {
    auto& api = rt_->api;
    Reader r{file_.data(), file_.data() + file_.size()};
    if (r.get<uint32_t>() != 0x4755504eu || r.get<uint32_t>() != 1) throw std::runtime_error("Unknown NPU graph format");
    ck(api.NeuronModel_create(&model_), "Model create");
    struct Pending { int32_t index; uint8_t kind; int32_t scalar; uint64_t off, len; bool pc; uint32_t dim, count; uint64_t scales; };
    std::vector<Pending> pending;
    uint32_t operands = r.get<uint32_t>();
    for (uint32_t i = 0; i < operands; i++) {
        int32_t type = r.get<int32_t>();
        auto dims = r.list(r.get<uint32_t>());
        float scale = r.get<float>();
        int32_t zero = r.get<int32_t>();
        NeuronOperandType t{type, uint32_t(dims.size()), dims.empty() ? nullptr : dims.data(), scale, zero};
        ck(api.NeuronModel_addOperand(model_, &t), "Add operand");
        Pending p{int32_t(i), r.get<uint8_t>(), 0, 0, 0, false, 0, 0, 0};
        if (p.kind == 1 || p.kind == 2) p.scalar = r.get<int32_t>();
        if (p.kind == 3) { p.off = r.get<uint64_t>(); p.len = r.get<uint64_t>(); }
        if ((p.pc = r.get<uint8_t>())) { p.dim = r.get<uint32_t>(); p.count = r.get<uint32_t>(); p.scales = r.get<uint64_t>(); }
        pending.push_back(p);
    }
    uint32_t ops = r.get<uint32_t>();
    std::vector<std::vector<uint32_t>> opIo;
    std::vector<int32_t> opCodes;
    for (uint32_t i = 0; i < ops; i++) {
        opCodes.push_back(r.get<int32_t>());
        opIo.push_back(r.list(r.get<uint32_t>()));
        opIo.push_back(r.list(r.get<uint32_t>()));
    }
    auto inputs = r.list(r.get<uint32_t>());
    auto outputs = r.list(r.get<uint32_t>());
    for (Io* io : {&in_, &out_}) *io = {r.get<uint8_t>() != 0, r.get<float>(), r.get<int32_t>(), r.get<uint32_t>()};
    uint64_t blobSize = r.get<uint64_t>();
    blob_ = size_t(r.p - file_.data());
    if (blobSize != uint64_t(r.end - r.p)) throw std::runtime_error("NPU graph weights are truncated");
    const uint8_t* blob = file_.data() + blob_;
    for (auto& p : pending) {
        if (p.pc) {
            if (p.scales + uint64_t(p.count) * 4 > blobSize) throw std::runtime_error("Scales outside weights");
            NeuronSymmPerChannelQuantParams q{p.dim, p.count, reinterpret_cast<const float*>(blob + p.scales)};
            ck(api.NeuronModel_setOperandSymmPerChannelQuantParams(model_, p.index, &q), "Per-channel scales");
        }
        if (p.kind == 2) {
            uint8_t v = uint8_t(p.scalar);
            ck(api.NeuronModel_setOperandValue(model_, p.index, &v, 1), "Bool value");
        } else if (p.kind == 1) {
            ck(api.NeuronModel_setOperandValue(model_, p.index, &p.scalar, 4), "Int value");
        } else if (p.kind == 3) {
            if (p.off + p.len > blobSize) throw std::runtime_error("Value outside weights");
            ck(api.NeuronModel_setOperandValue(model_, p.index, blob + p.off, p.len), "Tensor value");
        }
    }
    for (uint32_t i = 0; i < ops; i++) {
        auto& in = opIo[2 * i];
        auto& out = opIo[2 * i + 1];
        ck(api.NeuronModel_addOperation(model_, opCodes[i], in.size(), in.data(), out.size(), out.data()), "Add operation");
    }
    ck(api.NeuronModel_identifyInputsAndOutputs(model_, inputs.size(), inputs.data(), outputs.size(), outputs.data()), "Model IO");
    ck(api.NeuronModel_finish(model_), "Model finish");
    opCount_ = ops;
    inBuf_.resize(in_.elements * (in_.quantized ? 1 : 4));
    outBuf_.resize(out_.elements * (out_.quantized ? 1 : 4));
}

std::vector<const NeuronDevice*> NeuronModelRunner::fullSupport() {
    std::vector<const NeuronDevice*> out;
    std::unique_ptr<bool[]> ok(new bool[opCount_]);
    for (auto& d : rt_->accelerators) {
        std::fill_n(ok.get(), opCount_, false);
        if (rt_->api.NeuronModel_getSupportedOperationsForDevices(model_, &d.device, 1, ok.get()) == kNeuronNoError &&
            std::all_of(ok.get(), ok.get() + opCount_, [](bool b) { return b; }))
            out.push_back(d.device);
    }
    return out;
}

void NeuronModelRunner::prepare(const std::string& cache) {
    auto& api = rt_->api;
    if (fileExists(cache)) {
        auto bytes = readFile(cache);
        NeuronModel* m = nullptr;
        NeuronCompilation* c = nullptr;
        if (api.NeuronModel_restoreFromCompiledNetwork(&m, &c, bytes.data(), bytes.size()) == kNeuronNoError && c) {
            api.NeuronModel_free(model_);
            model_ = m;
            compilation_ = c;
            device = "cached";
            return;
        }
        if (m) api.NeuronModel_free(m);
        removeFile(cache);
    }
    std::string failures;
    for (const NeuronDevice* d : fullSupport()) {
        NeuronCompilation* c = nullptr;
        // createForDevices with one accelerator: no silent CPU fallback.
        if (api.NeuronCompilation_createForDevices(model_, &d, 1, &c) != kNeuronNoError) continue;
        if (api.NeuronCompilation_setPreference(c, kNeuronPreferFastSingleAnswer) != kNeuronNoError ||
            api.NeuronCompilation_finish(c) != kNeuronNoError) {
            api.NeuronCompilation_free(c);
            failures = "compile failed";
            continue;
        }
        compilation_ = c;
        for (auto& a : rt_->accelerators) if (a.device == d) device = a.name;
        size_t size = 0;
        if (api.NeuronCompilation_getCompiledNetworkSize(c, &size) == kNeuronNoError && size) {
            std::vector<uint8_t> bytes(size);
            if (api.NeuronCompilation_storeCompiledNetwork(c, bytes.data(), size) == kNeuronNoError) writeFileAtomic(cache, bytes.data(), size);
        }
        return;
    }
    throw std::runtime_error(failures.empty() ? "No NPU device runs every op" : "NPU compile failed");
}

void NeuronModelRunner::run(const float* input, float* output) {
    std::lock_guard<std::mutex> guard(lock_);
    auto& api = rt_->api;
    if (!compilation_) throw std::runtime_error("NPU model is not prepared");
    if (in_.quantized) quantize(input, inBuf_.data(), in_.elements, in_.scale, in_.zero);
    else std::memcpy(inBuf_.data(), input, inBuf_.size());
    NeuronExecution* e = nullptr;
    ck(api.NeuronExecution_create(compilation_, &e), "Execution");
    std::unique_ptr<NeuronExecution, void (*)(NeuronExecution*)> exec(e, api.NeuronExecution_free);
    if (api.NeuronExecution_setBoostHint) api.NeuronExecution_setBoostHint(e, 100);
    ck(api.NeuronExecution_setInput(e, 0, nullptr, inBuf_.data(), inBuf_.size()), "Set input");
    ck(api.NeuronExecution_setOutput(e, 0, nullptr, outBuf_.data(), outBuf_.size()), "Set output");
    ck(api.NeuronExecution_compute(e), "Compute");
    if (out_.quantized) dequantize(outBuf_.data(), output, out_.elements, out_.scale, out_.zero);
    else std::memcpy(output, outBuf_.data(), outBuf_.size());
}

}  // namespace npu
