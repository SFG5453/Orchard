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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "planner/pair_scorer.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <iomanip>
#include <limits>
#include <map>
#include <numeric>
#include <optional>
#include <regex>
#include <set>
#include <sstream>
#include <string>
#include <vector>

namespace orchard::planner {
namespace {

using Opt = std::optional<double>;
constexpr double kInf = std::numeric_limits<double>::infinity();
constexpr double kMaxStretch = 0.04;
constexpr int kMaxRoleCandidates = 12;
constexpr int kMaxDetailedCandidates = 64;
constexpr double kMaxDiscardedSeconds = 12;
constexpr double kVocalActiveThreshold = 0.6;
constexpr std::array<const char*, 8> kFrameFields = {
    "energy", "low", "mid", "high", "vocal", "novelty", "transientDensity", "stability"};

const Json& At(const Json& value, const char* key) {
  static const Json empty;
  if (!value.is_object()) return empty;
  auto found = value.find(key);
  return found == value.end() ? empty : *found;
}

Json Array(const Json& value) { return value.is_array() ? value : Json::array(); }
const Json& AsArray(const Json& value) {
  static const Json empty = Json::array();
  return value.is_array() ? value : empty;
}

Opt Finite(const Json& value) {
  if (value.is_null()) return {};
  double number = 0;
  if (value.is_number()) number = value.get<double>();
  else if (value.is_boolean()) number = value.get<bool>() ? 1 : 0;
  else if (value.is_string()) {
    const auto& str = value.get_ref<const std::string&>();
    if (str.empty()) return {};
    try {
      size_t used = 0;
      number = std::stod(str, &used);
      if (used != str.size()) return {};
    } catch (...) { return {}; }
  } else return {};
  return std::isfinite(number) ? Opt(number) : Opt();
}

Opt Number(const Json& value, const char* key) { return Finite(At(value, key)); }
double Or(Opt value, double fallback = 0) { return value.value_or(fallback); }
double Positive(const Json& value, double fallback = 0) {
  const auto number = Finite(value);
  return number && *number > 0 ? *number : fallback;
}
double Clamp(const Json& value, double lower = 0, double upper = 1) {
  return std::max(lower, std::min(upper, Or(Finite(value), lower)));
}
double Clamp(double value, double lower = 0, double upper = 1) {
  return std::max(lower, std::min(upper, value));
}
double Round(double value, int places = 6) {
  const double scale = std::pow(10.0, places);
  return std::floor(value * scale + 0.5) / scale;
}
Json JNum(Opt value) { return value ? Json(*value) : Json(nullptr); }
std::string Str(const Json& value, const std::string& fallback = "") {
  if (value.is_string()) return value.get<std::string>();
  if (value.is_number()) return value.dump();
  return fallback;
}
std::string Fixed(double value, int places) {
  std::ostringstream stream;
  stream << std::fixed << std::setprecision(places) << value;
  return stream.str();
}
Json Nullish(const Json& preferred, const Json& fallback) {
  return preferred.is_null() ? fallback : preferred;
}

Json SortedTimes(const Json& source, double duration) {
  std::set<double> values;
  const double maximum = duration > 0 ? duration : kInf;
  for (const auto& item : Array(source)) {
    auto value = Finite(item);
    if (value && *value >= 0 && *value <= maximum) values.insert(Round(*value));
  }
  Json result = Json::array();
  for (double value : values) result.push_back(value);
  return result;
}

Json BoundaryEvidence(const Json& candidate, const Json& overrides = Json::object()) {
  const Json& nested = At(candidate, "evidence").is_object() ? At(candidate, "evidence") : candidate;
  Json output = Json::object();
  for (const char* name : {"observedTime", "noveltyPeak", "energyDelta", "lowDelta", "vocalDelta",
                           "stabilityBefore", "stabilityAfter", "downbeatDistance"}) {
    const Json& value = (!At(overrides, name).is_null()) ? At(overrides, name) : At(nested, name);
    output[name] = JNum(Finite(value));
  }
  return output;
}

Opt CurveValueAt(const Json& curve, double target) {
  Opt best;
  double distance = kInf;
  for (const auto& point : Array(curve)) {
    auto time = Number(point, "time"), value = Number(point, "energy");
    if (!time || !value) continue;
    if (std::abs(*time - target) < distance) {
      distance = std::abs(*time - target);
      best = value;
    }
  }
  return best;
}

Json NormalizeFrames(const Json& raw, double duration) {
  Json supplied = Array(At(raw, "transitionFeatureFrames"));
  if (supplied.empty()) {
    supplied = Json::array();
    const Json energy = Array(At(raw, "energyCurve"));
    const Json vocal = Array(At(raw, "vocalActivityMask"));
    for (size_t index = 0; index < energy.size(); ++index) {
      const auto& point = energy[index];
      const auto observedTime = Number(point, "time");
      // Compact {t,e} curves have no frame time in the desktop normalizer. They are
      // discarded there, so avoid three needless full-curve searches per point.
      if (!observedTime) continue;
      const double time = *observedTime;
      Json frame = {{"time", At(point, "time")}, {"energy", At(point, "energy")},
                    {"low", JNum(CurveValueAt(At(raw, "lowEnergyCurve"), time))},
                    {"mid", JNum(CurveValueAt(At(raw, "midEnergyCurve"), time))},
                    {"high", JNum(CurveValueAt(At(raw, "highEnergyCurve"), time))},
                    {"vocal", index < vocal.size() ? JNum(Finite(vocal[index])) : Json(nullptr)}};
      supplied.push_back(std::move(frame));
    }
  }
  std::map<double, Json> byTime;
  for (const auto& item : supplied) {
    auto time = Number(item, "time");
    if (!time || *time < 0 || *time > duration) continue;
    Json frame = {{"time", Round(*time)}};
    for (const char* field : kFrameFields) frame[field] = JNum(Number(item, field));
    byTime[Round(*time)] = std::move(frame);
  }
  std::vector<Json> frames;
  frames.reserve(byTime.size());
  for (auto& entry : byTime) frames.push_back(std::move(entry.second));
  Json output = Json::array();
  if (frames.size() <= 240) {
    for (auto& frame : frames) output.push_back(std::move(frame));
  } else {
    const double stride = static_cast<double>(frames.size() - 1) / 239;
    for (int index = 0; index < 240; ++index)
      output.push_back(frames[static_cast<size_t>(std::floor(index * stride + 0.5))]);
  }
  return output;
}

Json NormalizeAnalysis(const Json& raw) {
  if (At(raw, "timing").is_object() && At(raw, "audibleRange").is_object() &&
      At(raw, "harmonic").is_object() && At(raw, "frames").is_array() &&
      At(raw, "boundaries").is_array()) return raw;

  const double duration = Positive(At(raw, "duration"));
  const double bpm = Positive(At(raw, "bpm"));
  const double beatInterval = Positive(At(raw, "beatInterval"), bpm > 0 ? 60 / bpm : 0);
  const double beatConfidence = Clamp(At(raw, "beatConfidence"));
  const Json& meterRaw = At(raw, "meter");
  const Json meter = {
      {"beatsPerBar", Clamp(std::floor(Positive(At(meterRaw, "beatsPerBar"), 4) + 0.5), 1, 16)},
      {"confidence", Clamp(At(meterRaw, "confidence"))},
      {"source", Str(At(meterRaw, "source")) == "detected" ? "detected" : "assumed-4-4"}};
  const Json timing = {
      {"bpm", bpm}, {"beatInterval", beatInterval},
      {"beats", SortedTimes(At(raw, "beats"), duration)},
      {"downbeats", SortedTimes(At(raw, "downbeats"), duration)},
      {"beatConfidence", beatConfidence},
      {"downbeatConfidence", Clamp(Nullish(At(raw, "downbeatConfidence"), beatConfidence))},
      {"source", At(raw, "beatModelChecked") == true ? "beat-model-refined" :
          Str(At(raw, "analysisSource")).empty() ? "analysis" : Str(At(raw, "analysisSource"))},
      {"meter", meter}};
  const double rangeStart = Clamp(Nullish(At(raw, "audibleStartTime"), At(raw, "pickupTime")), 0, duration);
  const double rangeEnd = Clamp(Nullish(At(raw, "contentEndTime"), duration), rangeStart, duration);
  const Json range = {{"start", rangeStart}, {"end", rangeEnd},
                      {"confidence", Clamp(Nullish(At(raw, "pickupConfidence"), rangeEnd > rangeStart ? 0.5 : 0.0))}};
  Json chroma = Json::array();
  for (const auto& item : Array(At(raw, "chroma"))) {
    if (chroma.size() == 12) break;
    chroma.push_back(Or(Finite(item)));
  }
  const Json harmonic = {{"key", Str(At(raw, "key"))},
                         {"keyConfidence", Clamp(At(raw, "keyConfidence"))},
                         {"chroma", chroma}};

  Json boundaries = Json::array();
  for (const auto& candidate : Array(At(raw, "structuralBoundaryCandidates"))) {
    auto observed = Finite(Nullish(At(candidate, "observedTime"), At(candidate, "time")));
    if (!observed || *observed < rangeStart || *observed > rangeEnd) continue;
    Opt nearest;
    double distance = kInf;
    for (const auto& downbeat : timing["downbeats"]) {
      const double value = downbeat.get<double>();
      if (value < rangeStart || value > rangeEnd) continue;
      if (std::abs(value - *observed) < distance) {
        nearest = value;
        distance = std::abs(value - *observed);
      }
    }
    const double tolerance = std::max(0.25, beatInterval);
    const double time = nearest && distance <= tolerance ? *nearest : *observed;
    boundaries.push_back({{"time", Round(time)}, {"confidence", Clamp(At(candidate, "confidence"))},
                          {"source", "detected-change"},
                          {"evidence", BoundaryEvidence(candidate, {{"observedTime", Round(*observed)},
                               {"downbeatDistance", std::isfinite(distance) ? Json(Round(distance)) : Json(nullptr)}})}});
  }
  for (double time : {rangeStart, rangeEnd})
    boundaries.push_back({{"time", Round(time)}, {"confidence", range["confidence"]},
                          {"source", "endpoint"}, {"evidence", BoundaryEvidence(Json::object())}});
  Json downbeats = Json::array();
  for (const auto& item : timing["downbeats"])
    if (item.get<double>() >= rangeStart && item.get<double>() <= rangeEnd) downbeats.push_back(item);
  for (size_t index = 0; index < downbeats.size(); index += 4)
    boundaries.push_back({{"time", downbeats[index]},
                          {"confidence", std::min({0.2, beatConfidence, Clamp(timing["downbeatConfidence"])})},
                          {"source", "rhythmic-fallback"},
                          {"evidence", BoundaryEvidence(Json::object(), {{"downbeatDistance", 0}})}});
  std::map<std::pair<std::string, double>, Json> unique;
  for (const auto& boundary : boundaries) {
    auto key = std::make_pair(Str(At(boundary, "source")), Or(Number(boundary, "time")));
    auto found = unique.find(key);
    if (found == unique.end() || Clamp(At(boundary, "confidence")) > Clamp(At(found->second, "confidence")))
      unique[key] = boundary;
  }
  boundaries = Json::array();
  for (auto& entry : unique) boundaries.push_back(std::move(entry.second));
  auto priority = [](const Json& value) {
    const auto source = Str(At(value, "source"));
    return source == "detected-change" ? 0 : source == "endpoint" ? 1 : 2;
  };
  std::sort(boundaries.begin(), boundaries.end(), [&](const Json& left, const Json& right) {
    const double a = Or(Number(left, "time")), b = Or(Number(right, "time"));
    return a != b ? a < b : priority(left) < priority(right);
  });
  return {{"duration", duration}, {"audibleRange", range}, {"timing", timing},
          {"harmonic", harmonic}, {"frames", NormalizeFrames(raw, duration)},
          {"boundaries", boundaries}};
}

Opt Interpolate(const Json& analysis, const char* field, double time) {
  const Json& frames = At(analysis, "frames");
  if (!frames.is_array() || frames.empty() || time < Or(Number(frames.front(), "time")) ||
      time > Or(Number(frames.back(), "time"))) return {};
  const auto next = std::lower_bound(frames.begin(), frames.end(), time,
      [](const Json& frame, double target) { return Or(Number(frame, "time")) < target; });
  if (next == frames.end()) return {};
  const double nextTime = Or(Number(*next, "time"));
  if (time == nextTime) return Number(*next, field);
  if (next == frames.begin()) return {};
  const auto& previous = *(next - 1);
  const double prevTime = Or(Number(previous, "time"));
  const auto left = Number(previous, field), right = Number(*next, field);
  if (!left || !right || nextTime <= prevTime) return {};
  return Round(*left + (*right - *left) * ((time - prevTime) / (nextTime - prevTime)));
}

Json Summarize(const Json& analysis, double start, double end) {
  Json summary = {{"coverage", 0}};
  const Json& frames = At(analysis, "frames");
  size_t count = 0;
  for (const auto& frame : AsArray(frames))
    if (Or(Number(frame, "time"), kInf) >= start && Or(Number(frame, "time"), -kInf) <= end) ++count;
  summary["coverage"] = count ? 1 : 0;
  for (const char* field : kFrameFields) {
    double sum = 0;
    size_t known = 0;
    for (const auto& frame : AsArray(frames)) {
      const double time = Or(Number(frame, "time"), kInf);
      if (time < start || time > end) continue;
      if (auto value = Number(frame, field)) { sum += *value; ++known; }
    }
    summary[field] = known ? Json(Round(sum / known)) : Json(nullptr);
  }
  return summary;
}

}  // namespace
}  // namespace orchard::planner

