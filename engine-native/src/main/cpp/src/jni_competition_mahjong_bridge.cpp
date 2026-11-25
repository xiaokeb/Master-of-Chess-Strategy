#include "mocs/engine/native_engine_registry.hpp"
#include "mocs/engine/competition_mahjong_shape.hpp"
#include "jni_competition_mahjong_public_frame.hpp"

#include <algorithm>
#include <array>
#include <jni.h>
#include <stdexcept>

namespace {
using namespace mocs::engine;

void throw_java(JNIEnv* env, const char* type, const char* message) noexcept {
    const auto klass = env->FindClass(type);
    if (klass) env->ThrowNew(klass, message);
}

template <typename Result, typename Operation>
Result guarded(JNIEnv* env, Result fallback, Operation&& operation) noexcept {
    try { return operation(); }
    catch (const std::invalid_argument&) {
        throw_java(env,"java/lang/IllegalArgumentException","Invalid competition Mahjong argument");
    } catch (const std::logic_error&) {
        throw_java(env,"java/lang/IllegalStateException","Invalid competition Mahjong session");
    } catch (...) {
        throw_java(env,"java/lang/RuntimeException","Competition Mahjong native operation failed");
    }
    return fallback;
}

std::shared_ptr<CompetitionMahjongNativeSession> require_session(jlong handle) {
    auto session = NativeEngineRegistry::instance().find_competition_mahjong(handle);
    if (!session) throw std::logic_error("Invalid Mahjong handle");
    return session;
}

jint advance_bots(JNIEnv* env, jlong handle, jint level, jlong seed, jint budget, bool calibrated) {
    return guarded(env, static_cast<jint>(0), [&]() -> jint {
        if (level < 0 || level > 3 || seed < 0 || budget < 1 || budget > 256)
            throw std::invalid_argument("Invalid bot parameters");
        return require_session(handle)->advance_bots(level, seed, budget, calibrated);
    });
}

jintArray ints(JNIEnv* env, const std::vector<jint>& fields) {
    if (fields.size() > 65536) throw std::length_error("Oversized Mahjong view");
    auto result = env->NewIntArray(static_cast<jsize>(fields.size()));
    if (result && !fields.empty())
        env->SetIntArrayRegion(result,0,static_cast<jsize>(fields.size()),fields.data());
    return result;
}

template <typename Value> jint optional_code(const std::optional<Value>& value) {
    return value ? static_cast<jint>(*value) : -1;
}

template <typename Value> void tiles(std::vector<jint>& fields, const std::vector<Value>& values) {
    fields.push_back(static_cast<jint>(values.size()));
    for (const auto value : values) fields.push_back(value);
}

std::vector<jint> bounded_ints(JNIEnv* env, jintArray data, jsize maximum) {
    if (!data || env->GetArrayLength(data) > maximum) throw std::invalid_argument("Invalid Mahjong array");
    std::vector<jint> values(env->GetArrayLength(data));
    if (!values.empty()) env->GetIntArrayRegion(data, 0, static_cast<jsize>(values.size()), values.data());
    return values;
}
} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_NativeCompetitionMahjongDiscardWaits_analyze(
    JNIEnv* env, jobject, jintArray hand, jintArray melds, jintArray visible_copies,
    jintArray discards, jint seat_wind, jint round_wind, jint scoring) {
    return guarded(env, static_cast<jintArray>(nullptr), [&]() -> jintArray {
        const auto tiles = bounded_ints(env, hand, 14);
        const auto packs = bounded_ints(env, melds, 16);
        const auto known = bounded_ints(env, visible_copies, 34);
        const auto candidates = bounded_ints(env, discards, 14);
        if (packs.size() % 4 != 0 || known.size() != 34 || tiles.size() != 14 - 3 * (packs.size() / 4) ||
            seat_wind < 0 || seat_wind > 3 || round_wind < 0 || round_wind > 3 ||
            scoring < 1 || scoring > 3) throw std::invalid_argument("Invalid public wait position");

        CompetitionMahjongFanInput input;
        input.seat_wind = seat_wind;
        input.round_wind = round_wind;
        const auto rules = static_cast<CompetitionMahjongFanRules>(scoring);
        std::array<std::uint8_t, 34> hand_counts{};
        auto owned_counts = hand_counts;
        for (const int tile : tiles) {
            if (tile < 0 || tile >= 34) throw std::invalid_argument("Invalid public hand tile");
            ++hand_counts[tile];
            ++owned_counts[tile];
        }
        for (std::size_t index = 0; index < packs.size(); index += 4) {
            const int kind = packs[index], tile = packs[index + 1], offer = packs[index + 2], added = packs[index + 3];
            if (kind < 1 || kind > 3 || tile < 0 || tile >= 34 || offer < 0 || offer > 3 ||
                (kind == 1 && (tile >= 27 || tile % 9 > 6 || offer == 0)) || (kind == 2 && offer == 0) ||
                (added != 0 && (added != 1 || kind != 3 || offer == 0))) {
                throw std::invalid_argument("Invalid local Mahjong meld");
            }
            if (kind == 1) {
                for (int offset = 0; offset < 3; ++offset) ++owned_counts[tile + offset];
            } else owned_counts[tile] += kind == 2 ? 3 : 4;
            input.packs.push_back({static_cast<CompetitionMahjongFanPackKind>(kind), tile, offer, added != 0});
        }
        for (int tile = 0; tile < 34; ++tile) {
            if (known[tile] < owned_counts[tile] || known[tile] > 4)
                throw std::invalid_argument("Invalid public tile multiplicity");
        }

        std::vector<jint> result;
        for (const int discard : candidates) {
            if (discard < 0 || discard >= 34 || hand_counts[discard] == 0)
                throw std::invalid_argument("Invalid hypothetical discard");
            input.concealed_tiles = tiles;
            input.concealed_tiles.erase(std::find(input.concealed_tiles.begin(), input.concealed_tiles.end(), discard));
            auto remaining_hand = hand_counts;
            --remaining_hand[discard];
            std::array<std::uint8_t, 34> other_known{};
            for (int tile = 0; tile < 34; ++tile) other_known[tile] = known[tile] - remaining_hand[tile];
            const auto waits = competition_mahjong_waits(
                remaining_hand, static_cast<std::uint8_t>(input.packs.size()), other_known);
            int total = 0;
            int from_discard = 0;
            for (int tile = 0; tile < 34; ++tile) {
                if (!waits.tiles[tile]) continue;
                input.win_tile = tile;
                input.self_drawn = false;
                const bool claim = evaluateCompetitionMahjongFan(input, rules).qualifies;
                input.self_drawn = true;
                const bool draw = evaluateCompetitionMahjongFan(input, rules).qualifies;
                const int copies = 4 - known[tile];
                if (claim || draw) total += copies;
                if (claim) from_discard += copies;
            }
            result.insert(result.end(), {discard, total, from_discard});
        }
        return ints(env, result);
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_createCompetitionMahjong(
    JNIEnv* env, jobject, jlong seed, jint dealer, jint wind, jint viewer, jint scoring) {
    return guarded(env,static_cast<jlong>(0),[&]() -> jlong {
        if (seed < 0 || dealer < 0 || dealer > 3 || wind < 0 || wind > 3 || viewer < 0 || viewer > 3 ||
            (scoring != static_cast<jint>(CompetitionMahjongFanRules::legacy_v1) &&
             scoring != static_cast<jint>(CompetitionMahjongFanRules::wmo_2021) &&
             scoring != static_cast<jint>(CompetitionMahjongFanRules::wmo_2021_kongs)))
            throw std::invalid_argument("Invalid setup");
        return NativeEngineRegistry::instance().create_competition_mahjong(
            seed, dealer, wind, viewer, static_cast<CompetitionMahjongFanRules>(scoring));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_releaseCompetitionMahjong(
    JNIEnv*, jobject, jlong handle) {
    (void) NativeEngineRegistry::instance().release_competition_mahjong(handle);
}

jintArray mocs::engine::jni::competition_mahjong_public_frame(
    JNIEnv* env, const CompetitionMahjongPublicView& view) {
        const auto& fan = view.winning_fan;
        std::vector<jint> fields{
            2,view.viewer,static_cast<jint>(view.phase),view.dealer,view.round_wind,
            view.current_player,static_cast<jint>(view.wall_remaining),
            optional_code(view.pending_tile),optional_code(view.pending_player),
            optional_code(view.winner),optional_code(view.discarder),(view.dealer + 1) % 4,
        };
        for (const auto score : view.score_delta) fields.push_back(score);
        fields.insert(fields.end(),{static_cast<jint>(fan.error),fan.structure_valid,
            fan.fan_without_flowers,fan.total_fan,fan.qualifies,static_cast<jint>(fan.entries.size())});
        for (const auto& entry : fan.entries) {
            fields.insert(fields.end(),{entry.id,entry.points,entry.count,static_cast<jint>(entry.name.size())});
            for (const unsigned char byte : entry.name) fields.push_back(byte);
        }
        for (const auto& seat : view.seats) {
            fields.insert(fields.end(),{static_cast<jint>(seat.concealed_count),seat.win_disabled});
            tiles(fields,seat.flowers);
            tiles(fields,seat.discards);
            fields.push_back(static_cast<jint>(seat.melds.size()));
            for (const auto& meld : seat.melds)
                fields.insert(fields.end(),{static_cast<jint>(meld.kind),optional_code(meld.tile),
                    meld.from_player,meld.claimed_position,meld.added_kong});
            tiles(fields,seat.local_concealed);
        }
        // Versioned public capabilities only; never serialize the private round here.
        fields.insert(fields.end(),{view.can_declare_win,optional_code(view.drawn_tile),
            optional_code(view.winning_tile)});
        tiles(fields,view.winning_tiles);
        return ints(env,fields);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_competitionMahjongSnapshot(
    JNIEnv* env, jobject, jlong handle) {
    return guarded(env,static_cast<jintArray>(nullptr),[&]() {
        return mocs::engine::jni::competition_mahjong_public_frame(env, require_session(handle)->snapshot());
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_competitionMahjongLegalActions(
    JNIEnv* env, jobject, jlong handle) {
    return guarded(env,static_cast<jintArray>(nullptr),[&]() {
        const auto actions = require_session(handle)->legal_actions();
        std::vector<jint> fields;
        for (const auto& action : actions)
            fields.insert(fields.end(),{static_cast<jint>(action.kind),action.tile,action.sequence_start});
        return ints(env,fields);
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_applyCompetitionMahjongAction(
    JNIEnv* env, jobject, jlong handle, jint kind, jint tile, jint sequence) {
    return guarded(env,static_cast<jintArray>(nullptr),[&]() {
        if (kind < 0 || kind > 8 || tile < 0 || tile > 33 || sequence < 0 || sequence > 33)
            throw std::invalid_argument("Invalid action");
        const auto result = require_session(handle)->apply(
            static_cast<CompetitionMahjongRoundActionKind>(kind),tile,sequence);
        return ints(env,{result.accepted,static_cast<jint>(result.error),result.false_hu});
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_advanceCompetitionMahjongBots(
    JNIEnv* env, jobject, jlong handle, jint level, jlong seed, jint budget) {
    return advance_bots(env, handle, level, seed, budget, false);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_CalibratedCompetitionMahjongBindings_advanceBots(
    JNIEnv* env, jobject, jlong handle, jint level, jlong seed, jint budget) {
    return advance_bots(env, handle, level, seed, budget, true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_undoCompetitionMahjong(
    JNIEnv* env, jobject, jlong handle) {
    return guarded(env,JNI_FALSE,[&]() -> jboolean { return require_session(handle)->undo() ? JNI_TRUE : JNI_FALSE; });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_serializeCompetitionMahjong(
    JNIEnv* env, jobject, jlong handle) {
    return guarded(env,static_cast<jbyteArray>(nullptr),[&]() {
        const auto data = require_session(handle)->serialize();
        auto result = env->NewByteArray(static_cast<jsize>(data.size()));
        if (result) env->SetByteArrayRegion(result,0,static_cast<jsize>(data.size()),
            reinterpret_cast<const jbyte*>(data.data()));
        return result;
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_restoreCompetitionMahjong(
    JNIEnv* env, jobject, jlong handle, jbyteArray input) {
    return guarded(env,JNI_FALSE,[&]() -> jboolean {
        const auto session = require_session(handle);
        if (!input) throw std::invalid_argument("Null record");
        const auto size = env->GetArrayLength(input);
        if (size < 16 || size > 1024 * 1024) return JNI_FALSE;
        std::vector<std::uint8_t> data(static_cast<std::size_t>(size));
        env->GetByteArrayRegion(input,0,size,reinterpret_cast<jbyte*>(data.data()));
        if (env->ExceptionCheck()) return JNI_FALSE;
        return session->restore(data) ? JNI_TRUE : JNI_FALSE;
    });
}
