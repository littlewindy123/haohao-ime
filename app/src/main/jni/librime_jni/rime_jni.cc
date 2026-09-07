// SPDX-FileCopyrightText: 2015 - 2024 Rime community
// SPDX-FileCopyrightText: 2026 HaoHao IME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later

#include <rime/component.h>
#include <rime/context.h>
#include <rime/dict/corrector.h>
#include <rime/dict/dictionary.h>
#include <rime/engine.h>
#include <rime/gear/poet.h>
#include <rime/gear/script_translator.h>
#include <rime/registry.h>
#include <rime/schema.h>
#include <rime/service.h>
#include <rime_api.h>

#include <algorithm>
#include <cstdlib>
#include <memory>
#include <set>
#include <string>
#include <tuple>
#include <unordered_map>
#include <vector>

#include "frontend.h"
#include "jni-utils.h"
#include "objconv.h"
#include "session.h"

#define MAX_BUFFER_LENGTH 2048

namespace {

constexpr const char *kHaoHaoNoPersonalizedLearning =
    "_haohao_no_personalized_learning";

// Keep the engine's raw digit input intact, while displaying the selected
// phrase's pinyin.
class NineKeyTranslation final : public rime::Translation {
 public:
  NineKeyTranslation(rime::an<rime::Translation> source,
                     rime::ScriptTranslator *translator)
      : source_(std::move(source)), translator_(translator) {
    set_exhausted(source_->exhausted());
  }
  bool Next() override {
    const bool next = source_->Next();
    set_exhausted(source_->exhausted());
    return next;
  }
  rime::an<rime::Candidate> Peek() override {
    if (source_->exhausted()) return nullptr;
    auto candidate = source_->Peek();
    if (auto phrase = std::dynamic_pointer_cast<rime::Phrase>(candidate)) {
      const auto spelling = translator_->Spell(phrase->code());
      if (!spelling.empty())
        phrase->set_preedit(translator_->FormatPreedit(spelling));
    }
    return candidate;
  }

 private:
  rime::an<rime::Translation> source_;
  rime::ScriptTranslator *translator_;
};

// A bounded, single-edit search of the final syllable. It consumes the complete
// remaining suffix, so a corrected edge cannot lead to another corrected edge.
class HaoHaoCorrector final : public rime::Corrector {
 public:
  explicit HaoHaoCorrector(bool nine_key) : nine_key_(nine_key) {}

  void ToleranceSearch(const rime::Prism &prism, const std::string &key,
                       rime::corrector::Corrections *results,
                       size_t tolerance) override {
    if (!tolerance || key.size() < 2 || key.size() > 7) return;
    const std::string alphabet =
        nine_key_ ? "23456789" : "abcdefghijklmnopqrstuvwxyz";
    if (key.find_first_not_of(alphabet) != std::string::npos) return;
    auto add = [&](const std::string &candidate) {
      if (candidate == key || candidate.size() < 2 || candidate.size() > 6)
        return;
      rime::SyllableId id;
      if (prism.GetValue(candidate, &id))
        results->Alter(id, {1, id, key.size()});
    };
    for (size_t i = 0; i < key.size(); ++i) {
      auto candidate = key;
      candidate.erase(i, 1);
      add(candidate);
      if (i + 1 < key.size()) {
        candidate = key;
        std::swap(candidate[i], candidate[i + 1]);
        add(candidate);
      }
      for (char replacement : alphabet) {
        if (!Adjacent(key[i], replacement)) continue;
        candidate = key;
        candidate[i] = replacement;
        add(candidate);
      }
    }
    for (size_t i = 0; i <= key.size(); ++i) {
      for (char missing : alphabet) {
        auto candidate = key;
        candidate.insert(i, 1, missing);
        add(candidate);
      }
    }
  }

