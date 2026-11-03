#include "util.h"
#include <cstdio>
#include <stdexcept>
#include <unistd.h>

namespace npu {

std::vector<uint8_t> readFile(const std::string& path) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) throw std::runtime_error("Cannot open " + path);
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> data(size > 0 ? size_t(size) : 0);
    size_t got = fread(data.data(), 1, data.size(), f);
    fclose(f);
    if (got != data.size()) throw std::runtime_error("Short read " + path);
    return data;
}

bool fileExists(const std::string& path) { return access(path.c_str(), R_OK) == 0; }

void removeFile(const std::string& path) { unlink(path.c_str()); }

void writeFileAtomic(const std::string& path, const void* data, size_t size) {
    std::string tmp = path + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) return;
    bool ok = fwrite(data, 1, size, f) == size;
    ok &= fflush(f) == 0 && fsync(fileno(f)) == 0;
    fclose(f);
    if (!ok || rename(tmp.c_str(), path.c_str()) != 0) unlink(tmp.c_str());
}

// Clamp before adding 0.5 so truncation rounds half up; the loop vectorizes.
void quantize(const float* in, uint8_t* out, size_t n, float scale, int32_t zero) {
    const float inv = 1.0f / scale, z = float(zero);
    for (size_t i = 0; i < n; i++) {
        float t = in[i] * inv + z;
        t = t < 0.0f ? 0.0f : (t > 255.0f ? 255.0f : t);
        out[i] = uint8_t(t + 0.5f);
    }
}

void dequantize(const uint8_t* in, float* out, size_t n, float scale, int32_t zero) {
    for (size_t i = 0; i < n; i++) out[i] = float(int32_t(in[i]) - zero) * scale;
}

}  // namespace npu
