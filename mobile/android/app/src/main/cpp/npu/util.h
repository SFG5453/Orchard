#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace npu {
std::vector<uint8_t> readFile(const std::string& path);
bool fileExists(const std::string& path);
void removeFile(const std::string& path);
// Temp file then rename, so a killed process never leaves a half-written cache.
void writeFileAtomic(const std::string& path, const void* data, size_t size);
void quantize(const float* in, uint8_t* out, size_t n, float scale, int32_t zero);
void dequantize(const uint8_t* in, float* out, size_t n, float scale, int32_t zero);
}