 private:
  bool Adjacent(char a, char b) const {
    if (a == b) return false;
    if (nine_key_) {
      const int left = a - '1', right = b - '1';
      return std::abs(left / 3 - right / 3) + std::abs(left % 3 - right % 3) ==
             1;
    }
    const std::string rows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    const int offsets[] = {0, 1, 3};  // quarter-key offsets
    int ax = 0, ay = 0, bx = 0, by = 0;
    for (int row = 0; row < 3; ++row) {
      auto pos = rows[row].find(a);
      if (pos != std::string::npos) {
        ax = 4 * pos + offsets[row];
        ay = row;
      }
      pos = rows[row].find(b);
      if (pos != std::string::npos) {
        bx = 4 * pos + offsets[row];
        by = row;
      }
    }
    return std::abs(ay - by) <= 1 && std::abs(ax - bx) <= 4;
  }
  bool nine_key_;
};

// A correction is decoded separately, never written back into Context::input.
// Map its native syllable/word spans back to the original keystrokes so that
// selecting, learning and backspacing keep using the real composition offsets.
class CorrectionSpans final : public rime::PhraseSyllabifier {
 public:
  explicit CorrectionSpans(rime::Spans spans) : spans_(std::move(spans)) {}
  rime::Spans Syllabify(const rime::Phrase *) override { return spans_; }

 private:
  rime::Spans spans_;
};

class SentenceSpellings {
 public:
  struct Variant {
    std::string text;
    size_t edit;
    int priority;
    int syllables;
  };
  explicit SentenceSpellings(rime::Prism &prism) : prism_(prism) {}

  std::vector<Variant> Search(const std::string &input, size_t suspect) {
    std::vector<Variant> variants;
    std::set<std::string> seen;
    auto add = [&](std::string text, size_t edit, int priority) {
      if (text == input || !seen.insert(text).second) return;
      const int count = Syllables(text);
      if (count < kInvalid)
        variants.push_back({std::move(text), edit, priority, count});
    };
    // The first disagreement with the original phrase localizes the ambiguous
    // syllable. Don't explore unrelated edits throughout a long sentence.
    const size_t begin = suspect > 6 ? suspect - 6 : 0;
    const size_t end = std::min(input.size(), suspect + 6);
    for (size_t i = begin; i < end; ++i) {
      auto text = input;
      text.erase(i, 1);
      const bool repeated = (i && input[i - 1] == input[i]) ||
                            (i + 1 < input.size() && input[i + 1] == input[i]);
      add(text, i, repeated ? 0 : 3);
      if (i + 1 < input.size()) {
        text = input;
        std::swap(text[i], text[i + 1]);
        add(text, i, 1);
      }
      for (char c = 'a'; c <= 'z'; ++c) {
        if (!Adjacent(input[i], c)) continue;
        text = input;
        text[i] = c;
        add(text, i, 2);
      }
    }
    for (size_t i = begin; i <= end; ++i) {
      for (char c = 'a'; c <= 'z'; ++c) {
        auto text = input;
        text.insert(i, 1, c);
        add(text, i, 3);
      }
    }
    std::stable_sort(
        variants.begin(), variants.end(), [](const auto &a, const auto &b) {
          return std::tie(a.syllables, a.priority, a.edit, a.text) <
                 std::tie(b.syllables, b.priority, b.edit, b.text);
        });
    if (variants.size() > 4) variants.resize(4);
    return variants;
  }

 private:
  static constexpr int kInvalid = 1000;
  rime::Prism &prism_;
  std::unordered_map<std::string, int> memo_;

  int Syllables(const std::string &input) {
    if (input.empty()) return 0;
    if (const auto it = memo_.find(input); it != memo_.end()) return it->second;
    if (memo_.size() >= 16000) return kInvalid;
    int best = kInvalid;
    std::vector<rime::Prism::Match> matches;
    prism_.CommonPrefixSearch(input, &matches);
    for (const auto &match : matches) {
      if (!match.length || match.length > 6) continue;
      bool normal = false;
      for (auto spelling = prism_.QuerySpelling(match.value);
           !spelling.exhausted(); spelling.Next()) {
        if (spelling.properties().type == rime::kNormalSpelling) {
          normal = true;
          break;
        }
      }
      if (normal)
        best = std::min(best, 1 + Syllables(input.substr(match.length)));
    }
    memo_.emplace(input, best);
    return best;
  }