namespace orchard::planner {
namespace {

Opt ParseRoot(const std::string& key, std::string* mode);
Json PairDecision(const Json& rawOutgoing, const Json& rawIncoming);

Opt LegacyRoot(const Json& value, std::string* mode) {
  const std::string key = Str(value);
  const size_t split = key.find(' ');
  const std::string root = key.substr(0, split);
  *mode = split == std::string::npos ? "" : key.substr(split + 1, key.find(' ', split + 1) - split - 1);
  static const std::map<std::string, int> index = {
      {"C",0},{"C♯",1},{"D♭",1},{"D",2},{"D♯",3},{"E♭",3},
      {"E",4},{"F",5},{"F♯",6},{"G♭",6},{"G",7},{"G♯",8},
      {"A♭",8},{"A",9},{"A♯",10},{"B♭",10},{"B",11}};
  auto found = index.find(root);
  return found == index.end() ? Opt() : Opt(found->second);
}

double NormalizedTempoRatio(const Json& left, const Json& right) {
  const double a = Or(Number(left, "bpm")), b = Or(Number(right, "bpm"));
  if (!a || !b) return 0;
  double ratio = b / a;
  while (ratio > 1.5) ratio /= 2;
  while (ratio < 0.67) ratio *= 2;
  return ratio;
}

Opt LegacyHarmonicCost(const Json& left, const Json& right) {
  std::string aMode, bMode;
  const auto a = LegacyRoot(left, &aMode), b = LegacyRoot(right, &bMode);
  if (!a || !b) return {};
  const int ai = static_cast<int>(*a), bi = static_cast<int>(*b);
  if (aMode != bMode) {
    if ((aMode == "major" && bi == (ai + 9) % 12) ||
        (bMode == "major" && ai == (bi + 9) % 12)) return 0.05;
    if (ai == bi) return 0.22;
    const int distance = std::min((ai - bi + 12) % 12, (bi - ai + 12) % 12);
    return std::min(1.0, 0.35 + distance / 10.0);
  }
  const int ac = ai * 7 % 12, bc = bi * 7 % 12;
  const int distance = std::min((ac - bc + 12) % 12, (bc - ac + 12) % 12);
  if (distance == 0) return 0;
  if (distance == 1) return 0.12;
  if (distance == 2) return 0.38;
  return std::min(1.0, 0.55 + distance * 0.09);
}

double Confidence(const Json& value, double fallback) {
  const auto number = Finite(value);
  return number && *number > 0 ? Clamp(*number, 0.15, 1) : fallback;
}

Opt EdgeEnergy(const Json& value, bool fromEnd) {
  const Json curve = Array(value);
  if (curve.empty()) return {};
  const size_t count = std::min<size_t>(6, std::max<size_t>(2, std::ceil(curve.size() * 0.08)));
  double sum = 0;
  int known = 0;
  const size_t actualCount = std::min(count, curve.size());
  for (size_t index = 0; index < actualCount; ++index) {
    const auto energy = Number(curve[fromEnd ? curve.size() - actualCount + index : index], "energy");
    if (!energy) continue;
    sum += *energy;
    ++known;
  }
  return known ? Opt(sum / known) : Opt();
}

Opt LegacyCost(const Json& left, const Json& right) {
  double weighted = 0, total = 0;
  const double ratio = NormalizedTempoRatio(left, right);
  if (ratio) {
    const double weight = 4 * std::sqrt(
        Confidence(Nullish(At(left, "tempoConfidence"), At(left, "beatConfidence")), 0.35) *
        Confidence(Nullish(At(right, "tempoConfidence"), At(right, "beatConfidence")), 0.35));
    weighted += std::min(1.5, std::abs(std::log2(ratio)) / std::log2(1.2)) * weight;
    total += weight;
  }
  const auto key = LegacyHarmonicCost(At(left, "key"), At(right, "key"));
  if (key) {
    const double weight = 2.4 * std::sqrt(
        Confidence(At(left, "keyConfidence"), 0.35) *
        Confidence(At(right, "keyConfidence"), 0.35));
    weighted += *key * weight;
    total += weight;
  }
  const auto loudLeft = Number(left, "loudnessLufs"), loudRight = Number(right, "loudnessLufs");
  if (loudLeft && loudRight && *loudLeft > -69 && *loudRight > -69) {
    weighted += std::min(1.0, std::abs(*loudLeft - *loudRight) / 12) * 0.55;
    total += 0.55;
  }
  const auto energyLeft = EdgeEnergy(At(left, "energyCurve"), true);
  const auto energyRight = EdgeEnergy(At(right, "energyCurve"), false);
  if (energyLeft && energyRight) {
    weighted += std::min(1.0, std::abs(*energyLeft - *energyRight) / 1.5) * 0.45;
    total += 0.45;
  }
  const auto vocalLeft = Number(left, "vocalProbability"), vocalRight = Number(right, "vocalProbability");
  if (vocalLeft && vocalRight && *vocalLeft > -0.001 && *vocalRight > -0.001) {
    weighted += Clamp((*vocalLeft - 0.5) * 2) * Clamp((*vocalRight - 0.5) * 2) * 0.35;
    total += 0.35;
  }
  return total > 0 ? Opt(weighted / total) : Opt();
}

bool HasMusicalAnalysis(const Json& analysis) {
  if (Or(Number(analysis, "bpm")) > 0) return true;
  std::string mode;
  return LegacyRoot(At(analysis, "key"), &mode).has_value();
}

double CostFromDecision(const Json& decision, double legacy) {
  const std::string className = Str(At(decision, "transitionClass"));
  const int classCost = className == "full_beatmatched" ? 0 :
      className == "conservative_beatmatched" ? 1 : className == "simple_crossfade" ? 2 :
      className == "silence_trim" ? 3 : 4;
  const double confidence = Clamp(At(decision, "confidence"));
  const double quality = Clamp(At(decision, "quality"));
  return classCost * 10 + (1 - confidence) * 2 + (1 - quality) + legacy * 0.01;
}

double TransitionCost(const Json& left, const Json& right, double legacy) {
  return CostFromDecision(PairDecision(left, right), legacy);
}

struct Entry { Json analysis; int index; };

void SortSegment(std::vector<Entry>& segment, const Json& initial, Json& order) {
  if (segment.empty()) return;
  Json previous = initial;
  if (!HasMusicalAnalysis(previous)) {
    order.push_back(segment.front().index);
    previous = segment.front().analysis;
    segment.erase(segment.begin());
  }
  while (!segment.empty()) {
    struct Comparable { size_t position; double legacy; int index; double cost; };
    std::vector<Comparable> options;
    for (size_t index = 0; index < segment.size(); ++index) {
      if (auto legacy = LegacyCost(previous, segment[index].analysis))
        options.push_back({index, *legacy, segment[index].index, 0});
    }
    size_t best = 0;
    if (!options.empty()) {
      std::sort(options.begin(), options.end(), [](const Comparable& a, const Comparable& b) {
        return a.legacy != b.legacy ? a.legacy < b.legacy : a.index < b.index;
      });
      if (options.size() > 3) options.resize(3);
      for (auto& option : options)
        option.cost = TransitionCost(previous, segment[option.position].analysis, option.legacy);
      std::sort(options.begin(), options.end(), [](const Comparable& a, const Comparable& b) {
        if (a.cost != b.cost) return a.cost < b.cost;
        if (a.legacy != b.legacy) return a.legacy < b.legacy;
        return a.index < b.index;
      });
      best = options.front().position;
    }
    order.push_back(segment[best].index);
    previous = segment[best].analysis;
    segment.erase(segment.begin() + best);
  }
}

}  // namespace

Json ScorePair(const Json& outgoing, const Json& incoming) {
  const Json decision = PairDecision(outgoing, incoming);
  const auto legacy = LegacyCost(outgoing, incoming);
  Json output = decision;
  output.erase("selected");
  output["legacyCost"] = legacy ? Json(*legacy) : Json(nullptr);
  output["cost"] = legacy ? Json(CostFromDecision(decision, *legacy)) : Json(nullptr);
  return output;
}

Json SortQueue(const Json& analyses, const Json& initial) {
  Json order = Json::array();
  std::vector<Entry> segment;
  Json previous = initial;
  const Json values = Array(analyses);
  for (size_t index = 0; index < values.size(); ++index) {
    const Json& analysis = values[index];
    if (HasMusicalAnalysis(analysis)) {
      segment.push_back({analysis, static_cast<int>(index)});
    } else {
      if (!segment.empty()) {
        SortSegment(segment, previous, order);
        previous = values[order.back().get<size_t>()];
      }
      order.push_back(index);
      previous = Json::object();
    }
  }
  SortSegment(segment, previous, order);
  return order;
}

}  // namespace orchard::planner

