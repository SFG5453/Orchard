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
#include "../transition/transition_render.h"
#include "../transition/wsola.h"

namespace {

// This is part of the persisted cache/result contract; bump it when numerical
// semantics or the exported object shape become incompatible.
constexpr int kAnalysisVersion = 7;

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

Napi::Array EnergyCurveArray(Napi::Env env, const std::vector<orchard::EnergyPoint>& points) {
  auto output = Napi::Array::New(env, points.size());
  for (size_t index = 0; index < points.size(); ++index) {
    auto point = Napi::Object::New(env);
    point.Set("time", Compact(points[index].time));
    point.Set("energy", Compact(points[index].energy));
    output.Set(index, point);
  }
  return output;
}

Napi::Array MixCueArray(Napi::Env env, const std::vector<orchard::MixCuePoint>& cues) {
  auto output = Napi::Array::New(env, cues.size());
  for (size_t index = 0; index < cues.size(); ++index) {
    auto cue = Napi::Object::New(env);
    cue.Set("time", Compact(cues[index].time));
    cue.Set("score", Compact(cues[index].score));
    cue.Set("type", cues[index].type);
    output.Set(index, cue);
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

  output.Set("energyCurve", EnergyCurveArray(env, result.energy_curve));
  output.Set("lowEnergyCurve", EnergyCurveArray(env, result.low_energy_curve));
  output.Set("midEnergyCurve", EnergyCurveArray(env, result.mid_energy_curve));
  output.Set("highEnergyCurve", EnergyCurveArray(env, result.high_energy_curve));
  output.Set("vocalActivityMask", NumberArray(env, result.vocal_activity_mask));
  output.Set("mixInCandidates", MixCueArray(env, result.mix_in_candidates));
  output.Set("mixOutCandidates", MixCueArray(env, result.mix_out_candidates));

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

// Owns the planar PCM snapshot and stretched result for the worker's lifetime.
// Mirrors AnalysisWorker: no shared mutable DSP state, no cancellation hook.
class StretchWorker final : public Napi::AsyncWorker {
 public:
  StretchWorker(
    Napi::Env env,
    std::vector<std::vector<float>> channels,
    double sample_rate,
    orchard::WsolaConfig config
  ) : Napi::AsyncWorker(env),
      deferred_(Napi::Promise::Deferred::New(env)),
      channels_(std::move(channels)),
      sample_rate_(sample_rate),
      config_(config) {}

  Napi::Promise Promise() const {
    return deferred_.Promise();
  }

  void Execute() override {
    // libuv worker-pool thread: do not create or retain JavaScript/Napi values.
    result_ = orchard::WsolaStretch(channels_, sample_rate_, config_);
  }

  void OnOK() override {
    // Environment thread: each channel is copied into its own Float32Array.
    const auto env = Env();
    auto output = Napi::Array::New(env, result_.size());
    for (size_t channel = 0; channel < result_.size(); ++channel) {
      auto typed = Napi::Float32Array::New(env, result_[channel].size());
      std::copy(result_[channel].begin(), result_[channel].end(), typed.Data());
      output.Set(channel, typed);
    }
    deferred_.Resolve(output);
  }

  void OnError(const Napi::Error& error) override {
    deferred_.Reject(error.Value());
  }

 private:
  Napi::Promise::Deferred deferred_;
  std::vector<std::vector<float>> channels_;
  double sample_rate_;
  orchard::WsolaConfig config_;
  std::vector<std::vector<float>> result_;
};

// Time-scales planar PCM without changing pitch. Accepts an array of
// Float32Array channels so the caller can hand over AudioBuffer planes
// directly; the copy is required because that storage stays owned by
// JavaScript and cannot be read later from the worker-pool thread.
Napi::Value TimeStretch(const Napi::CallbackInfo& info) {
  const auto env = info.Env();
  if (info.Length() < 3 || !info[0].IsArray() || !info[1].IsNumber() || !info[2].IsNumber()) {
    Napi::TypeError::New(env, "timeStretch expects channel Float32Arrays, sampleRate, and ratio")
      .ThrowAsJavaScriptException();
    return env.Undefined();
  }

  const auto planes = info[0].As<Napi::Array>();
  const double sample_rate = info[1].As<Napi::Number>().DoubleValue();
  const double ratio = info[2].As<Napi::Number>().DoubleValue();
  if (planes.Length() == 0 || !std::isfinite(sample_rate) || sample_rate < 1000 ||
      !std::isfinite(ratio) || ratio <= 0) {
    Napi::RangeError::New(env, "channels, sample rate, and ratio must be valid")
      .ThrowAsJavaScriptException();
    return env.Undefined();
  }

  std::vector<std::vector<float>> channels(planes.Length());
  size_t expected = 0;
  for (uint32_t index = 0; index < planes.Length(); ++index) {
    const auto plane = planes.Get(index);
    if (!plane.IsTypedArray() ||
        plane.As<Napi::TypedArray>().TypedArrayType() != napi_float32_array) {
      Napi::TypeError::New(env, "every channel must be a Float32Array")
        .ThrowAsJavaScriptException();
      return env.Undefined();
    }
    const auto typed = plane.As<Napi::Float32Array>();
    if (index == 0) expected = typed.ElementLength();
    if (typed.ElementLength() != expected || typed.ElementLength() == 0) {
      Napi::RangeError::New(env, "channels must be non-empty and the same length")
        .ThrowAsJavaScriptException();
      return env.Undefined();
    }
    channels[index].assign(typed.Data(), typed.Data() + typed.ElementLength());
  }

  orchard::WsolaConfig config;
  config.ratio = ratio;
  if (info.Length() > 3 && info[3].IsObject()) {
    const auto options = info[3].As<Napi::Object>();
    if (options.Has("frameSize")) {
      config.frame_size = options.Get("frameSize").As<Napi::Number>().Int32Value();
    }
    if (options.Has("searchRadius")) {
      config.search_radius = options.Get("searchRadius").As<Napi::Number>().Int32Value();
    }
    if (options.Has("startRatio")) {
      config.start_ratio = options.Get("startRatio").As<Napi::Number>().DoubleValue();
    }
    if (options.Has("glide")) {
      config.glide = options.Get("glide").As<Napi::Number>().DoubleValue();
    }
  }

  auto* worker = new StretchWorker(env, std::move(channels), sample_rate, config);
  auto promise = worker->Promise();
  worker->Queue();
  return promise;
}

// Reads one `{ channels, anchor, bpm }` source. Returns false and leaves the
// exception pending when the shape is wrong.
bool ReadSource(Napi::Env env, const Napi::Value& value, orchard::TransitionSource& source) {
  if (!value.IsObject()) {
    Napi::TypeError::New(env, "each transition source must be an object")
      .ThrowAsJavaScriptException();
    return false;
  }
  const auto object = value.As<Napi::Object>();
  if (!object.Get("channels").IsArray()) {
    Napi::TypeError::New(env, "each transition source needs a channels array")
      .ThrowAsJavaScriptException();
    return false;
  }
  const auto planes = object.Get("channels").As<Napi::Array>();
  source.channels.resize(planes.Length());
  for (uint32_t index = 0; index < planes.Length(); ++index) {
    const auto plane = planes.Get(index);
    if (!plane.IsTypedArray() ||
        plane.As<Napi::TypedArray>().TypedArrayType() != napi_float32_array) {
      Napi::TypeError::New(env, "every channel must be a Float32Array")
        .ThrowAsJavaScriptException();
      return false;
    }
    const auto typed = plane.As<Napi::Float32Array>();
    source.channels[index].assign(typed.Data(), typed.Data() + typed.ElementLength());
  }
  source.anchor = object.Has("anchor") ? object.Get("anchor").As<Napi::Number>().DoubleValue() : 0;
  source.bpm = object.Has("bpm") ? object.Get("bpm").As<Napi::Number>().DoubleValue() : 0;
  return true;
}

// Owns the PCM snapshots and rendered overlap for the worker's lifetime.
class TransitionWorker final : public Napi::AsyncWorker {
 public:
  TransitionWorker(
    Napi::Env env,
    orchard::TransitionSource outgoing,
    orchard::TransitionSource incoming,
    orchard::TransitionConfig config
  ) : Napi::AsyncWorker(env),
      deferred_(Napi::Promise::Deferred::New(env)),
      outgoing_(std::move(outgoing)),
      incoming_(std::move(incoming)),
      config_(config) {}

  Napi::Promise Promise() const {
    return deferred_.Promise();
  }

  void Execute() override {
    // libuv worker-pool thread: do not create or retain JavaScript/Napi values.
    result_ = orchard::RenderTransition(outgoing_, incoming_, config_);
  }

  void OnOK() override {
    // Environment thread: a refusal resolves normally so callers can branch on
    // `rendered` instead of wrapping every transition in a try/catch.
    const auto env = Env();
    auto output = Napi::Object::New(env);
    output.Set("rendered", result_.rendered);
    output.Set("rejected", result_.rejected);
    output.Set("stretchRatio", Compact(result_.stretch_ratio));
    output.Set("bpm", Compact(result_.bpm));
    auto planes = Napi::Array::New(env, result_.channels.size());
    for (size_t channel = 0; channel < result_.channels.size(); ++channel) {
      auto typed = Napi::Float32Array::New(env, result_.channels[channel].size());
      std::copy(result_.channels[channel].begin(), result_.channels[channel].end(), typed.Data());
      planes.Set(channel, typed);
    }
    output.Set("channels", planes);
    deferred_.Resolve(output);
  }

  void OnError(const Napi::Error& error) override {
    deferred_.Reject(error.Value());
  }

 private:
  Napi::Promise::Deferred deferred_;
  orchard::TransitionSource outgoing_;
  orchard::TransitionSource incoming_;
  orchard::TransitionConfig config_;
  orchard::TransitionResult result_;
};

// Renders a beat-matched overlap between two tracks into one buffer. The
// synchronous copies are required because TypedArray storage stays owned by
// JavaScript and cannot be read later from the worker-pool thread.
Napi::Value RenderTransition(const Napi::CallbackInfo& info) {
  const auto env = info.Env();
  if (info.Length() < 3 || !info[2].IsObject()) {
    Napi::TypeError::New(env, "renderTransition expects outgoing, incoming, and options")
      .ThrowAsJavaScriptException();
    return env.Undefined();
  }

  orchard::TransitionSource outgoing;
  orchard::TransitionSource incoming;
  if (!ReadSource(env, info[0], outgoing)) return env.Undefined();
  if (!ReadSource(env, info[1], incoming)) return env.Undefined();

  const auto options = info[2].As<Napi::Object>();
  orchard::TransitionConfig config;
  const auto number = [&options](const char* key, double fallback) {
    return options.Has(key) ? options.Get(key).As<Napi::Number>().DoubleValue() : fallback;
  };
  config.sample_rate = number("sampleRate", config.sample_rate);
  config.beats = number("beats", config.beats);
  config.bass_swap = number("bassSwap", config.bass_swap);
  config.bass_crossover_hz = number("bassCrossoverHz", config.bass_crossover_hz);
  config.bass_swap_seconds = number("bassSwapSeconds", config.bass_swap_seconds);
  config.tempo_glide = number("tempoGlide", config.tempo_glide);

  auto* worker = new TransitionWorker(env, std::move(outgoing), std::move(incoming), config);
  auto promise = worker->Promise();
  worker->Queue();
  return promise;
}

// Exposes the version marker, analysis, time-stretch, and transition renderer.
Napi::Object Initialize(Napi::Env env, Napi::Object exports) {
  exports.Set("analysisVersion", kAnalysisVersion);
  exports.Set("analyze", Napi::Function::New(env, Analyze));
  exports.Set("timeStretch", Napi::Function::New(env, TimeStretch));
  exports.Set("renderTransition", Napi::Function::New(env, RenderTransition));
  exports.Set(
    "maxTransparentRatioDeviation",
    Napi::Number::New(env, orchard::kMaxTransparentRatioDeviation)
  );
  return exports;
}

}  // namespace

NODE_API_MODULE(orchard_audio_analysis, Initialize)