  static bool Adjacent(char a, char b) {
    if (a == b) return false;
    const std::string rows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    const int offsets[] = {0, 1, 3};
    int ax = 0, ay = 0, bx = 0, by = 0;
    for (int row = 0; row < 3; ++row) {
      auto pos = rows[row].find(a);
      if (pos != std::string::npos) {
        ax = 4 * pos + offsets[row];
        ay = row;
      }
      pos = rows[row].find(b);
      if (pos != std::string::npos) {
        bx = 4 * pos + offsets[row];
        by = row;
      }
    }
    return std::abs(ay - by) <= 1 && std::abs(ax - bx) <= 4;
  }
};

class HaoHaoScriptTranslator final : public rime::ScriptTranslator {
 public:
  using ScriptTranslator::ScriptTranslator;

  std::string FullSpelling(const rime::an<rime::Candidate> &candidate) {
    auto phrase = std::dynamic_pointer_cast<rime::Phrase>(
        rime::Candidate::GetGenuineCandidate(candidate));
    if (!phrase) return {};
    auto spelling = Spell(phrase->code());
    spelling.erase(std::remove(spelling.begin(), spelling.end(), '\''),
                   spelling.end());
    spelling.erase(std::remove(spelling.begin(), spelling.end(), ' '),
                   spelling.end());
    return spelling;
  }

  rime::an<rime::Phrase> MapCorrection(
      const rime::an<rime::Phrase> &original,
      const SentenceSpellings::Variant &variant, const std::string &input,
      const rime::Segment &segment) {
    auto position = [&](size_t pos) {
      const size_t local = pos - segment.start;
      if (local == variant.text.size()) return segment.end;
      if (local <= variant.edit) return pos;
      if (variant.text.size() > input.size()) return pos - 1;
      if (variant.text.size() < input.size()) return pos + 1;
      return pos;
    };
    rime::Spans spans;
    const auto old_spans = original->spans();
    for (size_t pos = original->start(); pos <= original->end(); ++pos)
      if (old_spans.HasVertex(pos)) spans.AddVertex(position(pos));
    rime::an<rime::Phrase> mapped;
    if (auto sentence = std::dynamic_pointer_cast<rime::Sentence>(original)) {
      auto copy = std::make_shared<rime::Sentence>(sentence->language());
      size_t end = segment.start;
      for (size_t i = 0; i < sentence->components().size(); ++i) {
        end += sentence->word_lengths()[i];
        copy->Extend(sentence->components()[i], position(end) - segment.start,
                     sentence->weight());
      }
      copy->Offset(segment.start);
      mapped = copy;
    } else {
      mapped = std::make_shared<rime::Phrase>(
          original->language(), original->type(), segment.start, segment.end,
          std::make_shared<rime::DictEntry>(original->entry()));
    }
    mapped->set_syllabifier(std::make_shared<CorrectionSpans>(spans));
    mapped->set_quality(original->quality());
    mapped->set_preedit(input);
    return mapped;
  }