namespace orchard::planner {
namespace {

Json TempoFit(const Json& outgoingAnalysis, const Json& incomingAnalysis);
Json HarmonicEvidence(const Json& outgoing, const Json& incoming);
Json MappedVocalCollision(const Json& outgoing, const Json& incoming, const Json& pair);
Json GenerateCandidates(const Json& analysis, const std::string& role);
Json BuildPairs(const Json& outgoingCandidates, const Json& incomingCandidates,
                const Json& fit, const Json& outgoing, const Json& incoming);

constexpr std::array<const char*, 5> kClasses = {"normal_boundary", "silence_trim", "simple_crossfade",
                                                  "conservative_beatmatched", "full_beatmatched"};
int Rank(const std::string& name) {
  for (int index = 0; index < static_cast<int>(kClasses.size()); ++index)
    if (name == kClasses[index]) return index;
  return 0;
}

Json PhaseEvidence(const Json& pair, const Json& outgoing, const Json& incoming) {
  const Json& leftTiming = At(outgoing, "timing"), &rightTiming = At(incoming, "timing");
  double leftDistance = kInf, rightDistance = kInf;
  for (const auto& item : Array(At(leftTiming, "downbeats")))
    if (auto value = Finite(item))
      leftDistance = std::min(leftDistance, std::abs(*value - Or(Number(pair, "outgoingEnd"))));
  for (const auto& item : Array(At(rightTiming, "downbeats")))
    if (auto value = Finite(item))
      rightDistance = std::min(rightDistance, std::abs(*value - Or(Number(pair, "incomingEnd"))));
  const bool usable = std::isfinite(leftDistance) && std::isfinite(rightDistance);
  const double error = usable ? std::max(leftDistance, rightDistance) : kInf;
  const double leftInterval = Or(Number(leftTiming, "beatInterval"), kInf);
  const double rightInterval = Or(Number(rightTiming, "beatInterval"), kInf);
  const double tolerance = std::max(0.08, std::min(leftInterval > 0 ? leftInterval : kInf,
                                                    rightInterval > 0 ? rightInterval : kInf) * 0.5);
  return {{"beatErrorSeconds", usable ? Json(Round(error)) : Json(nullptr)},
          {"downbeatAligned", usable && error <= std::min(0.08, tolerance)},
          {"score", usable ? Clamp(1 - error / std::max(tolerance, 0.08)) : 0.5}};
}

Json SpectralSimilarity(const Json& left, const Json& right) {
  double dot = 0, leftPower = 0, rightPower = 0;
  int known = 0;
  for (const char* field : {"low", "mid", "high"}) {
    const auto a = Number(left, field), b = Number(right, field);
    if (!a || !b) continue;
    ++known;
    dot += *a * *b;
    leftPower += *a * *a;
    rightPower += *b * *b;
  }
  if (!known || leftPower <= 0 || rightPower <= 0) return {{"score", 0.5}, {"coverage", 0}};
  return {{"score", Clamp(dot / std::sqrt(leftPower * rightPower))},
          {"coverage", static_cast<double>(known) / 3}};
}

Json EnergyScore(const Json& pair, const Json& outgoing, const Json& incoming,
                 const Json& leftSummary, const Json& rightSummary) {
  const auto leftStart = Interpolate(outgoing, "energy", Or(Number(pair, "outgoingStart")));
  const auto leftEnd = Interpolate(outgoing, "energy", Or(Number(pair, "outgoingEnd")));
  const auto rightStart = Interpolate(incoming, "energy", Or(Number(pair, "incomingStart")));
  const auto rightEnd = Interpolate(incoming, "energy", Or(Number(pair, "incomingEnd")));
  if (!leftStart || !leftEnd || !rightStart || !rightEnd) {
    const int known = static_cast<int>(Number(leftSummary, "energy").has_value()) +
                      static_cast<int>(Number(rightSummary, "energy").has_value());
    return {{"score", known ? 0.55 : 0.5}, {"coverage", known / 2.0}};
  }
  const double release = Clamp(0.5 + (*leftStart - *leftEnd));
  const double arrival = Clamp(0.5 + (*rightEnd - *rightStart));
  const double level = 1 - Clamp(std::abs((*leftStart + *leftEnd) / 2 - (*rightStart + *rightEnd) / 2));
  return {{"score", Clamp(release * 0.35 + arrival * 0.35 + level * 0.3)}, {"coverage", 1}};
}

Json VocalScore(const Json& collision, const Json& leftSummary, const Json& rightSummary) {
  const auto av = Number(leftSummary, "vocal"), bv = Number(rightSummary, "vocal");
  const double coverage = Or(Number(collision, "coverage"));
  if (coverage <= 0 || (!av && !bv)) return {{"score", 0.5}, {"coverage", 0}};
  const double risk = std::max(Clamp(Or(Number(collision, "activeFraction"))),
                               Clamp(Or(Number(collision, "simultaneousMean")) * 1.15));
  const double solo = std::max(av ? Clamp(*av) : 0, bv ? Clamp(*bv) : 0);
  const double soloRisk = Clamp((solo - 0.18) / 0.82);
  return {{"score", Clamp(1 - risk * 0.72 - soloRisk * 0.24)}, {"coverage", coverage}};
}

double StructureScore(const Json& pair, const Json& outgoing, const Json& incoming) {
  const Json& a = At(pair, "outgoingCandidate"), &b = At(pair, "incomingCandidate");
  auto strength = [](const Json& candidate) {
    const auto source = Str(At(candidate, "source"));
    return source == "detected-change" ? 1.0 : source == "endpoint" ? 0.62 :
        source == "downbeat-evidence" ? 0.42 : 0.22;
  };
  const double source = std::sqrt(strength(a) * strength(b));
  const double confidence = std::sqrt(Clamp(At(a, "confidence")) * Clamp(At(b, "confidence")));
  const double meter = std::sqrt(Clamp(At(At(At(outgoing, "timing"), "meter"), "confidence")) *
                                 Clamp(At(At(At(incoming, "timing"), "meter"), "confidence")));
  return Clamp(confidence * 0.6 + source * 0.3 + meter * 0.1);
}

Json EvaluatePair(const Json& pair, const Json& outgoing, const Json& incoming,
                  const Json& fit, const Json& harmonic) {
  const double duration = Or(Number(pair, "durationSeconds"));
  const Json leftSummary = Summarize(outgoing, Or(Number(pair, "outgoingStart")),
      Or(Number(pair, "outgoingStart")) + duration * Or(Number(pair, "outgoingRatio"), 1));
  const Json rightSummary = Summarize(incoming, Or(Number(pair, "incomingStart")),
      Or(Number(pair, "incomingStart")) + duration * Or(Number(pair, "incomingRatio"), 1));
  const Json collision = MappedVocalCollision(outgoing, incoming, pair);
  const Json vocal = VocalScore(collision, leftSummary, rightSummary);
  const Json phase = PhaseEvidence(pair, outgoing, incoming);
  const Json& left = At(pair, "outgoingCandidate"), &right = At(pair, "incomingCandidate");
  const Json& leftTiming = At(outgoing, "timing"), &rightTiming = At(incoming, "timing");
  const double beatConfidence = std::min(Clamp(At(leftTiming, "beatConfidence")),
                                         Clamp(At(rightTiming, "beatConfidence")));
  int maximumClass = Rank("full_beatmatched");
  Json gates = Json::array();
  auto lower = [&](const char* code, const char* className, const char* severity) {
    gates.push_back({{"code", code}, {"severity", severity}, {"maximumClass", className}});
    maximumClass = std::min(maximumClass, Rank(className));
  };
  if (At(fit, "beatmatched") != true) lower("tempo-distance", "simple_crossfade", "veto");
  if (beatConfidence < 0.55) lower("beat-confidence", "simple_crossfade", "veto");
  if (At(harmonic, "severeClash") == true) lower("harmonic-clash", "simple_crossfade", "veto");
  const bool sustained = Or(Number(collision, "coverage")) >= 0.5 &&
      Or(Number(collision, "activeFraction")) >= 0.3 &&
      Or(Number(collision, "longestRunBeats")) >= 4;
  if (sustained) lower("vocal-collision", "conservative_beatmatched", "demotion");
  const int detected = static_cast<int>(Str(At(left, "source")) == "detected-change") +
                       static_cast<int>(Str(At(right, "source")) == "detected-change");
  const double structureConfidence = std::min(Clamp(At(left, "confidence")), Clamp(At(right, "confidence")));
  const double meterConfidence = std::min(
      Clamp(At(At(leftTiming, "meter"), "confidence")),
      Clamp(At(At(rightTiming, "meter"), "confidence")));
  if (detected < 2 || structureConfidence < 0.55 || meterConfidence < 0.35) {
    const bool rhythmic = Str(At(left, "source")) == "rhythmic-fallback" ||
                          Str(At(right, "source")) == "rhythmic-fallback";
    lower("structure-confidence", rhythmic ? "simple_crossfade" : "conservative_beatmatched", "demotion");
  }
  if (Or(Number(harmonic, "confidence")) < 0.25 || Or(Number(collision, "coverage")) < 0.5)
    lower("evidence-coverage", "conservative_beatmatched", "demotion");
  const auto error = Number(phase, "beatErrorSeconds");
  const double interval = std::min(Or(Number(leftTiming, "beatInterval"), kInf),
                                   Or(Number(rightTiming, "beatInterval"), kInf));
  if (error && *error > std::max(0.2, interval * 0.45))
    lower("phase-error", "simple_crossfade", "veto");

  const Json energy = EnergyScore(pair, outgoing, incoming, leftSummary, rightSummary);
  const Json spectral = SpectralSimilarity(leftSummary, rightSummary);
  const double geometricBeat = std::sqrt(Clamp(At(leftTiming, "beatConfidence")) *
                                         Clamp(At(rightTiming, "beatConfidence")));
  double stability = 0, knownStability = 0;
  if (auto value = Number(leftSummary, "stability")) { stability += Clamp(*value); ++knownStability; }
  if (auto value = Number(rightSummary, "stability")) { stability += Clamp(*value); ++knownStability; }
  stability = knownStability ? stability / knownStability : 0.5;
  const int beats = static_cast<int>(Or(Number(pair, "beats")));
  Json components = {
      {"beat", Clamp(geometricBeat * 0.75 + Or(Number(phase, "score")) * 0.25)},
      {"structure", StructureScore(pair, outgoing, incoming)},
      {"tempo", At(fit, "beatmatched") == true ?
          Clamp(1 - Or(Number(fit, "deviation")) / kMaxStretch) : 0.35},
      {"harmonic", Clamp(At(harmonic, "score"))},
      {"vocal", Or(Number(vocal, "score"))},
      {"energy", Or(Number(energy, "score"))},
      {"spectral", Or(Number(spectral, "score"))},
      {"stability", Clamp(stability)},
      {"duration", beats == 16 ? 1.0 : beats == 8 ? 0.85 : beats == 32 ? 0.75 : 0.65},
      {"dspRisk", 1.0}};
  for (auto& item : components.items()) item.value() = Round(item.value().get<double>());
  const std::array<std::pair<const char*, double>, 10> weights = {{{"beat", 0.14}, {"structure", 0.14},
      {"tempo", 0.1}, {"harmonic", 0.13}, {"vocal", 0.15}, {"energy", 0.12},
      {"spectral", 0.08}, {"stability", 0.07}, {"duration", 0.04}, {"dspRisk", 0.03}}};
  double quality = 0;
  for (const auto& [name, weight] : weights) quality += components[name].get<double>() * weight;
  quality = Round(quality);
  const double coverage = Round((geometricBeat + Or(Number(components, "structure")) +
      Or(Number(harmonic, "confidence")) + Or(Number(vocal, "coverage")) +
      Or(Number(energy, "coverage")) + Or(Number(spectral, "coverage"))) / 6);
  int vetoes = 0;
  for (const auto& gate : gates) if (Str(At(gate, "severity")) == "veto") ++vetoes;
  const double confidence = Round(Clamp(quality * 0.82 + coverage * 0.18 - vetoes * 0.06 -
      (static_cast<int>(gates.size()) - vetoes) * 0.015));
  auto classify = [&](double value) {
    if (maximumClass >= Rank("full_beatmatched") && value >= 0.78) return std::string("full_beatmatched");
    if (maximumClass >= Rank("conservative_beatmatched") && value >= 0.62)
      return std::string("conservative_beatmatched");
    if (value >= 0.42) return std::string("simple_crossfade");
    if (value >= 0.25) return std::string("silence_trim");
    return std::string("normal_boundary");
  };
  return {{"id", At(pair, "id")}, {"pair", pair}, {"components", components},
          {"harmonic", harmonic}, {"collision", collision}, {"gates", gates},
          {"maximumClass", kClasses[maximumClass]}, {"phase", phase},
          {"evidenceCoverage", coverage}, {"quality", quality}, {"confidence", confidence},
          {"transitionClass", classify(confidence)}};
}

std::string Classify(double confidence, const std::string& maximumClass) {
  const int rank = Rank(maximumClass);
  if (rank >= Rank("full_beatmatched") && confidence >= 0.78) return "full_beatmatched";
  if (rank >= Rank("conservative_beatmatched") && confidence >= 0.62) return "conservative_beatmatched";
  if (confidence >= 0.42) return "simple_crossfade";
  if (confidence >= 0.25) return "silence_trim";
  return "normal_boundary";
}

std::string StrategyFor(const Json& evaluation) {
  const auto className = Str(At(evaluation, "transitionClass"));
  if (className == "silence_trim" || className == "normal_boundary") return "boundary_handoff";
  const Json& collision = At(evaluation, "collision"), &pair = At(evaluation, "pair");
  if (className == "simple_crossfade") {
    if (Or(Number(collision, "longestRunBeats")) >= 4 ||
        Or(Number(collision, "activeFraction")) >= 0.3 ||
        Or(Number(collision, "simultaneousMean")) > 0.25)
      return Or(Number(pair, "durationSeconds")) <= 0 ? "boundary_handoff" : "filtered_blend";
    return "filtered_blend";
  }
  const auto outgoingLow = Number(At(At(pair, "outgoingCandidate"), "summary"), "low");
  const auto incomingLow = Number(At(At(pair, "incomingCandidate"), "summary"), "low");
  if (outgoingLow && incomingLow && *outgoingLow >= 0.62 && *incomingLow >= 0.62) return "bass_swap";
  if (Or(Number(At(evaluation, "components"), "harmonic")) < 0.55 ||
      Or(Number(At(evaluation, "components"), "spectral")) < 0.55 ||
      Or(Number(collision, "simultaneousMean")) > 0.25) return "filtered_blend";
  return "beatmatched_crossfade";
}

Json PairDecision(const Json& rawOutgoing, const Json& rawIncoming) {
  const Json outgoing = NormalizeAnalysis(rawOutgoing), incoming = NormalizeAnalysis(rawIncoming);
  const Json leftCandidates = GenerateCandidates(outgoing, "outgoing");
  const Json rightCandidates = GenerateCandidates(incoming, "incoming");
  const Json fit = TempoFit(outgoing, incoming);
  const Json pairs = BuildPairs(leftCandidates, rightCandidates, fit, outgoing, incoming);
  if (pairs.empty()) {
    const Json& a = At(outgoing, "audibleRange"), &b = At(incoming, "audibleRange");
    const double availableOutgoing = std::max(0.0, Or(Number(a, "end")) - Or(Number(a, "start")));
    const double availableIncoming = std::max(0.0, Or(Number(b, "end")) - Or(Number(b, "start")));
    const double seconds = std::min({4.0, availableOutgoing, availableIncoming});
    const bool usable = seconds >= 1;
    const std::string className = usable ?
        "simple_crossfade" : "normal_boundary";
    return {{"transitionClass", className}, {"confidence", 0}, {"quality", 0},
            {"selectedId", nullptr}, {"selected", nullptr},
            {"strategy", usable ? "equal_power_crossfade" : "short_fade"},
            {"outgoingStart", Or(Number(a, "end")) - (usable ? seconds : 0)},
            {"outgoingEnd", Or(Number(a, "end"))},
            {"incomingStart", Or(Number(b, "start"))},
            {"incomingHandoff", Or(Number(b, "start"))},
            {"durationSeconds", usable ? seconds : 0},
            {"beats", 0}, {"targetBpm", 0},
            {"outgoingRatio", 1}, {"incomingRatio", 1}};
  }
  const Json harmonic = HarmonicEvidence(outgoing, incoming);
  Json evaluations = Json::array();
  for (const auto& pair : pairs) evaluations.push_back(EvaluatePair(pair, outgoing, incoming, fit, harmonic));
  std::sort(evaluations.begin(), evaluations.end(), [](const Json& a, const Json& b) {
    const int ar = Rank(Str(At(a, "transitionClass"))), br = Rank(Str(At(b, "transitionClass")));
    if (ar != br) return ar > br;
    const double ac = Or(Number(a, "confidence")), bc = Or(Number(b, "confidence"));
    if (ac != bc) return ac > bc;
    const double aq = Or(Number(a, "quality")), bq = Or(Number(b, "quality"));
    return aq != bq ? aq > bq : Str(At(a, "id")) < Str(At(b, "id"));
  });
  Json winner = evaluations.front();
  Json runner;
  for (size_t index = 1; index < evaluations.size(); ++index) {
    if (At(evaluations[index], "transitionClass") == At(winner, "transitionClass") &&
        At(evaluations[index], "id") != At(winner, "id")) { runner = evaluations[index]; break; }
  }
  if (runner.is_null() && evaluations.size() > 1) runner = evaluations[1];
  const double margin = Round(std::max(0.0, Or(Number(winner, "confidence")) - Or(Number(runner, "confidence"))));
  const double confidence = Round(Clamp(Or(Number(winner, "confidence")) + std::min(0.02, margin * 0.15)));
  winner["confidence"] = confidence;
  winner["transitionClass"] = Classify(confidence, Str(At(winner, "maximumClass")));
  const Json& pair = At(winner, "pair");
  Json gateCodes = Json::array();
  for (const auto& gate : Array(At(winner, "gates"))) gateCodes.push_back(At(gate, "code"));
  return {{"transitionClass", winner["transitionClass"]}, {"confidence", confidence},
          {"quality", winner["quality"]}, {"selectedId", winner["id"]},
          {"strategy", StrategyFor(winner)},
          {"outgoingStart", At(pair, "outgoingStart")},
          {"outgoingEnd", At(pair, "outgoingEnd")}, {"incomingHandoff", At(pair, "incomingEnd")},
          {"incomingStart", At(pair, "incomingStart")},
          {"durationSeconds", At(pair, "durationSeconds")},
          {"beats", At(pair, "beats")}, {"targetBpm", At(pair, "targetBpm")},
          {"outgoingRatio", At(pair, "outgoingRatio")},
          {"incomingRatio", At(pair, "incomingRatio")},
          {"gates", gateCodes}, {"selected", winner}};
}

}  // namespace
}  // namespace orchard::planner

