/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

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
#include "../analyzer/mel_spectrogram.h"
#include "../analyzer/vocal_spectrogram.h"
#include "../transition/transition_render.h"
#include "../transition/rubberband_stretch.h"

namespace {

// This is part of the persisted cache/result contract; bump it when numerical
// semantics or the exported object shape become incompatible.
constexpr int kAnalysisVersion = 11;

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

Napi::Array TransitionFeatureFrameArray(
  Napi::Env env,
  const std::vector<orchard::TransitionFeatureFrame>& frames
) {
  auto output = Napi::Array::New(env, frames.size());
  for (size_t index = 0; index < frames.size(); ++index) {
    const auto& frame = frames[index];
    auto value = Napi::Object::New(env);
    value.Set("time", Compact(frame.time));
    value.Set("energy", Compact(frame.energy));
    value.Set("low", Compact(frame.low));
    value.Set("mid", Compact(frame.mid));
    value.Set("high", Compact(frame.high));
    value.Set("vocal", Compact(frame.vocal));
    value.Set("novelty", Compact(frame.novelty));
    value.Set("transientDensity", Compact(frame.transient_density));
    value.Set("stability", Compact(frame.stability));
    output.Set(index, value);
  }
  return output;
}

Napi::Array StructuralBoundaryArray(
  Napi::Env env,
  const std::vector<orchard::StructuralBoundaryCandidate>& boundaries
) {
  auto output = Napi::Array::New(env, boundaries.size());
  for (size_t index = 0; index < boundaries.size(); ++index) {
    const auto& boundary = boundaries[index];
    auto value = Napi::Object::New(env);
    value.Set("time", Compact(boundary.time));
    value.Set("confidence", Compact(boundary.confidence));
    value.Set("source", boundary.source);
    value.Set("noveltyPeak", Compact(boundary.novelty_peak));
    value.Set("energyDelta", Compact(boundary.energy_delta));
    value.Set("lowDelta", Compact(boundary.low_delta));
    value.Set("vocalDelta", Compact(boundary.vocal_delta));
    value.Set("stabilityBefore", Compact(boundary.stability_before));
    value.Set("stabilityAfter", Compact(boundary.stability_after));
    value.Set("downbeatDistance", Compact(boundary.downbeat_distance));
    output.Set(index, value);
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
  auto meter = Napi::Object::New(env);
  meter.Set("beatsPerBar", result.meter.beats_per_bar);
  meter.Set("confidence", Compact(result.meter.confidence));
  meter.Set("source", result.meter.source);
  output.Set("meter", meter);
  output.Set(
    "transitionFeatureFrames",
    TransitionFeatureFrameArray(env, result.transition_feature_frames)
  );
  output.Set(
    "structuralBoundaryCandidates",
    StructuralBoundaryArray(env, result.structural_boundary_candidates)
  );

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

// Computes the beat model's log-mel input off the environment thread. Same
// ownership rules as AnalysisWorker: the PCM snapshot and the result live here
// until OnOK runs and the worker deletes itself.
class BeatSpectrogramWorker final : public Napi::AsyncWorker {
 public:
  BeatSpectrogramWorker(Napi::Env env, std::vector<float> samples, double sample_rate)
    : Napi::AsyncWorker(env),
      deferred_(Napi::Promise::Deferred::New(env)),
      samples_(std::move(samples)),
      sample_rate_(sample_rate) {}

  Napi::Promise Promise() const {
    return deferred_.Promise();
  }

  void Execute() override {
    // libuv worker-pool thread: do not create or retain JavaScript/Napi values.
    result_ = orchard::ComputeBeatSpectrogram(samples_, sample_rate_);
  }

  void OnOK() override {
    const auto env = Env();
    auto object = Napi::Object::New(env);
    object.Set("frames", Napi::Number::New(env, static_cast<double>(result_.frames)));
    object.Set("mels", Napi::Number::New(env, static_cast<double>(orchard::kBeatSpectrogramMels)));
    // Frames per second, so the caller can turn model frame indices into times
    // without duplicating the hop constant.
    object.Set("framesPerSecond", Napi::Number::New(
      env,
      orchard::kBeatSpectrogramSampleRate / orchard::kBeatSpectrogramHop
    ));
    auto typed = Napi::Float32Array::New(env, result_.values.size());
    if (!result_.values.empty()) {
      std::copy(result_.values.begin(), result_.values.end(), typed.Data());
    }
    object.Set("values", typed);
    deferred_.Resolve(object);
  }

  void OnError(const Napi::Error& error) override {
    deferred_.Reject(error.Value());
  }

 private:
  Napi::Promise::Deferred deferred_;
  std::vector<float> samples_;
  double sample_rate_;
  orchard::BeatSpectrogram result_;
};

// Mono Float32 PCM at exactly 22,050 Hz in, flattened [frames][128] log-mel out.
// A rate mismatch resolves to zero frames rather than throwing: the caller
// treats "no spectrogram" as "no model prediction" and keeps the native grid.
Napi::Value BeatSpectrogram(const Napi::CallbackInfo& info) {
  const auto env = info.Env();
  if (info.Length() < 2 || !info[0].IsTypedArray() || !info[1].IsNumber()) {
    Napi::TypeError::New(env, "beatSpectrogram expects Float32Array samples and sampleRate")
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
  if (samples.ElementLength() == 0 || !std::isfinite(sample_rate) || sample_rate < 1000) {
    Napi::RangeError::New(env, "audio samples and sample rate must be valid")
      .ThrowAsJavaScriptException();
    return env.Undefined();
  }

  std::vector<float> copied(samples.ElementLength());
  std::copy(samples.Data(), samples.Data() + samples.ElementLength(), copied.begin());
  auto* worker = new BeatSpectrogramWorker(env, std::move(copied), sample_rate);
  auto promise = worker->Promise();
  worker->Queue();
  return promise;
}

// Computes the vocal-separation model's linear-STFT input off the environment
// thread. Same ownership rules as BeatSpectrogramWorker.
class VocalSpectrogramWorker final : public Napi::AsyncWorker {
 public:
  VocalSpectrogramWorker(
    Napi::Env env,
    std::vector<std::vector<float>> channels,
    double sample_rate
  ) : Napi::AsyncWorker(env),
      deferred_(Napi::Promise::Deferred::New(env)),
      channels_(std::move(channels)),
      sample_rate_(sample_rate) {}

  Napi::Promise Promise() const {
    return deferred_.Promise();
  }

  void Execute() override {
    result_ = orchard::ComputeVocalSpectrogram(channels_, sample_rate_);
  }

  void OnOK() override {
    const auto env = Env();
    auto object = Napi::Object::New(env);
    object.Set("frames", Napi::Number::New(env, static_cast<double>(result_.frames)));
    object.Set("channels", Napi::Number::New(env, static_cast<double>(orchard::kVocalSpectrogramChannels)));
    object.Set("bins", Napi::Number::New(env, static_cast<double>(orchard::kVocalSpectrogramBins)));
    object.Set("framesPerSecond", Napi::Number::New(
      env,
      orchard::kVocalSpectrogramSampleRate / orchard::kVocalSpectrogramHop
    ));
    auto typed = Napi::Float32Array::New(env, result_.values.size());
    if (!result_.values.empty()) {
      std::copy(result_.values.begin(), result_.values.end(), typed.Data());
    }
    object.Set("values", typed);
    deferred_.Resolve(object);
  }

  void OnError(const Napi::Error& error) override {
    deferred_.Reject(error.Value());
  }

 private:
  Napi::Promise::Deferred deferred_;
  std::vector<std::vector<float>> channels_;
  double sample_rate_;
  orchard::VocalSpectrogram result_;
};

// Stereo Float32 PCM at exactly 44,100 Hz in (planar, two channels required --
// mono callers must duplicate first, matching the transition renderer's
// convention), flattened [2][frames][2049] linear-magnitude STFT out. An
// invalid rate or channel count resolves to zero frames rather than throwing:
// the caller treats "no spectrogram" as "no vocal mask available".
Napi::Value VocalSpectrogram(const Napi::CallbackInfo& info) {
  const auto env = info.Env();
  if (info.Length() < 2 || !info[0].IsArray() || !info[1].IsNumber()) {
    Napi::TypeError::New(env, "vocalSpectrogram expects an array of two Float32Array channels and sampleRate")
      .ThrowAsJavaScriptException();
    return env.Undefined();
  }
  const auto planes = info[0].As<Napi::Array>();
  const double sample_rate = info[1].As<Napi::Number>().DoubleValue();
  if (!std::isfinite(sample_rate) || sample_rate < 1000) {
    Napi::RangeError::New(env, "sample rate must be valid").ThrowAsJavaScriptException();
    return env.Undefined();
  }

  std::vector<std::vector<float>> channels(planes.Length());
  for (uint32_t index = 0; index < planes.Length(); ++index) {
    const auto plane = planes.Get(index);
    if (!plane.IsTypedArray() ||
        plane.As<Napi::TypedArray>().TypedArrayType() != napi_float32_array) {
      Napi::TypeError::New(env, "every channel must be a Float32Array")
        .ThrowAsJavaScriptException();
      return env.Undefined();
    }
    const auto typed = plane.As<Napi::Float32Array>();
    if (typed.ElementLength() == 0) {
      Napi::RangeError::New(env, "channels must not be empty").ThrowAsJavaScriptException();
      return env.Undefined();
    }
    channels[index].assign(typed.Data(), typed.Data() + typed.ElementLength());
  }

  auto* worker = new VocalSpectrogramWorker(env, std::move(channels), sample_rate);
  auto promise = worker->Promise();
  worker->Queue();
  return promise;
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

class StretchWorker final : public Napi::AsyncWorker {
 public:
  StretchWorker(
    Napi::Env env,
    std::vector<std::vector<float>> channels,
    double sample_rate,
    double ratio
  ) : Napi::AsyncWorker(env),
      deferred_(Napi::Promise::Deferred::New(env)),
      channels_(std::move(channels)),
      sample_rate_(sample_rate),
      ratio_(ratio) {}

  Napi::Promise Promise() const {
    return deferred_.Promise();
  }

  void Execute() override {
    result_ = orchard::RubberBandTimeStretch(channels_, sample_rate_, ratio_);
  }

  void OnOK() override {
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
  double ratio_;
  std::vector<std::vector<float>> result_;
};

// Time-scales planar PCM without changing pitch, via the vendored Rubber Band
// Library. Accepts an array of Float32Array channels so the caller can hand
// over AudioBuffer planes directly; the copy is required because that
// storage stays owned by JavaScript and cannot be read later from the
// worker-pool thread.
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

  auto* worker = new StretchWorker(env, std::move(channels), sample_rate, ratio);
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
  config.handoff = number("handoff", config.handoff);
  config.bed = number("bed", config.bed);
  config.bass_crossover_hz = number("bassCrossoverHz", config.bass_crossover_hz);
  config.bass_swap_seconds = number("bassSwapSeconds", config.bass_swap_seconds);
  config.filter_sweep = number("filterSweep", config.filter_sweep);
  config.filter_sweep_start_hz = number("filterSweepStartHz", config.filter_sweep_start_hz);
  if (options.Has("vocalDuckCurve") && options.Get("vocalDuckCurve").IsArray()) {
    const auto curve = options.Get("vocalDuckCurve").As<Napi::Array>();
    config.vocal_duck_curve.resize(curve.Length());
    for (uint32_t index = 0; index < curve.Length(); ++index) {
      config.vocal_duck_curve[index] = curve.Get(index).As<Napi::Number>().FloatValue();
    }
  }

  auto* worker = new TransitionWorker(env, std::move(outgoing), std::move(incoming), config);
  auto promise = worker->Promise();
  worker->Queue();
  return promise;
}

Napi::Object Initialize(Napi::Env env, Napi::Object exports) {
  exports.Set("analysisVersion", kAnalysisVersion);
  exports.Set("analyze", Napi::Function::New(env, Analyze));
  exports.Set("beatSpectrogram", Napi::Function::New(env, BeatSpectrogram));
  exports.Set("vocalSpectrogram", Napi::Function::New(env, VocalSpectrogram));
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