  rime::an<rime::Translation> SentenceCorrections(
      const std::string &input, const rime::Segment &segment,
      rime::an<rime::Translation> source) {
    if (!source || source->exhausted() || !source->Peek() || !dict_ ||
        !dict_->prism())
      return source;
    auto leader = source->Peek();
    const auto spelling = FullSpelling(leader);
    // Preserve exact input and explicit abbreviation/proper-name choices.
    // Only fallback decodings with a spelling mismatch enter this path.
    if (spelling.empty() || (leader->end() == segment.end &&
                             spelling.compare(0, input.size(), input) == 0))
      return source;
    size_t suspect = 0;
    while (suspect < input.size() && suspect < spelling.size() &&
           input[suspect] == spelling[suspect])
      ++suspect;
    const auto variants =
        SentenceSpellings(*dict_->prism()).Search(input, suspect);
    std::vector<rime::an<rime::Phrase>> corrections;
    std::set<std::string> seen;
    for (const auto &variant : variants) {
      auto target = segment;
      target.end = segment.start + variant.text.size();
      auto result = ScriptTranslator::Query(variant.text, target);
      if (!result || result->exhausted()) continue;
      const auto candidate = result->Peek();
      // No completions, abbreviated paths or cascaded edits in the result.
      if (!candidate || candidate->end() != target.end ||
          FullSpelling(candidate) != variant.text ||
          candidate->text() == leader->text())
        continue;
      auto phrase = std::dynamic_pointer_cast<rime::Phrase>(
          rime::Candidate::GetGenuineCandidate(candidate));
      if (phrase && seen.insert(phrase->text()).second)
        corrections.push_back(MapCorrection(phrase, variant, input, segment));
    }
    if (corrections.empty()) return source;
    // Word/sentence frequency from the existing local dictionary breaks ties;
    // this is not a semantic language model and never uses the network.
    std::stable_sort(
        corrections.begin(), corrections.end(),
        [](const auto &a, const auto &b) { return a->weight() > b->weight(); });
    auto prefix = std::make_shared<rime::FifoTranslation>();
    // Keep one useful correction, not a row of speculative sentences.
    prefix->Append(corrections.front());
    auto combined = std::make_shared<rime::UnionTranslation>();
    *combined += prefix;
    *combined += source;
    return combined;
  }

  rime::an<rime::Translation> Query(const std::string &input,
                                    const rime::Segment &segment) override {
    const bool nine = engine_->schema()->schema_id() == "haohao_pinyin_9";
    const bool correct =
        engine_->context()->get_option("_haohao_smart_correction") &&
        input.size() >= 4 && input.size() <= (nine ? 32 : 96) &&
        (nine || input.find_first_not_of("abcdefghijklmnopqrstuvwxyz") ==
                     std::string::npos) &&
        engine_->context()->caret_pos() == engine_->context()->input().size();
    enable_correction_ = false;
    corrector_.reset();
    auto source = ScriptTranslator::Query(input, segment);
    const bool short_exact = !nine && correct && input.size() <= 7 && source &&
                             !source->exhausted() && source->Peek() &&
                             FullSpelling(source->Peek()) == input;
    if (correct && !nine && !short_exact)
      return SentenceCorrections(input, segment, source);
    if (correct) {
      auto prefix = std::make_shared<rime::FifoTranslation>();
      std::set<std::string> seen;
      // Preserve the existing leaders even when an unrelated frequent word has
      // a high correction score. Native Phrase objects retain commit/learning
      // semantics.
      // Simplification/deduplication can merge several native candidates into
      // one visible item. Keep a wider original window for exact short input.
      const int preserve = short_exact && !nine ? 16 : 4;
      for (int i = 0; i < preserve && source && !source->exhausted(); ++i) {
        auto candidate = source->Peek();
        if (!candidate) break;
        seen.insert(candidate->text());
        prefix->Append(candidate);
        source->Next();
      }
      enable_correction_ = true;
      corrector_.reset(new HaoHaoCorrector(nine));
      auto corrected = ScriptTranslator::Query(input, segment);
      int added = 0;
      for (int scanned = 0;
           scanned < 32 && added < 4 && corrected && !corrected->exhausted();
           ++scanned) {
        auto candidate = corrected->Peek();
        if (!candidate) break;
        if (candidate->end() == segment.end &&
            seen.insert(candidate->text()).second) {
          prefix->Append(candidate);
          ++added;
        }
        corrected->Next();
      }
      auto combined = std::make_shared<rime::UnionTranslation>();
      *combined += prefix;
      if (source) *combined += source;
      source = combined;
    }
    if (source && engine_->schema()->schema_id() == "haohao_pinyin_9") {
      return std::make_shared<NineKeyTranslation>(source, this);
    }
    return source;
  }

  bool Memorize(const rime::CommitEntry &commit_entry) override {
    if (!engine_ ||
        engine_->context()->get_option(kHaoHaoNoPersonalizedLearning)) {
      return false;
    }
    return ScriptTranslator::Memorize(commit_entry);
  }
};

void register_haohao_components() {
  rime::Registry::instance().Register(
      "haohao_script_translator", new rime::Component<HaoHaoScriptTranslator>);
}

}  // namespace

extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void rime_require_module_predict();
// librime is compiled as a static library, we have to link modules explicitly
static void declare_librime_module_dependencies() {
  rime_require_module_lua();
  rime_require_module_octagram();
  rime_require_module_predict();
}

class Rime {
 public:
  Rime() : rime(rime_get_api()) {}
  Rime(Rime const &) = delete;
  void operator=(Rime const &) = delete;

  static Rime &Instance() {
    static Rime instance;
    return instance;
  }

  bool startup(bool fullCheck,
               const RimeNotificationHandler &notificationHandler) {
    if (!rime) return false;
    const char *userDir = getenv("RIME_USER_DATA_DIR");
    const char *sharedDir = getenv("RIME_SHARED_DATA_DIR");
    const char *versionName = getenv("RIME_DISTRIBUTION_VERSION");

    RIME_STRUCT(RimeTraits, trime_traits)
    trime_traits.shared_data_dir = sharedDir;
    trime_traits.user_data_dir = userDir;
    trime_traits.log_dir = "";  // set empty log_dir to log to logcat only
    trime_traits.app_name = "rime.trime";
    trime_traits.distribution_name = "Trime";
    trime_traits.distribution_code_name = "trime";
    trime_traits.distribution_version = versionName;

    rime->setup(&trime_traits);
    rime->initialize(&trime_traits);
    register_haohao_components();
    rime->set_notification_handler(notificationHandler, GlobalRef->jvm);
    return rime->start_maintenance(fullCheck);
  }

  void joinMaintenanceThread() { rime->join_maintenance_thread(); }

  bool deploySchema(std::string_view schemaFile) {
    return rime->deploy_schema(schemaFile.data());
  }

  bool deployConfigFile(std::string_view configFile,
                        std::string_view versionKey) {
    return rime->deploy_config_file(configFile.data(), versionKey.data());
  }

  bool processKey(int keycode, int mask) {
    return rime->process_key(session(), keycode, mask);
  }

  bool simulateKeySequence(const std::string &sequence) {
    return rime->simulate_key_sequence(session(), sequence.data());
  }

  bool commitComposition() { return rime->commit_composition(session()); }

  void clearComposition() { rime->clear_composition(session()); }

  size_t nineKeyStart(rime::Context *context) {
    return context->input().find_first_of(
        "23456789", context->composition().GetConfirmedPosition());
  }

  std::string nineKeyInput() {
    auto s = rime::Service::instance().GetSession(session());
    if (!s) return "";
    auto ctx = s->context();
    auto start = nineKeyStart(ctx);
    return start == std::string::npos ? "" : ctx->input().substr(start);
  }

  bool filterNineKeyInput(const std::string &syllable,
                          const std::string &digits,
                          const std::string &expected) {
    auto s = rime::Service::instance().GetSession(session());
    if (!s) return false;
    auto ctx = s->context();
    auto start = nineKeyStart(ctx);
    if (start == std::string::npos || expected.empty() ||
        ctx->input().substr(start) != expected ||
        expected.compare(0, digits.size(), digits) != 0)
      return false;
    auto input = ctx->input();
    auto replacement = syllable;
    const auto end = start + digits.size();
    if (end < input.size() && input[end] != '\'') replacement += '\'';
    input.replace(start, digits.size(), replacement);
    // Context::set_input preserves confirmed segments in the unchanged prefix.
    ctx->set_input(input);
    ctx->set_caret_pos(input.size());
    return true;
  }

  std::unique_ptr<CommitProto> commit() {
    RIME_STRUCT(RimeCommit, data)
    if (rime->get_commit(session(), &data)) {
      auto p = std::make_unique<CommitProto>(&data);
      rime->free_commit(&data);
      return p;
    }
    return std::make_unique<CommitProto>();
  }