namespace orchard::planner {
namespace {

Json TempoFit(const Json& outgoingAnalysis, const Json& incomingAnalysis) {
  double outgoing = Or(Number(At(outgoingAnalysis, "timing"), "bpm"));
  double incoming = Or(Number(At(incomingAnalysis, "timing"), "bpm"));
  if (outgoing <= 0 || incoming <= 0)
    return {{"outgoingBpm", outgoing}, {"incomingBpm", incoming}, {"targetBpm", outgoing},
            {"outgoingRatio", 1}, {"incomingRatio", 1}, {"deviation", 0}, {"beatmatched", false}};
  while (incoming / outgoing > 1.5) incoming /= 2;
  while (incoming / outgoing < 0.67) incoming *= 2;
  incoming = Round(incoming);
  const double ratio = incoming / outgoing;
  const double deviation = std::abs(ratio - 1);
  if (deviation > kMaxStretch)
    return {{"outgoingBpm", outgoing}, {"incomingBpm", incoming}, {"targetBpm", outgoing},
            {"outgoingRatio", 1}, {"incomingRatio", 1}, {"deviation", Round(deviation)},
            {"beatmatched", false}};
  return {{"outgoingBpm", outgoing}, {"incomingBpm", incoming}, {"targetBpm", incoming},
          {"outgoingRatio", Round(ratio)}, {"incomingRatio", 1}, {"deviation", Round(deviation)},
          {"beatmatched", true}};
}

Opt ParseRoot(const std::string& key, std::string* mode) {
  static const std::regex pattern(R"(^([^\s]+)\s+(major|minor)$)", std::regex::icase);
  std::smatch match;
  if (!std::regex_match(key, match, pattern)) return {};
  *mode = match[2];
  std::transform(mode->begin(), mode->end(), mode->begin(), [](unsigned char c) { return std::tolower(c); });
  std::string root = match[1];
  for (char& c : root) if (static_cast<unsigned char>(c) < 128) c = std::toupper(static_cast<unsigned char>(c));
  static const std::map<std::string, int> roots = {
      {"C",0},{"B#",0},{"C#",1},{"C♯",1},{"DB",1},{"D♭",1},
      {"D",2},{"D#",3},{"D♯",3},{"EB",3},{"E♭",3},{"E",4},{"FB",4},
      {"F",5},{"E#",5},{"F#",6},{"F♯",6},{"GB",6},{"G♭",6},
      {"G",7},{"G#",8},{"G♯",8},{"AB",8},{"A♭",8},{"A",9},
      {"A#",10},{"A♯",10},{"BB",10},{"B♭",10},{"B",11},{"CB",11}};
  auto found = roots.find(root);
  return found == roots.end() ? Opt() : Opt(found->second);
}

Opt KeyRelationship(const Json& left, const Json& right) {
  std::string leftMode, rightMode;
  auto a = ParseRoot(Str(left), &leftMode), b = ParseRoot(Str(right), &rightMode);
  if (!a || !b) return {};
  const int clockwise = (static_cast<int>(*b) - static_cast<int>(*a) + 12) % 12;
  const int distance = std::min(clockwise, 12 - clockwise);
  if (leftMode != rightMode) {
    if ((leftMode == "major" && clockwise == 9) || (leftMode == "minor" && clockwise == 3)) return 0.95;
    if (distance == 0) return 0.7;
    if (distance == 5) return 0.55;
    if (distance == 6) return 0.05;
    return 0.35;
  }
  if (distance == 0) return 1;
  if (distance == 5) return 0.85;
  if (distance == 2) return 0.65;
  if (distance == 1) return 0.3;
  if (distance == 6) return 0;
  return 0.45;
}

Opt ChromaSimilarity(const Json& left, const Json& right) {
  if (!left.is_array() || left.size() != 12 || !right.is_array() || right.size() != 12) return {};
  double dot = 0, leftPower = 0, rightPower = 0;
  for (int index = 0; index < 12; ++index) {
    const double a = Or(Finite(left[index])), b = Or(Finite(right[index]));
    dot += a * b;
    leftPower += a * a;
    rightPower += b * b;
  }
  if (leftPower <= 0 || rightPower <= 0) return {};
  return Clamp(dot / std::sqrt(leftPower * rightPower));
}

Json HarmonicEvidence(const Json& outgoing, const Json& incoming) {
  const Json& left = At(outgoing, "harmonic"), &right = At(incoming, "harmonic");
  const auto keyScore = KeyRelationship(At(left, "key"), At(right, "key"));
  const auto chromaScore = ChromaSimilarity(At(left, "chroma"), At(right, "chroma"));
  const double score = keyScore && chromaScore ? *keyScore * 0.7 + *chromaScore * 0.3 :
      keyScore ? *keyScore : chromaScore.value_or(0.5);
  const double confidence = std::sqrt(Clamp(At(left, "keyConfidence")) * Clamp(At(right, "keyConfidence")));
  return {{"score", Round(score)}, {"confidence", Round(confidence)},
          {"keyScore", keyScore ? Json(Round(*keyScore)) : Json(nullptr)},
          {"chromaScore", chromaScore ? Json(Round(*chromaScore)) : Json(nullptr)},
          {"severeClash", confidence >= 0.65 && score < 0.2}};
}

double FrameCadence(const Json& analysis) {
  const Json& frames = At(analysis, "frames");
  if (!frames.is_array()) return 0.25;
  std::vector<double> differences;
  for (size_t index = 1; index < frames.size(); ++index) {
    const double delta = Or(Number(frames[index], "time")) - Or(Number(frames[index - 1], "time"));
    if (delta > 0) differences.push_back(delta);
  }
  std::sort(differences.begin(), differences.end());
  return differences.empty() ? 0.25 : differences[differences.size() / 2];
}

Json MappedVocalCollision(const Json& outgoing, const Json& incoming, const Json& pair) {
  const double duration = std::max(0.0, Or(Number(pair, "durationSeconds")));
  const double outgoingStart = Or(Number(pair, "outgoingStart"));
  const double incomingStart = Or(Number(pair, "incomingStart"));
  const double outgoingRatio = Or(Number(pair, "outgoingRatio"), 1);
  const double incomingRatio = Or(Number(pair, "incomingRatio"), 1);
  const double targetBpm = Or(Number(pair, "targetBpm"));
  const double step = std::max(0.05, std::min({0.25, FrameCadence(outgoing), FrameCadence(incoming)}));
  const int sampleCount = std::max(1, static_cast<int>(std::ceil(duration / step)) + 1);
  int known = 0, active = 0, currentRun = 0, longestRun = 0;
  double simultaneousTotal = 0;
  for (int index = 0; index < sampleCount; ++index) {
    const double time = std::min(duration, index * step);
    const auto a = Interpolate(outgoing, "vocal", outgoingStart + time * outgoingRatio);
    const auto b = Interpolate(incoming, "vocal", incomingStart + time * incomingRatio);
    if (!a || !b) { currentRun = 0; continue; }
    ++known;
    simultaneousTotal += std::min(*a, *b);
    if (*a >= kVocalActiveThreshold && *b >= kVocalActiveThreshold) {
      ++active;
      longestRun = std::max(longestRun, ++currentRun);
    } else currentRun = 0;
  }
  const double longestSeconds = std::min(duration, longestRun * step);
  return {{"simultaneousMean", known ? Json(Round(simultaneousTotal / known)) : Json(nullptr)},
          {"activeFraction", known ? Json(Round(static_cast<double>(active) / known)) : Json(nullptr)},
          {"longestRunSeconds", Round(longestSeconds)},
          {"longestRunBeats", targetBpm > 0 ? Round(longestSeconds * targetBpm / 60) : 0},
          {"coverage", Round(static_cast<double>(known) / sampleCount)}};
}

Json AudibleTail(const Json& analysis, double anchor) {
  const Json& range = At(analysis, "audibleRange");
  const double rangeStart = Or(Number(range, "start"));
  const double rangeEnd = Or(Number(range, "end"), Or(Number(analysis, "duration")));
  const double start = std::max(rangeStart, std::min(rangeEnd, anchor));
  const double end = std::max(start, rangeEnd);
  const double span = std::max(0.0, end - start);
  if (span <= 1e-6)
    return {{"spanSeconds", 0}, {"audibleSeconds", 0}, {"unknownSeconds", 0},
            {"chargedAudibleSeconds", 0}, {"coverage", 1}, {"classification", "endpoint"}};
  std::vector<Json> frames;
  std::vector<double> energies;
  for (const auto& frame : Array(At(analysis, "frames"))) {
    if (!Number(frame, "time")) continue;
    frames.push_back(frame);
    auto energy = Number(frame, "energy");
    if (energy && *energy >= 0) energies.push_back(*energy);
  }
  std::sort(frames.begin(), frames.end(), [](const Json& a, const Json& b) {
    return Or(Number(a, "time")) < Or(Number(b, "time"));
  });
  std::sort(energies.begin(), energies.end());
  const double reference = energies.empty() ? kInf : energies[static_cast<size_t>(std::floor((energies.size() - 1) * 0.85))];
  const double threshold = reference > 0 && std::isfinite(reference) ? reference * 0.1 : kInf;
  double known = 0, audible = 0;
  for (size_t index = 0; index + 1 < frames.size(); ++index) {
    const auto& left = frames[index], &right = frames[index + 1];
    const double segmentStart = std::max(start, Or(Number(left, "time")));
    const double segmentEnd = std::min(end, Or(Number(right, "time")));
    const auto a = Number(left, "energy"), b = Number(right, "energy");
    if (segmentEnd <= segmentStart || !a || !b) continue;
    const double seconds = segmentEnd - segmentStart;
    known += seconds;
    if ((*a + *b) / 2 >= threshold) audible += seconds;
  }
  known = std::min(span, known);
  const double unknown = std::max(0.0, span - known);
  const double charged = std::min(span, audible + unknown);
  const double coverage = span > 0 ? known / span : 1;
  const char* classification = coverage < 0.5 ? "unknown" :
      audible <= 0.5 && unknown <= 0.5 ? "silence" :
      charged <= kMaxDiscardedSeconds ? "short-tail" : "continuing";
  return {{"spanSeconds", Round(span)}, {"audibleSeconds", Round(audible)},
          {"unknownSeconds", Round(unknown)}, {"chargedAudibleSeconds", Round(charged)},
          {"coverage", Round(coverage)}, {"classification", classification}};
}

int SourcePriority(const std::string& source) {
  return source == "detected-change" ? 4 : source == "endpoint" ? 3 :
      source == "downbeat-evidence" ? 2 : source == "rhythmic-fallback" ? 1 : 0;
}

Json CandidateFrom(const Json& analysis, const std::string& role, const Json& boundary) {
  const auto anchor = Number(boundary, "time");
  if (!anchor) return nullptr;
  const Json& range = At(analysis, "audibleRange");
  const Json summary = Summarize(analysis, std::max(Or(Number(range, "start")), *anchor - 16), *anchor);
  const double stability = Or(Number(summary, "stability"), 0.5);
  const auto vocal = Number(summary, "vocal");
  const std::string source = Str(At(boundary, "source"), "rhythmic-fallback");
  const double confidence = Clamp(At(boundary, "confidence"));
  const Json tail = role == "outgoing" ? AudibleTail(analysis, *anchor) : Json(nullptr);
  const double tailCost = tail.is_null() ? 0 : Clamp(Or(Number(tail, "chargedAudibleSeconds")) / kMaxDiscardedSeconds);
  const double priority = SourcePriority(source) * 0.2 + confidence * 0.55 + stability * 0.15 +
      (vocal ? 1 - Clamp(*vocal) : 0.35) * 0.1 - tailCost * 0.15;
  return {{"id", role + ":" + source + ":" + Fixed(*anchor, 6)}, {"role", role},
          {"anchorTime", *anchor}, {"source", source}, {"confidence", confidence},
          {"runwaySeconds", Round(*anchor - Or(Number(range, "start")))},
          {"tail", tail}, {"summary", summary}, {"evidence", At(boundary, "evidence")},
          {"priorityScore", Round(priority)}};
}

Json GenerateCandidates(const Json& analysis, const std::string& role) {
  Json candidates = Json::array();
  const Json& range = At(analysis, "audibleRange"), &timing = At(analysis, "timing");
  const double start = Or(Number(range, "start")), end = Or(Number(range, "end"));
  const double duration = Or(Number(analysis, "duration"));
  const double windowStart = role == "incoming" ? start : std::max(start, end - std::min(60.0, duration * 0.4));
  const double windowEnd = role == "incoming" ? std::min(end, start + std::min(60.0, duration * 0.35)) : end;
  for (const auto& boundary : Array(At(analysis, "boundaries"))) {
    const double time = Or(Number(boundary, "time"), -kInf);
    if (time >= windowStart && time <= windowEnd) candidates.push_back(CandidateFrom(analysis, role, boundary));
  }
  for (const auto& downbeat : Array(At(timing, "downbeats"))) {
    const double time = Or(Finite(downbeat));
    if (time < windowStart || time > windowEnd) continue;
    const Json summary = Summarize(analysis, std::max(start, time - 16), time);
    const double stability = Or(Number(summary, "stability"), 0.5);
    const auto vocal = Number(summary, "vocal");
    const double confidence = Clamp(Or(Number(timing, "downbeatConfidence")) * 0.35 +
        stability * 0.35 + (vocal ? (1 - *vocal) * 0.3 : 0.15), 0, 0.6);
    candidates.push_back(CandidateFrom(analysis, role, {{"time", time}, {"confidence", confidence},
        {"source", "downbeat-evidence"}, {"evidence", {{"downbeatDistance", 0}}}}));
  }
  Json eligible = Json::array();
  for (const auto& candidate : candidates) {
    if (candidate.is_null()) continue;
    if (role == "outgoing" && Or(Number(At(candidate, "tail"), "chargedAudibleSeconds")) > kMaxDiscardedSeconds)
      continue;
    eligible.push_back(candidate);
  }
  std::sort(eligible.begin(), eligible.end(), [&](const Json& a, const Json& b) {
    const double ap = Or(Number(a, "priorityScore")), bp = Or(Number(b, "priorityScore"));
    if (ap != bp) return ap > bp;
    const int as = SourcePriority(Str(At(a, "source"))), bs = SourcePriority(Str(At(b, "source")));
    if (as != bs) return as > bs;
    const double at = Or(Number(a, "anchorTime")), bt = Or(Number(b, "anchorTime"));
    if (at != bt) return role == "incoming" ? at < bt : at > bt;
    return Str(At(a, "id")) < Str(At(b, "id"));
  });
  const double tolerance = std::max(0.05, Or(Number(timing, "beatInterval"), 0.5) / 2);
  Json output = Json::array();
  for (const auto& candidate : eligible) {
    const double time = Or(Number(candidate, "anchorTime"));
    bool duplicate = false;
    for (const auto& previous : output)
      if (std::abs(Or(Number(previous, "anchorTime")) - time) < tolerance) { duplicate = true; break; }
    if (duplicate) continue;
    output.push_back(candidate);
    if (output.size() >= kMaxRoleCandidates) break;
  }
  return output;
}

double CheapPairScore(const Json& outgoing, const Json& incoming, int beats, bool beatmatched) {
  const Json& left = At(outgoing, "summary"), &right = At(incoming, "summary");
  const double stability = (Or(Number(left, "stability"), 0.5) + Or(Number(right, "stability"), 0.5)) / 2;
  const auto av = Number(left, "vocal"), bv = Number(right, "vocal");
  const double clean = av && bv ? 1 - std::max(Clamp(*av), Clamp(*bv)) :
      av ? 1 - Clamp(*av) : bv ? 1 - Clamp(*bv) : 0.35;
  const double preference = beats == 16 ? 1 : beats == 8 ? 0.85 : beats == 32 ? 0.75 : 0.65;
  return Round(Or(Number(outgoing, "confidence")) * 0.25 +
      Or(Number(incoming, "confidence")) * 0.25 + stability * 0.15 + clean * 0.15 +
      preference * 0.1 + (beatmatched ? 1 : 0.4) * 0.1);
}

Json BuildPairs(const Json& outgoingCandidates, const Json& incomingCandidates,
                const Json& fit, const Json& outgoing, const Json& incoming) {
  const double targetBpm = Or(Number(fit, "targetBpm"));
  Json pairs = Json::array();
  if (targetBpm <= 0) return pairs;
  const bool beatmatched = At(fit, "beatmatched") == true;
  const double outgoingRatio = Or(Number(fit, "outgoingRatio"), 1);
  const double incomingRatio = Or(Number(fit, "incomingRatio"), 1);
  const Json& outgoingRange = At(outgoing, "audibleRange"), &incomingRange = At(incoming, "audibleRange");
  for (const auto& left : outgoingCandidates) {
    for (const auto& right : incomingCandidates) {
      const Json& a = At(left, "summary"), &b = At(right, "summary");
      const bool longAllowed = Str(At(left, "source")) == "detected-change" &&
          Str(At(right, "source")) == "detected-change" &&
          Or(Number(left, "confidence")) >= 0.65 && Or(Number(right, "confidence")) >= 0.65 &&
          Or(Number(At(outgoing, "timing"), "beatConfidence")) >= 0.7 &&
          Or(Number(At(incoming, "timing"), "beatConfidence")) >= 0.7 &&
          Number(a, "vocal") && Or(Number(a, "vocal")) <= 0.35 &&
          Number(b, "vocal") && Or(Number(b, "vocal")) <= 0.35;
      std::vector<std::pair<int, double>> specs;
      if (beatmatched) {
        for (int beats : {4, 8, 16}) specs.emplace_back(beats, beats * 60.0 / targetBpm);
        if (longAllowed) specs.emplace_back(32, 32 * 60.0 / targetBpm);
      } else {
        for (double seconds : {2.0, 3.0, 4.0}) specs.emplace_back(0, seconds);
        if (4 * 60.0 / targetBpm <= 4) specs.emplace_back(4, 4 * 60.0 / targetBpm);
      }
      for (const auto& [beats, seconds] : specs) {
        if (beatmatched && (beats < 8 || seconds < 4 - 1e-6)) continue;
        if (!beatmatched && seconds > 4 + 1e-6) continue;
        const double outgoingEnd = Or(Number(left, "anchorTime"));
        const double incomingEnd = Or(Number(right, "anchorTime"));
        const double outgoingStart = outgoingEnd - seconds * outgoingRatio;
        const double incomingStart = incomingEnd - seconds * incomingRatio;
        if (outgoingStart < Or(Number(outgoingRange, "start")) - 1e-6 ||
            outgoingEnd > Or(Number(outgoingRange, "end")) + 1e-6 ||
            incomingStart < Or(Number(incomingRange, "start")) - 1e-6 ||
            incomingEnd > Or(Number(incomingRange, "end")) + 1e-6) continue;
        if (std::max(std::abs(outgoingRatio - 1), std::abs(incomingRatio - 1)) > kMaxStretch + 1e-6)
          continue;
        const std::string suffix = (beats > 0 ? std::to_string(beats) : Fixed(seconds, 1)) + "s";
        pairs.push_back({{"id", Str(At(left, "id")) + ">" + Str(At(right, "id")) + ":" + suffix},
            {"outgoingCandidate", left}, {"incomingCandidate", right},
            {"outgoingStart", Round(outgoingStart)}, {"outgoingEnd", outgoingEnd},
            {"incomingStart", Round(incomingStart)}, {"incomingEnd", incomingEnd},
            {"durationSeconds", Round(seconds)}, {"beats", beats}, {"targetBpm", targetBpm},
            {"outgoingRatio", outgoingRatio}, {"incomingRatio", incomingRatio},
            {"beatmatched", beatmatched},
            {"cheapScore", CheapPairScore(left, right, beats, beatmatched)}});
      }
    }
  }
  std::sort(pairs.begin(), pairs.end(), [](const Json& a, const Json& b) {
    const double as = Or(Number(a, "cheapScore")), bs = Or(Number(b, "cheapScore"));
    return as != bs ? as > bs : Str(At(a, "id")) < Str(At(b, "id"));
  });
  if (pairs.size() > kMaxDetailedCandidates) pairs.erase(pairs.begin() + kMaxDetailedCandidates, pairs.end());
  return pairs;
}

}  // namespace
}  // namespace orchard::planner
