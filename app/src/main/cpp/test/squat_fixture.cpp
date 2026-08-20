#include "squat_fixture.h"

#include <cstdlib>
#include <fstream>
#include <iterator>

#include "one_euro.h"

namespace kinex {
namespace {

// The recorder's format is fixed and self-inflicted, so the parsing here is a scan for
// the keys it needs rather than a JSON library the project would then depend on.
bool ReadNumbersAfter(const std::string& text, const char* key, size_t count,
                      std::vector<double>* out) {
    const size_t key_at = text.find(key);
    if (key_at == std::string::npos) {
        return false;
    }
    const char* cursor = text.c_str() + key_at + std::string(key).size();
    for (size_t i = 0; i < count; ++i) {
        while (*cursor != '\0' && *cursor != '-' && *cursor != '.' &&
               (*cursor < '0' || *cursor > '9')) {
            ++cursor;
        }
        char* end = nullptr;
        const double value = std::strtod(cursor, &end);
        if (end == cursor) {
            return false;
        }
        out->push_back(value);
        cursor = end;
    }
    return true;
}

bool LoadRecording(const char* path, Recording* recording, std::string* error) {
    std::ifstream input(path);
    if (!input) {
        *error = "cannot open " + std::string(path);
        return false;
    }

    std::string line;
    while (std::getline(input, line)) {
        if (line.empty()) {
            continue;
        }
        std::vector<double> timestamp;
        if (!ReadNumbersAfter(line, "\"timestampMs\"", 1, &timestamp)) {
            *error = "no timestampMs on line " + std::to_string(recording->frame_count() + 1);
            return false;
        }
        std::vector<double> landmarks;
        landmarks.reserve(kLandmarkFloats);
        if (!ReadNumbersAfter(line, "\"landmarks\"", kLandmarkFloats, &landmarks)) {
            *error = "line " + std::to_string(recording->frame_count() + 1) + " does not hold " +
                     std::to_string(kLandmarkFloats) + " landmark floats";
            return false;
        }
        recording->timestamps_ms.push_back(static_cast<int64_t>(timestamp[0]));
        for (double value : landmarks) {
            recording->frames.push_back(static_cast<float>(value));
        }
    }

    if (recording->frame_count() == 0) {
        *error = std::string(path) + " holds no frames";
        return false;
    }
    return true;
}

struct Segments {
    int frames;
    Segment still;
    float depth_target_degrees;
    std::vector<Segment> descents;
};

bool LoadSegments(const char* path, Segments* segments, std::string* error) {
    std::ifstream input(path);
    if (!input) {
        *error = "cannot open " + std::string(path);
        return false;
    }
    const std::string text((std::istreambuf_iterator<char>(input)),
                           std::istreambuf_iterator<char>());

    std::vector<double> frames;
    if (!ReadNumbersAfter(text, "\"frames\"", 1, &frames)) {
        *error = "no \"frames\" count in " + std::string(path);
        return false;
    }
    segments->frames = static_cast<int>(frames[0]);

    std::vector<double> still;
    if (!ReadNumbersAfter(text, "\"still\"", 2, &still)) {
        *error = "no \"still\" range in " + std::string(path);
        return false;
    }
    segments->still = Segment{static_cast<int>(still[0]), static_cast<int>(still[1])};

    // Optional: only the FSM replay needs a depth target, and it reports its own absence.
    std::vector<double> depth_target;
    segments->depth_target_degrees =
        ReadNumbersAfter(text, "\"depthTargetDeg\"", 1, &depth_target)
            ? static_cast<float>(depth_target[0])
            : kInvalidAngle;

    const size_t descent_at = text.find("\"descent\"");
    if (descent_at == std::string::npos) {
        *error = "no \"descent\" ranges in " + std::string(path);
        return false;
    }
    const size_t open = text.find('[', descent_at);
    if (open == std::string::npos) {
        *error = "\"descent\" is not a list of [begin, end] pairs in " + std::string(path);
        return false;
    }

    // Bracket depth rather than a search for "]]", so the sidecar can be written the way
    // a human would write it — pretty-printed, one pair per line.
    std::vector<double> bounds;
    int depth = 0;
    const char* cursor = text.c_str() + open;
    while (*cursor != '\0') {
        if (*cursor == '[') {
            ++depth;
            ++cursor;
        } else if (*cursor == ']') {
            --depth;
            ++cursor;
            if (depth == 0) {
                break;
            }
        } else if (*cursor == '-' || (*cursor >= '0' && *cursor <= '9')) {
            char* end = nullptr;
            bounds.push_back(std::strtod(cursor, &end));
            cursor = end;
        } else {
            ++cursor;
        }
    }
    if (depth != 0) {
        *error = "\"descent\" list is never closed in " + std::string(path);
        return false;
    }
    // An odd count is a malformed list. An empty one is not: a fixture whose whole purpose
    // is a variance baseline holds no reps, and "descent": [] is how it says so. The
    // expected_reps cross-check below is what catches a rep fixture that lost its ranges.
    if (bounds.size() % 2 != 0) {
        *error = "\"descent\" holds " + std::to_string(bounds.size()) +
                 " numbers, which is not whole [begin, end] pairs";
        return false;
    }
    for (size_t i = 0; i < bounds.size(); i += 2) {
        segments->descents.push_back(
            Segment{static_cast<int>(bounds[i]), static_cast<int>(bounds[i + 1])});
    }
    return true;
}

bool ValidateSegment(const Segment& segment, int frames, const std::string& name,
                     std::string* error) {
    if (segment.begin < 0 || segment.end > frames || segment.begin + 1 >= segment.end) {
        *error = name + " range [" + std::to_string(segment.begin) + ", " +
                 std::to_string(segment.end) + ") is empty or outside the recording's " +
                 std::to_string(frames) + " frames";
        return false;
    }
    return true;
}

bool ValidateSegments(const FixtureSpec& spec, const Segments& segments, int frames,
                      std::string* error) {
    if (segments.frames != frames) {
        *error = std::string(spec.segments_path) + " claims " + std::to_string(segments.frames) +
                 " frames but " + spec.recording_path + " holds " + std::to_string(frames) +
                 ". Someone re-recorded the fixture and left the segments behind — every "
                 "index in it is now measuring the wrong frames.";
        return false;
    }
    const int marked = static_cast<int>(segments.descents.size());
    if (spec.expected_reps != 0 && marked != spec.expected_reps) {
        *error = "the fixture is a " + std::to_string(spec.expected_reps) +
                 "-rep squat, so it needs " + std::to_string(spec.expected_reps) +
                 " descent ranges, one per rep, but the sidecar holds " + std::to_string(marked);
        return false;
    }
    if (!ValidateSegment(segments.still, frames, "still", error)) {
        return false;
    }
    for (int rep = 0; rep < marked; ++rep) {
        const size_t index = static_cast<size_t>(rep);
        if (!ValidateSegment(segments.descents[index], frames, "descent (rep " +
                                                                   std::to_string(rep) + ")",
                             error)) {
            return false;
        }
        // Reps are measured up to where the next one starts, so overlapping or unsorted
        // ranges would silently measure one rep across another's frames.
        if (rep > 0 && segments.descents[index - 1].end > segments.descents[index].begin) {
            *error = "descent ranges must be in order and must not overlap: rep " +
                     std::to_string(rep - 1) + " ends after rep " + std::to_string(rep) +
                     " begins";
            return false;
        }
    }
    return true;
}

// Runs the filter over the whole recording in order, because it is stateful — slicing a
// segment out first would hand it a cold filter and measure a warm-up, not a filter.
Recording FilterAll(const Recording& raw) {
    Recording filtered;
    filtered.timestamps_ms = raw.timestamps_ms;
    filtered.frames.resize(raw.frames.size());

    LandmarkFilter filter;
    for (size_t frame = 0; frame < raw.frame_count(); ++frame) {
        const size_t offset = frame * kLandmarkFloats;
        filter.Apply(&raw.frames[offset], raw.timestamps_ms[frame], &filtered.frames[offset]);
    }
    return filtered;
}

}  // namespace

bool SquatFixtureExists(const FixtureSpec& spec) {
    std::ifstream probe(spec.recording_path);
    return static_cast<bool>(probe);
}

std::string MissingFixtureMessage(const FixtureSpec& spec) {
    const std::string reps = spec.requirement;
    return "no fixture at cpp/test/" + std::string(spec.recording_path) +
           " — this test is inert until one lands.\n"
           "Record a squat with the app's recorder and pull it:\n"
           "  adb pull /sdcard/Android/data/com.kinex/files/recordings/<file>.jsonl \\\n"
           "    app/src/main/cpp/test/" +
           std::string(spec.recording_path) + "\n" + reps + "\nThen write cpp/test/" +
           std::string(spec.segments_path) + " — schema at the top of squat_fixture.h.";
}

bool LoadSquatFixture(const FixtureSpec& spec, SquatFixture* fixture, std::string* error) {
    if (!LoadRecording(spec.recording_path, &fixture->raw, error)) {
        return false;
    }

    Segments segments;
    if (!LoadSegments(spec.segments_path, &segments, error)) {
        *error += "\nThe fixture is present, so its segments must be too. Schema is at the "
                  "top of squat_fixture.h.";
        return false;
    }
    if (!ValidateSegments(spec, segments, fixture->frames(), error)) {
        return false;
    }

    fixture->still = segments.still;
    fixture->descents = segments.descents;
    fixture->depth_target_degrees = segments.depth_target_degrees;
    fixture->filtered = FilterAll(fixture->raw);
    return true;
}

Segment RepWindow(const SquatFixture& fixture, int rep) {
    const size_t index = static_cast<size_t>(rep);
    const int begin = fixture.descents[index].begin;
    const int end = rep + 1 < fixture.reps() ? fixture.descents[index + 1].begin
                                             : fixture.frames();
    return Segment{begin, end};
}

std::vector<float> Channel(const Recording& recording, int landmark, int component,
                           const Segment& segment) {
    std::vector<float> series;
    series.reserve(static_cast<size_t>(segment.end - segment.begin));
    for (int frame = segment.begin; frame < segment.end; ++frame) {
        series.push_back(
            recording.frames[static_cast<size_t>(frame) * kLandmarkFloats +
                             static_cast<size_t>(landmark * kLandmarkStride + component)]);
    }
    return series;
}

}  // namespace kinex
