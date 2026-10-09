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

#include "lyric_language.h"

#include <algorithm>
#include <array>
#include <map>
#include <set>

namespace lyric_language {
namespace {

struct LatinLanguage {
  const char *code;
  // Common function words; shared ones (de, la, que) still count, uniques decide.
  const char *words;
  // Letters almost only this language uses.
  const char *marks;
};

// Portuguese has no model yet; it is here so it never gets mistaken for Spanish.
constexpr std::array<LatinLanguage, 9> kLatin{{
    {"eng", "the and you i'm i you're it's is are was to of in on that this with your my me we "
            "don't can't won't what when just all now like love know never get got gonna wanna be", ""},
    {"spa", "el los las que y en un una es por con para mi tu te yo como pero más quiero eres estoy "
            "está todo nada cuando porque sin mí qué contigo corazón amor vida soy aquí", "ñ¿¡"},
    {"por", "os as um uma não é eu você meu minha com para na do da em mais mas seu sua tudo "
            "coração quando tão também pra só estou vou", "ãõ"},
    {"fra", "le les des et je il elle nous vous est qui pas ne dans pour avec mon ma mes ton ta moi "
            "toi c'est j'ai suis plus mais sur au du ce tout", "œêûî"},
    {"deu", "der die das und ich du nicht ist ein eine mit mich mir dich dir wir auf für sie zu den "
            "dem im auch noch nur wenn aber mein dein kein bin hab war", "ßä"},
    {"ita", "il lo gli che di e è non per con mi ti sono sei io del della nel ma più come cosa "
            "amore cuore perché questo quando ancora anche sempre", "ì"},
    {"cat", "els les que de i és per amb em et jo del dels al als però més tot quan ara sóc ets "
            "meu teu molt aquest perquè", "·"},
    {"nld", "de het een en ik je jij niet is van dat die op met voor mijn jouw zijn wat maar ook "
            "nog wel als er naar bij hou ben heb ze", "ĳ"},
    {"tur", "ve bir bu ben sen ne ki için gibi çok beni seni var yok değil mi ama her şey aşk "
            "sana bana benim senin daha", "ğış"},
}};

std::u32string decode(std::string_view text) {
  std::u32string out;
  out.reserve(text.size());
  for (size_t i = 0; i < text.size();) {
    const auto lead = static_cast<unsigned char>(text[i]);
    const int extra = lead < 0x80 ? 0 : lead < 0xe0 ? 1 : lead < 0xf0 ? 2 : 3;
    char32_t cp = extra == 0 ? lead : lead & (0x3f >> extra);
    size_t k = 1;
    for (; k <= size_t(extra) && i + k < text.size(); ++k)
      cp = (cp << 6) | (static_cast<unsigned char>(text[i + k]) & 0x3f);
    out += cp;
    i += k;
  }
  return out;
}

bool isLatinLetter(char32_t c) {
  return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == 0xaa || c == 0xba ||
         (c >= 0xc0 && c <= 0x24f && c != 0xd7 && c != 0xf7) || (c >= 0x250 && c <= 0x2af) ||
         (c >= 0x1e00 && c <= 0x1eff) || (c >= 0x2c60 && c <= 0x2c7f) || (c >= 0xa720 && c <= 0xa7ff) ||
         (c >= 0xff21 && c <= 0xff3a) || (c >= 0xff41 && c <= 0xff5a);
}

// Letters of any script; punctuation, symbol, and emoji blocks split words.
bool isLetter(char32_t c) {
  if (isLatinLetter(c))
    return true;
  if (c < 0x370 || (c >= 0x2000 && c <= 0x2bff) || (c >= 0x3000 && c <= 0x303f) || (c >= 0xfe30 && c <= 0xfe4f) ||
      (c >= 0xff00 && c <= 0xff20) || c >= 0x1f000)
    return false;
  return true;
}

// Lowercase for the Latin blocks the word lists use; other scripts never reach the word scorer.
char32_t lower(char32_t c) {
  if ((c >= 'A' && c <= 'Z') || (c >= 0xc0 && c <= 0xde && c != 0xd7))
    return c + 0x20;
  if (c == 0x130)
    return 'i';
  if (c == 0x178)
    return 0xff;
  // Latin Extended-A and Additional pair upper/lower as even/odd, with a shifted run at 0x139..0x148.
  if ((c >= 0x100 && c <= 0x137) || (c >= 0x14a && c <= 0x177) || (c >= 0x1e00 && c <= 0x1eff))
    return c % 2 == 0 ? c + 1 : c;
  if ((c >= 0x139 && c <= 0x148) || (c >= 0x179 && c <= 0x17e))
    return c % 2 == 1 ? c + 1 : c;
  return c;
}

const std::map<std::u32string, std::vector<const char *>> &wordSets() {
  // Word -> languages that use it.
  static const auto sets = [] {
    std::map<std::u32string, std::vector<const char *>> out;
    for (const auto &language : kLatin) {
      const std::u32string words = decode(language.words);
      size_t start = 0;
      while (start < words.size()) {
        size_t end = words.find(U' ', start);
        if (end == std::u32string::npos)
          end = words.size();
        if (end > start)
          out[words.substr(start, end - start)].push_back(language.code);
        start = end + 1;
      }
    }
    return out;
  }();
  return sets;
}

struct Scripts {
  int hangul{0}, kana{0}, han{0}, cyrillic{0}, greek{0}, arabic{0}, latin{0};
};

Scripts countScripts(const std::u32string &text) {
  Scripts s;
  for (const char32_t u : text) {
    if ((u >= 0xac00 && u <= 0xd7a3) || (u >= 0x1100 && u <= 0x11ff) || (u >= 0x3130 && u <= 0x318f))
      ++s.hangul;
    else if (u >= 0x3040 && u <= 0x30ff)
      ++s.kana;
    else if (u >= 0x4e00 && u <= 0x9fff)
      ++s.han;
    else if (u >= 0x0400 && u <= 0x04ff)
      ++s.cyrillic;
    else if (u >= 0x0370 && u <= 0x03ff)
      ++s.greek;
    else if (u >= 0x0600 && u <= 0x06ff)
      ++s.arabic;
    else if (isLatinLetter(u))
      ++s.latin;
  }
  return s;
}

std::string detectLatin(const std::u32string &text) {
  std::u32string lowered = text;
  std::transform(lowered.begin(), lowered.end(), lowered.begin(), lower);
  std::map<std::string, int> scores;
  std::u32string word;
  const auto score = [&] {
    if (word.empty())
      return;
    const auto it = wordSets().find(word);
    if (it != wordSets().end())
      for (const char *code : it->second)
        scores[code] += 1;
    word.clear();
  };
  for (const char32_t c : lowered) {
    if (c == 0x2019 || c == '\'')
      word += U'\'';
    else if (c == 0xb7 || isLetter(c))
      word += c;
    else
      score();
  }
  score();
  for (const auto &language : kLatin) {
    for (const char32_t mark : decode(language.marks))
      if (lowered.find(mark) != std::u32string::npos)
        scores[language.code] += 2;
  }
  std::string best;
  int bestScore = 0;
  bool tie = false;
  for (const auto &[code, value] : scores) {
    if (value > bestScore) {
      best = code;
      bestScore = value;
      tie = false;
    } else if (value == bestScore) {
      tie = true;
    }
  }
  return tie ? std::string() : best;
}

bool isLatinCode(const std::string &code) {
  return std::any_of(kLatin.begin(), kLatin.end(), [&](const LatinLanguage &l) { return code == l.code; });
}

std::string detect(const std::u32string &text) {
  const Scripts s = countScripts(text);
  // Any Hangul or kana marks the line, even beside English words: "Real bad 걔는" is Korean.
  if (s.hangul)
    return "kor";
  if (s.kana)
    return "jpn";
  if (s.han)
    return "zho";
  if (s.cyrillic)
    return "rus";
  if (s.greek)
    return "ell";
  if (s.arabic)
    return "ara";
  return s.latin ? detectLatin(text) : std::string();
}

} // namespace

std::string detectLine(std::string_view text) { return detect(decode(text)); }

SongPlan plan(const std::vector<std::string> &lines) {
  std::vector<std::u32string> decoded;
  std::vector<std::string> detected;
  decoded.reserve(lines.size());
  detected.reserve(lines.size());
  // Ordered by first appearance so equal counts settle the same way every time.
  std::vector<std::pair<std::string, int>> counts;
  const auto count = [&counts](const std::string &code) -> int & {
    for (auto &entry : counts)
      if (entry.first == code)
        return entry.second;
    return counts.emplace_back(code, 0).second;
  };
  int voiced = 0;
  for (const std::string &line : lines) {
    decoded.push_back(decode(line));
    detected.push_back(detect(decoded.back()));
    if (!detected.back().empty()) {
      ++voiced;
      ++count(detected.back());
    }
  }
  // Japanese lyrics often have kanji-only lines; kana elsewhere claims them.
  const auto zho = std::find_if(counts.begin(), counts.end(), [](const auto &e) { return e.first == "zho"; });
  const bool hasJapanese =
      std::any_of(counts.begin(), counts.end(), [](const auto &e) { return e.first == "jpn" && e.second > 0; });
  if (hasJapanese && zho != counts.end()) {
    const int han = zho->second;
    counts.erase(zho);
    count("jpn") += han;
    for (std::string &code : detected)
      if (code == "zho")
        code = "jpn";
  }

  SongPlan out;
  int bestCount = 0;
  for (const auto &[code, value] : counts) {
    if (code != "eng" && value > bestCount) {
      out.source = code;
      bestCount = value;
    }
  }
  // One stray line is a misread or a quote, unless it is most of the song.
  if (bestCount < 2 && bestCount * 2 <= voiced)
    out.source.clear();

  const bool latinSource = isLatinCode(out.source);
  out.translate.reserve(lines.size());
  for (size_t i = 0; i < lines.size(); ++i) {
    const std::string &code = detected[i];
    // Short Latin lines with no telling words lean on the song's language.
    const bool unclearLatin = code.empty() && latinSource && countScripts(decoded[i]).latin > 0;
    out.translate.push_back(!out.source.empty() && (code == out.source || unclearLatin));
  }
  return out;
}

std::string displayName(std::string_view code) {
  static constexpr std::pair<std::string_view, std::string_view> kNames[] = {
      {"eng", "English"}, {"kor", "Korean"},  {"jpn", "Japanese"}, {"zho", "Chinese"},  {"rus", "Russian"},
      {"ell", "Greek"},   {"ara", "Arabic"},  {"tur", "Turkish"},  {"fra", "French"},   {"deu", "German"},
      {"spa", "Spanish"}, {"ita", "Italian"}, {"nld", "Dutch"},    {"cat", "Catalan"},  {"por", "Portuguese"},
  };
  for (const auto &[key, name] : kNames)
    if (key == code)
      return std::string(name);
  return std::string(code);
}

} // namespace lyric_language
