#pragma once
#include "neuron_abi.h"
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace npu {

struct NeuronDeviceRef {
    std::string name;
    const NeuronDevice* device;
};

// The phone's Neuron adapter with its MDLA/VPU/NPU/APU devices; CPU, GPU and DSP never count.
struct NeuronRuntime {
    void* handle = nullptr;
    std::string library, version;
    NeuronApi api;
    std::vector<NeuronDeviceRef> accelerators;
};

// First adapter that exposes an accelerator, or null with `error` set.
std::shared_ptr<NeuronRuntime> openNeuron(std::string& error);

// One all-int8 graph from a .npug file, compiled for a single accelerator.
class NeuronModelRunner {
public:
    NeuronModelRunner(std::shared_ptr<NeuronRuntime> runtime, const std::string& graph);
    ~NeuronModelRunner();
    // Accelerators that run every op, in enumeration order.
    std::vector<const NeuronDevice*> fullSupport();
    // Restores `cache` when it loads, otherwise compiles and writes it.
    void prepare(const std::string& cache);
    // Float in, float out; quantization happens here. Sizes are the graph's element counts.
    void run(const float* input, float* output);
    size_t inputSize() const { return in_.elements; }
    size_t outputSize() const { return out_.elements; }
    std::string device;

private:
    struct Io { bool quantized; float scale; int32_t zero; uint32_t elements; };
    void build();
    std::shared_ptr<NeuronRuntime> rt_;
    std::vector<uint8_t> file_;
    size_t blob_ = 0;
    uint32_t opCount_ = 0;
    Io in_{}, out_{};
    NeuronModel* model_ = nullptr;
    NeuronCompilation* compilation_ = nullptr;
    std::vector<uint8_t> inBuf_, outBuf_;
    std::mutex lock_;
};

}  // namespace npu
