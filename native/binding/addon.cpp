// N-API boundary for the offline analyzer. JavaScript memory is snapshotted on
// the calling thread, DSP runs without N-API access on a libuv worker, and all
// JavaScript result allocation happens again on the calling environment thread.
// The binding owns no audio device or real-time callback resources.

#include <napi.h>

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

#include "../analyzer/audio_analysis.h"

namespace {

// This is part of the persisted cache/result contract; bump it when numerical
// semantics or the exported object shape become incompatible.
constexpr int kAnalysisVersion = 6;

// Stable cache output uses four decimal places to keep stored JSON compact.
double Compact(double value) {
  return std::round(value * 10000.0) / 10000.0;
}

Napi::Array NumberArray(Napi::Env env, const std::vector<double>& values) {
  auto output = Napi::Array::New(env, values.size());
  for (size_t index = 0; index < values.size(); ++index) {
    output.Set(index, Napi::Number::New(env, Compact(values[index])));
  }
  return output;
}

// Called only from OnOK: every Napi value must be created on the environment
// thread, never from Execute(). The returned JS object copies all native data.
Napi::Object ToObject(Napi::Env env, const orchard::AnalysisResult& result) {
  auto output = Napi::Object::New(env);
  output.Set("analysisVersion", kAnalysisVersion);
  output.Set("duration", Compact(result.duration));
  output.Set("bpm", Compact(result.bpm));
  output.Set("beatInterval", Compact(result.beat_interval));
  output.Set("firstBeat", Compact(result.first_beat));
  output.Set("beatConfidence", Compact(result.beat_confidence));
  output.Set("beats", NumberArray(env, result.beats));
  output.Set("downbeats", NumberArray(env, result.downbeats));
  output.Set("phraseBoundaries", NumberArray(env, result.phrase_boundaries));
  output.Set("key", result.key);
  output.Set("keyConfidence", Compact(result.key_confidence));
  output.Set("chroma", NumberArray(env, result.chroma));
  output.Set("audibleStartTime", Compact(result.audible_start_time));
  output.Set("pickupTime", Compact(result.pickup_time));
  output.Set("pickupConfidence", Compact(result.pickup_confidence));
  output.Set("mixInTime", Compact(result.mix_in_time));
  output.Set("mixInConfidence", Compact(result.mix_in_confidence));
  output.Set("introEndTime", Compact(result.intro_end_time));
  output.Set("outroStartTime", Compact(result.outro_start_time));
  output.Set("contentEndTime", Compact(result.content_end_time));
  output.Set("mixOutTime", Compact(result.mix_out_time));
  output.Set("loudnessLufs", Compact(result.loudness_lufs));
  output.Set("peakDbfs", Compact(result.peak_dbfs));
  output.Set("dynamicRangeDb", Compact(result.dynamic_range_db));
  output.Set("vocalProbability", Compact(result.vocal_probability));
  output.Set("instrumentalProbability", Compact(1.0 - result.vocal_probability));

  auto energy_curve = Napi::Array::New(env, result.energy_curve.size());
  for (size_t index = 0; index < result.energy_curve.size(); ++index) {
    auto point = Napi::Object::New(env);
    point.Set("time", Compact(result.energy_curve[index].time));
    point.Set("energy", Compact(result.energy_curve[index].energy));
    energy_curve.Set(index, point);
  }
  output.Set("energyCurve", energy_curve);

  auto phrases = Napi::Array::New(env, result.phrases.size());
  for (size_t index = 0; index < result.phrases.size(); ++index) {
    auto phrase = Napi::Object::New(env);
    phrase.Set("start", Compact(result.phrases[index].start));
    phrase.Set("end", Compact(result.phrases[index].end));
    phrase.Set("type", result.phrases[index].type);
    phrase.Set("confidence", Compact(result.phrases[index].confidence));
    phrases.Set(index, phrase);
  }
  output.Set("phrases", phrases);
  return output;
}

// Owns the PCM snapshot and result until AsyncWorker completes its callback and
// releases the heap-allocated worker. There is no shared mutable DSP state and
// no cancellation hook; process shutdown is the final fallback cleanup.
class AnalysisWorker final : public Napi::AsyncWorker {
 public:
  AnalysisWorker(
    Napi::Env env,
    std::vector<float> samples,
    double sample_rate,
    double duration
  ) : Napi::AsyncWorker(env),
      deferred_(Napi::Promise::Deferred::New(env)),
      samples_(std::move(samples)),
      sample_rate_(sample_rate),
      duration_(duration) {}

  Napi::Promise Promise() const {
    return deferred_.Promise();
  }

  void Execute() override {
    // libuv worker-pool thread: do not create or retain JavaScript/Napi values.
    result_ = orchard::AnalyzeAudio(samples_, sample_rate_, duration_);
  }

  void OnOK() override {
    // Environment thread: conversion may allocate many JS arrays and objects.
    deferred_.Resolve(ToObject(Env(), result_));
  }

  void OnError(const Napi::Error& error) override {
    deferred_.Reject(error.Value());
  }

 private:
  Napi::Promise::Deferred deferred_;
  std::vector<float> samples_;
  double sample_rate_;
  double duration_;
  orchard::AnalysisResult result_;
};

// Validates the public shape and metadata without scanning every sample. The
// synchronous copy is required because TypedArray backing storage remains owned
// by JavaScript and cannot safely be read later from the worker-pool thread.
Napi::Value Analyze(const Napi::CallbackInfo& info) {
  const auto env = info.Env();
  if (info.Length() < 3 || !info[0].IsTypedArray() || !info[1].IsNumber() || !info[2].IsNumber()) {
    Napi::TypeError::New(env, "analyze expects Float32Array samples, sampleRate, and duration")
      .ThrowAsJavaScriptException();
    return env.Undefined();
  }

  const auto typed = info[0].As<Napi::TypedArray>();
  if (typed.TypedArrayType() != napi_float32_array) {
    Napi::TypeError::New(env, "samples must be a Float32Array").ThrowAsJavaScriptException();
    return env.Undefined();
  }
  const auto samples = info[0].As<Napi::Float32Array>();
  const double sample_rate = info[1].As<Napi::Number>().DoubleValue();
  const double duration = info[2].As<Napi::Number>().DoubleValue();
  if (samples.ElementLength() == 0 || !std::isfinite(sample_rate) || sample_rate < 1000 ||
      !std::isfinite(duration) || duration <= 0) {
    Napi::RangeError::New(env, "audio samples, sample rate, and duration must be valid")
      .ThrowAsJavaScriptException();
    return env.Undefined();
  }

  // This whole-buffer allocation/copy runs on the Electron main thread. It is
  // acceptable for offline analysis but must not be introduced into an audio
  // render callback or another real-time path.
  std::vector<float> copied(samples.ElementLength());
  std::copy(samples.Data(), samples.Data() + samples.ElementLength(), copied.begin());
  auto* worker = new AnalysisWorker(env, std::move(copied), sample_rate, duration);
  auto promise = worker->Promise();
  worker->Queue();
  return promise;
}

// Exposes only the version marker and Promise-returning analysis entry point.
Napi::Object Initialize(Napi::Env env, Napi::Object exports) {
  exports.Set("analysisVersion", kAnalysisVersion);
  exports.Set("analyze", Napi::Function::New(env, Analyze));
  return exports;
}

}  // namespace

NODE_API_MODULE(orchard_audio_analysis, Initialize)