  std::unique_ptr<ContextProto> context(bool includeMenu = true) {
    RIME_STRUCT(RimeContext, data)
    auto s = session();
    if (rime->get_context(s, &data)) {
      auto input = rime->get_input(s);
      auto caretPos = rime->get_caret_pos(s);
      auto p =
          std::make_unique<ContextProto>(&data, input, caretPos, includeMenu);
      rime->free_context(&data);
      return p;
    }
    return std::make_unique<ContextProto>();
  }

  std::unique_ptr<StatusProto> status() {
    RIME_STRUCT(RimeStatus, data)
    if (rime->get_status(session(), &data)) {
      auto p = std::make_unique<StatusProto>(&data);
      rime->free_status(&data);
      return p;
    }
    return std::make_unique<StatusProto>();
  }

  void setOption(std::string_view key, bool value) {
    rime->set_option(session(), key.data(), value);
  }

  bool getOption(std::string_view key) {
    return rime->get_option(session(), key.data());
  }

  std::string currentSchemaId() {
    char result[MAX_BUFFER_LENGTH];
    return rime->get_current_schema(session(), result, MAX_BUFFER_LENGTH)
               ? result
               : "";
  }

  std::vector<SchemaItem> schemaList() {
    std::vector<SchemaItem> result;
    RimeSchemaList list{};
    if (rime->get_schema_list(&list)) {
      result = SchemaItem::fromCList(list);
      rime->free_schema_list(&list);
    }
    return std::move(result);
  }

  bool selectSchema(std::string_view schemaId) {
    return rime->select_schema(session(), schemaId.data());
  }

  std::string rawInput() {
    auto cStr = rime->get_input(session());
    return cStr ? cStr : "";
  }

  size_t caretPosition() { return rime->get_caret_pos(session()); }

  void setCaretPosition(size_t caretPos) {
    rime->set_caret_pos(session(), caretPos);
  }

  bool selectCandidate(size_t index, bool global) {
    if (global) {
      return rime->select_candidate(session(), index);
    } else {
      return rime->select_candidate_on_current_page(session(), index);
    }
  }

  bool deleteCandidate(size_t index, bool global) {
    if (global) {
      return rime->delete_candidate(session(), index);
    } else {
      return rime->delete_candidate_on_current_page(session(), index);
    }
  }

  bool changePage(bool backward) {
    return rime->change_page(session(), backward);
  }

  std::vector<CandidateProto> getCandidates(int startIndex, int limit) {
    std::vector<CandidateProto> result;
    result.reserve(limit);
    RimeCandidateListIterator iter{};
    if (rime->candidate_list_from_index(session(), &iter, startIndex)) {
      int count = 0;
      while (rime->candidate_list_next(&iter)) {
        if (count >= limit) break;
        result.emplace_back(iter.candidate);
        ++count;
      }
      rime->candidate_list_end(&iter);
    }
    return std::move(result);
  }

  std::tuple<int, int, std::vector<CandidateProto>> getBulkCandidates() {
    constexpr int limit = 16;
    auto list = getCandidates(0, limit);
    // use -1 to indicate it's not sure how many candidates now
    auto size = list.size() < limit ? list.size() : -1;
    auto highlighted = rime_get_highlighted_candidate_index(session());
    return std::make_tuple(size, highlighted, std::move(list));
  }

  void exit() {
    session_.reset();
    rime->finalize();
  }

  bool sync() {
    session_.reset();
    return rime->sync_user_data();
  }

 private:
  RimeApi *rime;
  std::shared_ptr<SessionHolder> session_;

  RimeSessionId session(bool requestNewSession = true) {
    if (!session_ && requestNewSession) {
      try {
        auto newSession = std::make_shared<SessionHolder>();
        session_ = newSession;
      } catch (...) {
        session_ = nullptr;
      }
    }
    if (!session_) {
      return 0;
    }
    return session_->id();
  }
};

