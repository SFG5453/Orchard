// The subset of MediaTek's Neuron adapter C ABI that Orchard calls, resolved at runtime from
// the phone's own libneuronusdk_adapter. Layouts and codes follow NeuroPilot 8.
#pragma once
#include <cstddef>
#include <cstdint>

struct NeuronModel;
struct NeuronCompilation;
struct NeuronExecution;
struct NeuronDevice;

struct NeuronOperandType {
    int32_t type;
    uint32_t dimensionCount;
    const uint32_t* dimensions;
    float scale;
    int32_t zeroPoint;
};

struct NeuronSymmPerChannelQuantParams {
    uint32_t channelDim;
    uint32_t scaleCount;
    const float* scales;
};

struct NeuronRuntimeVersion {
    uint8_t major, minor, patch;
};

constexpr int kNeuronNoError = 0;
constexpr int32_t kNeuronBool = 6;
constexpr int32_t kNeuronPreferFastSingleAnswer = 1;

#define NEURON_REQUIRED(X) \
    X(Neuron_getVersion, int, NeuronRuntimeVersion*) \
    X(Neuron_getDeviceCount, int, uint32_t*) \
    X(Neuron_getDevice, int, uint32_t, NeuronDevice**) \
    X(NeuronDevice_getName, int, const NeuronDevice*, const char**) \
    X(NeuronModel_create, int, NeuronModel**) \
    X(NeuronModel_free, void, NeuronModel*) \
    X(NeuronModel_addOperand, int, NeuronModel*, const NeuronOperandType*) \
    X(NeuronModel_setOperandValue, int, NeuronModel*, int32_t, const void*, size_t) \
    X(NeuronModel_setOperandSymmPerChannelQuantParams, int, NeuronModel*, int32_t, const NeuronSymmPerChannelQuantParams*) \
    X(NeuronModel_addOperation, int, NeuronModel*, int32_t, uint32_t, const uint32_t*, uint32_t, const uint32_t*) \
    X(NeuronModel_identifyInputsAndOutputs, int, NeuronModel*, uint32_t, const uint32_t*, uint32_t, const uint32_t*) \
    X(NeuronModel_finish, int, NeuronModel*) \
    X(NeuronModel_getSupportedOperationsForDevices, int, const NeuronModel*, const NeuronDevice* const*, uint32_t, bool*) \
    X(NeuronModel_restoreFromCompiledNetwork, int, NeuronModel**, NeuronCompilation**, const void*, size_t) \
    X(NeuronCompilation_createForDevices, int, NeuronModel*, const NeuronDevice* const*, uint32_t, NeuronCompilation**) \
    X(NeuronCompilation_setPreference, int, NeuronCompilation*, int32_t) \
    X(NeuronCompilation_finish, int, NeuronCompilation*) \
    X(NeuronCompilation_getCompiledNetworkSize, int, NeuronCompilation*, size_t*) \
    X(NeuronCompilation_storeCompiledNetwork, int, NeuronCompilation*, void*, size_t) \
    X(NeuronCompilation_free, void, NeuronCompilation*) \
    X(NeuronExecution_create, int, NeuronCompilation*, NeuronExecution**) \
    X(NeuronExecution_setInput, int, NeuronExecution*, int32_t, const void*, const void*, size_t) \
    X(NeuronExecution_setOutput, int, NeuronExecution*, int32_t, const void*, void*, size_t) \
    X(NeuronExecution_compute, int, NeuronExecution*) \
    X(NeuronExecution_free, void, NeuronExecution*)

#define NEURON_OPTIONAL(X) \
    X(NeuronExecution_setBoostHint, int, NeuronExecution*, uint8_t)

struct NeuronApi {
#define NEURON_FIELD(name, ret, ...) ret (*name)(__VA_ARGS__) = nullptr;
    NEURON_REQUIRED(NEURON_FIELD)
    NEURON_OPTIONAL(NEURON_FIELD)
#undef NEURON_FIELD
};