GlobalRefSingleton *GlobalRef;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
  GlobalRef = new GlobalRefSingleton(jvm);
  declare_librime_module_dependencies();
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_startupRime(JNIEnv *env, jclass clazz,
                                            jstring shared_dir,
                                            jstring user_dir,
                                            jstring version_name,
                                            jboolean full_check) {
  // for rime shared data dir
  setenv("RIME_SHARED_DATA_DIR", CString(env, shared_dir), 1);
  // for rime user data dir
  setenv("RIME_USER_DATA_DIR", CString(env, user_dir), 1);
  setenv("RIME_DISTRIBUTION_VERSION", CString(env, version_name), 1);

  auto notificationHandler = [](void *context_object, RimeSessionId session_id,
                                const char *message_type,
                                const char *message_value) {
    auto env = GlobalRef->AttachEnv();
    int type = 0;  // unknown
    if (strcmp(message_type, "schema") == 0) {
      type = 1;
    } else if (strcmp(message_type, "option") == 0) {
      type = 2;
    } else if (strcmp(message_type, "deploy") == 0) {
      type = 3;
    }
    auto vararg = JRef<jobjectArray>(
        env, env->NewObjectArray(1, GlobalRef->Object, nullptr));
    env->SetObjectArrayElement(vararg, 0, JString(env, message_value));
    env->CallStaticVoidMethod(GlobalRef->Rime, GlobalRef->HandleRimeMessage,
                              type, *vararg);
  };

  return Rime::Instance().startup(full_check, notificationHandler);
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_core_Rime_joinRimeMaintenanceThread(JNIEnv *env,
                                                          jclass /* thiz */) {
  Rime::Instance().joinMaintenanceThread();
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_core_Rime_exitRime(JNIEnv *env, jclass /* thiz */) {
  Rime::Instance().exit();
}

// deployment
extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_deployRimeSchemaFile(JNIEnv *env,
                                                     jclass /* thiz */,
                                                     jstring schema_file) {
  return Rime::Instance().deploySchema(*CString(env, schema_file));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_deployRimeConfigFile(JNIEnv *env,
                                                     jclass /* thiz */,
                                                     jstring file_name,
                                                     jstring version_key) {
  return Rime::Instance().deployConfigFile(*CString(env, file_name),
                                           *CString(env, version_key));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_syncRimeUserData(JNIEnv *env,
                                                 jclass /* thiz */) {
  return Rime::Instance().sync();
}

// input
extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_processRimeKey(JNIEnv *env, jclass /* thiz */,
                                               jint keycode, jint mask) {
  return Rime::Instance().processKey(keycode, mask);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_commitRimeComposition(JNIEnv *env,
                                                      jclass /* thiz */) {
  return Rime::Instance().commitComposition();
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_core_Rime_clearRimeComposition(JNIEnv *env,
                                                     jclass /* thiz */) {
  Rime::Instance().clearComposition();
}

// output
extern "C" JNIEXPORT jobject JNICALL
Java_com_osfans_trime_core_Rime_getRimeCommit(JNIEnv *env, jclass /* thiz */) {
  auto commit = Rime::Instance().commit();
  return rimeCommitToJObject(env, *commit);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_osfans_trime_core_Rime_getRimeContext(JNIEnv *env, jclass /* thiz */) {
  auto context = Rime::Instance().context();
  return rimeContextToJObject(env, *context);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_osfans_trime_core_Rime_getRimeStatus(JNIEnv *env, jclass /* thiz */) {
  auto status = Rime::Instance().status();
  return rimeStatusToJObject(env, *status);
}

// runtime options
extern "C" JNIEXPORT void JNICALL Java_com_osfans_trime_core_Rime_setRimeOption(
    JNIEnv *env, jclass /* thiz */, jstring option, jboolean value) {
  Rime::Instance().setOption(*CString(env, option), value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_getRimeOption(JNIEnv *env, jclass /* thiz */,
                                              jstring option) {
  return Rime::Instance().getOption(*CString(env, option));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_osfans_trime_core_Rime_getRimeSchemaList(JNIEnv *env,
                                                  jclass /* thiz */) {
  return rimeSchemaListToJObjectArray(env, Rime::Instance().schemaList());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_core_Rime_getCurrentRimeSchema(JNIEnv *env,
                                                     jclass /* thiz */) {
  return env->NewStringUTF(Rime::Instance().currentSchemaId().c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_selectRimeSchema(JNIEnv *env, jclass /* thiz */,
                                                 jstring schema_id) {
  return Rime::Instance().selectSchema(*CString(env, schema_id));
}

// testing
extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_simulateRimeKeySequence(JNIEnv *env,
                                                        jclass /* thiz */,
                                                        jstring key_sequence) {
  return Rime::Instance().simulateKeySequence(CString(env, key_sequence));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_core_Rime_getRimeRawInput(JNIEnv *env,
                                                jclass /* thiz */) {
  return env->NewStringUTF(Rime::Instance().rawInput().data());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_core_Rime_getRimeNineKeyInput(JNIEnv *env, jclass) {
  return env->NewStringUTF(Rime::Instance().nineKeyInput().c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_filterRimeNineKeyInput(JNIEnv *env, jclass,
                                                       jstring syllable,
                                                       jstring digits,
                                                       jstring expected) {
  return Rime::Instance().filterNineKeyInput(
      CString(env, syllable), CString(env, digits), CString(env, expected));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_osfans_trime_core_Rime_getRimeCaretPos(JNIEnv *env,
                                                jclass /* thiz */) {
  return static_cast<jint>(Rime::Instance().caretPosition());
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_core_Rime_setRimeCaretPos(JNIEnv *env, jclass /* thiz */,
                                                jint caret_pos) {
  Rime::Instance().setCaretPosition(caret_pos);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_selectRimeCandidate(JNIEnv *env,
                                                    jclass /* thiz */,
                                                    jint index,
                                                    jboolean global) {
  return Rime::Instance().selectCandidate(index, global);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_deleteRimeCandidate(JNIEnv *env,
                                                    jclass /* thiz */,
                                                    jint index,
                                                    jboolean global) {
  return Rime::Instance().deleteCandidate(index, global);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_changeRimeCandidatePage(JNIEnv *env,
                                                        jclass clazz,
                                                        jboolean backward) {
  return Rime::Instance().changePage(backward);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_osfans_trime_core_Rime_getRimeCandidates(JNIEnv *env, jclass clazz,
                                                  jint start_index,
                                                  jint limit) {
  return rimeCandidateListToJObjectArray(
      env, Rime::Instance().getCandidates(start_index, limit));
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_osfans_trime_core_Rime_getRimeResponse(JNIEnv *env, jclass clazz,
                                                jboolean paging_mode) {
  auto commit = Rime::Instance().commit();
  // the menu is only needed in paging mode, otherwise its candidates would be
  // duplicated by the bulk candidates query below
  auto context = Rime::Instance().context(paging_mode);
  auto status = Rime::Instance().status();
  auto jCommit = JRef(env, rimeCommitToJObject(env, *commit));
  auto jComposition =
      JRef(env, rimeCompositionToJObject(env, context->composition));
  auto jStatus = JRef(env, rimeStatusToJObject(env, *status));
  // keep the local references alive until RimeResponse is constructed below
  jobject jCandidates = nullptr;
  if (paging_mode) {
    // the candidate layout is queried right where the page is built, so the
    // consumer does not need a separate rime option round-trip per key
    auto &rime = Rime::Instance();
    bool is_horizontal_layout =
        rime.getOption("_linear") || rime.getOption("_horizontal");
    jCandidates =
        rimeCandidatesPagedToJObject(env, context->menu, is_horizontal_layout);
  } else {
    auto [size, highlighted, list] = Rime::Instance().getBulkCandidates();
    auto jList =
        JRef<jobjectArray>(env, rimeCandidateListToJObjectArray(env, list));
    jCandidates =
        env->NewObject(GlobalRef->CandidatesBulk, GlobalRef->CandidatesBulkInit,
                       size, highlighted, *jList);
  }
  return rimeResponseToJObject(env, jCommit, jComposition, jCandidates,
                               jStatus);
}
