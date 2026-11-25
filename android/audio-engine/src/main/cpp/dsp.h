// Voice Magic AI - real-time DSP chain (header only, no Android dependencies).
// Everything here is allocation-free after prepare() so it is safe to call from
// an audio callback. The same code is used live (Oboe) and offline (editor/studio).
#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace vm {

constexpr int kParamCount = 21;

// Keep in sync with EffectParams.kt (index order of toArray()).
enum Param : int {
    P_PITCH = 0,        // semitones, -12..+12
    P_FORMANT,          // -1..+1  vocal-tract (formant) shift, approximated with paired EQ peaks - NOT true LPC/PSOLA formant shifting
    P_ECHO_MIX,         // 0..1
    P_ECHO_DELAY_MS,    // 20..900
    P_ECHO_FEEDBACK,    // 0..0.9
    P_REVERB_MIX,       // 0..1
    P_REVERB_ROOM,      // 0..1
    P_RING_FREQ,        // Hz
    P_RING_MIX,         // 0..1
    P_EQ_LOW,           // dB
    P_EQ_MID,           // dB
    P_EQ_HIGH,          // dB
    P_GATE_DB,          // dB, noise gate threshold
    P_DENOISE,          // 0/1 adaptive noise suppression
    P_OUT_GAIN,         // linear
    P_DISTORTION,       // 0..1
    P_BANDPASS,         // 0/1 telephone/radio band limiting
    P_TREM_DEPTH,       // 0..1 amplitude wobble (elderly voice)
    P_TREM_HZ,          // 0.5..12
    P_VIB_DEPTH,        // 0..1 semitones of pitch wobble
    P_VIB_HZ            // 0.5..12
};

inline void defaultParams(float* p) {
    for (int i = 0; i < kParamCount; ++i) p[i] = 0.f;
    p[P_ECHO_DELAY_MS] = 250.f;
    p[P_ECHO_FEEDBACK] = 0.35f;
    p[P_REVERB_ROOM] = 0.5f;
    p[P_RING_FREQ] = 30.f;
    p[P_GATE_DB] = -90.f;
    p[P_OUT_GAIN] = 1.f;
    p[P_TREM_HZ] = 5.f;
    p[P_VIB_HZ] = 5.5f;
}

// ---------------------------------------------------------------- Biquad (RBJ)
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1 = 0, z2 = 0;
    bool active = false;

    void reset() { z1 = z2 = 0.f; }
    float process(float x) {
        float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void setRaw(double nb0, double nb1, double nb2, double a0, double na1, double na2) {
        b0 = (float)(nb0 / a0);
        b1 = (float)(nb1 / a0);
        b2 = (float)(nb2 / a0);
        a1 = (float)(na1 / a0);
        a2 = (float)(na2 / a0);
    }
    void lowShelf(double fs, double f, double dB) {
        const double A = std::pow(10.0, dB / 40.0), w = 2 * M_PI * f / fs;
        const double c = std::cos(w), s = std::sin(w), al = s / (2 * 0.7071), t = 2 * std::sqrt(A) * al;
        setRaw(A * ((A + 1) - (A - 1) * c + t), 2 * A * ((A - 1) - (A + 1) * c),
               A * ((A + 1) - (A - 1) * c - t), (A + 1) + (A - 1) * c + t,
               -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - t);
    }
    void highShelf(double fs, double f, double dB) {
        const double A = std::pow(10.0, dB / 40.0), w = 2 * M_PI * f / fs;
        const double c = std::cos(w), s = std::sin(w), al = s / (2 * 0.7071), t = 2 * std::sqrt(A) * al;
        setRaw(A * ((A + 1) + (A - 1) * c + t), -2 * A * ((A - 1) + (A + 1) * c),
               A * ((A + 1) + (A - 1) * c - t), (A + 1) - (A - 1) * c + t,
               2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - t);
    }
    void peaking(double fs, double f, double dB, double q) {
        const double A = std::pow(10.0, dB / 40.0), w = 2 * M_PI * f / fs;
        const double c = std::cos(w), al = std::sin(w) / (2 * q);
        setRaw(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
    }
    void highPass(double fs, double f, double q = 0.7071) {
        const double w = 2 * M_PI * f / fs, c = std::cos(w), al = std::sin(w) / (2 * q);
        setRaw((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
    }
    void lowPass(double fs, double f, double q = 0.7071) {
        const double w = 2 * M_PI * f / fs, c = std::cos(w), al = std::sin(w) / (2 * q);
        setRaw((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
    }
};

// ---------------------------------------------------------------- Pitch shifter
// Classic dual-tap crossfaded delay-line shifter. Very low latency (~25 ms),
// good quality for +/-12 semitones on speech. Not a phase vocoder.
class PitchShifter {
public:
    void prepare(float fs) {
        win_ = std::max(64, (int)(0.05f * fs));
        buf_.assign((size_t)win_ * 2 + 8, 0.f);
        w_ = 0;
        phase_ = 0.f;
    }
    void setSemitones(float s) { ratio_ = std::pow(2.f, s / 12.f); }
    void setRatio(float r) { ratio_ = r; }
    float process(float x) {
        const int n = (int)buf_.size();
        buf_[w_] = x;
        float out = 0.f;
        for (int k = 0; k < 2; ++k) {
            float ph = phase_ + 0.5f * k;
            if (ph >= 1.f) ph -= 1.f;
            const float delay = ph * win_ + 1.f;
            float rp = (float)w_ - delay;
            if (rp < 0.f) rp += n;
            int i0 = (int)rp;
            const float fr = rp - i0;
            int i1 = i0 + 1;
            if (i0 >= n) i0 -= n;
            if (i1 >= n) i1 -= n;
            const float s = buf_[i0] * (1.f - fr) + buf_[i1] * fr;
            out += s * (0.5f - 0.5f * std::cos(6.2831853f * ph));
        }
        phase_ += (1.f - ratio_) / (float)win_;
        if (phase_ >= 1.f) phase_ -= 1.f;
        else if (phase_ < 0.f) phase_ += 1.f;
        if (++w_ >= n) w_ = 0;
        return out;
    }
private:
    std::vector<float> buf_;
    int win_ = 2400, w_ = 0;
    float phase_ = 0.f, ratio_ = 1.f;
};

// ---------------------------------------------------------------- Echo
class Echo {
public:
    void prepare(float fs) {
        buf_.assign((size_t)(fs * 1.0f) + 4, 0.f);
        w_ = 0;
    }
    void setDelaySamples(int d) { delay_ = std::max(1, std::min(d, (int)buf_.size() - 1)); }
    float process(float x, float mix, float fb) {
        int r = w_ - delay_;
        if (r < 0) r += (int)buf_.size();
        const float wet = buf_[r];
        buf_[w_] = x + wet * fb;
        if (++w_ >= (int)buf_.size()) w_ = 0;
        return x + wet * mix;
    }
private:
    std::vector<float> buf_;
    int w_ = 0, delay_ = 1000;
};

// ---------------------------------------------------------------- Reverb (Freeverb style)
class Reverb {
public:
    void prepare(float fs) {
        static const int combLen[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491};
        static const int apLen[kAps] = {556, 441, 341};
        const float k = fs / 44100.f;
        for (int i = 0; i < kCombs; ++i) comb_[i].init(std::max(8, (int)(combLen[i] * k)));
        for (int i = 0; i < kAps; ++i) ap_[i].init(std::max(8, (int)(apLen[i] * k)));
    }
    void setRoom(float room) { fb_ = 0.70f + 0.28f * std::min(1.f, std::max(0.f, room)); }
    float process(float x, float mix) {
        float wet = 0.f;
        const float in = x * 0.02f;
        for (int i = 0; i < kCombs; ++i) wet += comb_[i].process(in, fb_, 0.3f);
        for (int i = 0; i < kAps; ++i) wet = ap_[i].process(wet);
        return x * (1.f - 0.4f * mix) + wet * 3.f * mix;
    }
private:
    static constexpr int kCombs = 6, kAps = 3;
    struct Comb {
        std::vector<float> b; int i = 0; float store = 0.f;
        void init(int n) { b.assign((size_t)n, 0.f); i = 0; store = 0.f; }
        float process(float x, float fb, float damp) {
            const float o = b[i];
            store = o * (1.f - damp) + store * damp;
            b[i] = x + store * fb;
            if (++i >= (int)b.size()) i = 0;
            return o;
        }
    };
    struct Allpass {
        std::vector<float> b; int i = 0;
        void init(int n) { b.assign((size_t)n, 0.f); i = 0; }
        float process(float x) {
            const float bo = b[i];
            const float o = -x + bo;
            b[i] = x + bo * 0.5f;
            if (++i >= (int)b.size()) i = 0;
            return o;
        }
    };
    Comb comb_[kCombs];
    Allpass ap_[kAps];
    float fb_ = 0.84f;
};

// ---------------------------------------------------------------- Adaptive noise gate
// Basic noise suppression: tracks the noise floor and expands (attenuates) below it.
// This is NOT a neural denoiser; it is intentionally lightweight and offline-capable.
class NoiseGate {
public:
    void setFixedThreshold(float lin) { fixed_ = lin; }
    void setAdaptive(bool a) { adaptive_ = a; }
    float process(float x) {
        const float a = std::fabs(x);
        env_ = a > env_ ? a : env_ * 0.9985f;
        if (env_ < floor_) floor_ = env_;
        else floor_ += (env_ - floor_) * 0.00003f;
        float thr = fixed_;
        if (adaptive_) thr = std::max(thr, floor_ * 2.5f + 1e-4f);
        const float target = env_ > thr ? 1.f : 0.08f;
        gain_ += (target - gain_) * (target > gain_ ? 0.02f : 0.0015f);
        return x * gain_;
    }
    void reset() { env_ = 0.f; floor_ = 0.01f; gain_ = 1.f; }
private:
    float env_ = 0.f, floor_ = 0.01f, gain_ = 1.f, fixed_ = 0.f;
    bool adaptive_ = false;
};

// ---------------------------------------------------------------- Effect chain
class EffectChain {
public:
    EffectChain() {
        float d[kParamCount];
        defaultParams(d);
        for (int i = 0; i < kParamCount; ++i) {
            shared_[i].store(d[i], std::memory_order_relaxed);
            p_[i] = d[i];
        }
    }

    void prepare(float sampleRate) {
        fs_ = sampleRate;
        pitch_.prepare(fs_);
        echo_.prepare(fs_);
        reverb_.prepare(fs_);
        gate_.reset();
        for (auto* b : {&low_, &mid_, &high_, &hp_, &lp_}) b->reset();
        ringPhase_ = 0.f;
        tremPhase_ = vibPhase_ = 0.f;
        for (auto* b : {&fa0_, &fa1_, &fa2_, &fb0_, &fb1_, &fb2_}) b->reset();
        localVersion_ = ~0u;  // force coefficient refresh
        syncParams();
    }

    // Thread-safe: may be called from any thread.
    void setParams(const float* p) {
        for (int i = 0; i < kParamCount; ++i) shared_[i].store(p[i], std::memory_order_relaxed);
        version_.fetch_add(1, std::memory_order_release);
    }

    void processBlock(const float* in, float* out, int n) {
        syncParams();
        float peak = 0.f;
        for (int i = 0; i < n; ++i) {
            float y = processSample(in[i]);
            const float a = std::fabs(y);
            if (a > peak) peak = a;
            out[i] = std::max(-1.f, std::min(1.f, y));
        }
        if (peak > preLimitPeak_) preLimitPeak_ = peak;
    }

    // Peak before the final limiter since the last call (used for clipping detection).
    float takePreLimitPeak() { const float p = preLimitPeak_; preLimitPeak_ = 0.f; return p; }

private:
    float processSample(float x) {
        if (p_[P_DENOISE] > 0.5f || gateEnabled_) x = gate_.process(x);
        if (pitchOn_) {
            if (vibDepth_ > 0.001f) {
                vibPhase_ += vibInc_;
                if (vibPhase_ > 6.2831853f) vibPhase_ -= 6.2831853f;
                // 2^(x/12) ~= 1 + 0.05776 x for small x (x = vibrato in semitones)
                pitch_.setRatio(baseRatio_ * (1.f + vibDepth_ * 0.05776f * std::sin(vibPhase_)));
            }
            x = pitch_.process(x);
        }
        if (tremDepth_ > 0.001f) {
            tremPhase_ += tremInc_;
            if (tremPhase_ > 6.2831853f) tremPhase_ -= 6.2831853f;
            x *= 1.f - tremDepth_ * (0.5f + 0.5f * std::sin(tremPhase_));
        }
        if (p_[P_RING_MIX] > 0.001f) {
            const float c = std::sin(ringPhase_);
            ringPhase_ += ringInc_;
            if (ringPhase_ > 6.2831853f) ringPhase_ -= 6.2831853f;
            const float m = p_[P_RING_MIX];
            x = x * (1.f - m) + x * c * m;
        }
        if (p_[P_DISTORTION] > 0.001f) {
            const float d = p_[P_DISTORTION];
            const float drive = 1.f + d * 12.f;
            x = std::tanh(x * drive) / std::tanh(drive) * (1.f - 0.2f * d);
        }
        if (p_[P_BANDPASS] > 0.5f) x = lp_.process(hp_.process(x));
        if (low_.active) x = low_.process(x);
        if (mid_.active) x = mid_.process(x);
        if (high_.active) x = high_.process(x);
        if (formantOn_) {
            x = fa0_.process(fa1_.process(fa2_.process(x)));
            x = fb0_.process(fb1_.process(fb2_.process(x)));
        }
        if (p_[P_ECHO_MIX] > 0.001f) x = echo_.process(x, p_[P_ECHO_MIX], p_[P_ECHO_FEEDBACK]);
        if (p_[P_REVERB_MIX] > 0.001f) x = reverb_.process(x, p_[P_REVERB_MIX]);
        return x * p_[P_OUT_GAIN];
    }

    void syncParams() {
        const uint32_t v = version_.load(std::memory_order_acquire);
        if (v == localVersion_) return;
        localVersion_ = v;
        for (int i = 0; i < kParamCount; ++i) p_[i] = shared_[i].load(std::memory_order_relaxed);
        // sanitise
        p_[P_PITCH] = clampf(p_[P_PITCH], -12.f, 12.f);
        p_[P_FORMANT] = clampf(p_[P_FORMANT], -1.f, 1.f);
        p_[P_ECHO_MIX] = clampf(p_[P_ECHO_MIX], 0.f, 1.f);
        p_[P_ECHO_FEEDBACK] = clampf(p_[P_ECHO_FEEDBACK], 0.f, 0.9f);
        p_[P_REVERB_MIX] = clampf(p_[P_REVERB_MIX], 0.f, 1.f);
        p_[P_RING_MIX] = clampf(p_[P_RING_MIX], 0.f, 1.f);
        p_[P_DISTORTION] = clampf(p_[P_DISTORTION], 0.f, 1.f);
        p_[P_OUT_GAIN] = clampf(p_[P_OUT_GAIN], 0.f, 4.f);
        p_[P_TREM_DEPTH] = clampf(p_[P_TREM_DEPTH], 0.f, 1.f);
        p_[P_TREM_HZ] = clampf(p_[P_TREM_HZ], 0.5f, 12.f);
        p_[P_VIB_DEPTH] = clampf(p_[P_VIB_DEPTH], 0.f, 1.f);
        p_[P_VIB_HZ] = clampf(p_[P_VIB_HZ], 0.5f, 12.f);

        vibDepth_ = p_[P_VIB_DEPTH];
        tremDepth_ = p_[P_TREM_DEPTH];
        vibInc_ = 6.2831853f * p_[P_VIB_HZ] / fs_;
        tremInc_ = 6.2831853f * p_[P_TREM_HZ] / fs_;
        baseRatio_ = std::pow(2.f, p_[P_PITCH] / 12.f);
        pitchOn_ = std::fabs(p_[P_PITCH]) > 0.01f || vibDepth_ > 0.001f;
        pitch_.setSemitones(p_[P_PITCH]);
        echo_.setDelaySamples((int)(clampf(p_[P_ECHO_DELAY_MS], 20.f, 900.f) * fs_ / 1000.f));
        reverb_.setRoom(p_[P_REVERB_ROOM]);
        ringInc_ = 6.2831853f * clampf(p_[P_RING_FREQ], 1.f, 2000.f) / fs_;

        const float f = p_[P_FORMANT];
        const float lowDb = p_[P_EQ_LOW] - f * 2.f;
        const float midDb = p_[P_EQ_MID];
        const float highDb = p_[P_EQ_HIGH] + f * 2.f;

        // Formant shift approximation: boost the vowel resonances at their shifted position and
        // cut them at the original position, for F1..F3 (about +-32% at |f| = 1).
        formantOn_ = std::fabs(f) > 0.02f;
        if (formantOn_) {
            const float s = std::pow(2.f, 0.4f * f), a = std::fabs(f);
            static const float cf[3] = {500.f, 1500.f, 2500.f};
            Biquad* up[3] = {&fa0_, &fa1_, &fa2_};
            Biquad* dn[3] = {&fb0_, &fb1_, &fb2_};
            for (int i = 0; i < 3; ++i) {
                up[i]->peaking(fs_, std::min(cf[i] * s, fs_ * 0.45f), 5.f * a, 1.4);
                dn[i]->peaking(fs_, std::min(cf[i], fs_ * 0.45f), -4.f * a, 1.4);
            }
        }
        low_.active = std::fabs(lowDb) > 0.05f;
        mid_.active = std::fabs(midDb) > 0.05f;
        high_.active = std::fabs(highDb) > 0.05f;
        if (low_.active) low_.lowShelf(fs_, 200.0, clampf(lowDb, -18.f, 18.f));
        if (mid_.active) mid_.peaking(fs_, 1500.0, clampf(midDb, -18.f, 18.f), 0.8);
        if (high_.active) high_.highShelf(fs_, 4000.0, clampf(highDb, -18.f, 18.f));
        hp_.highPass(fs_, 350.0);
        lp_.lowPass(fs_, 3400.0);

        const float gdb = clampf(p_[P_GATE_DB], -90.f, 0.f);
        gateEnabled_ = gdb > -89.f;
        gate_.setFixedThreshold(gateEnabled_ ? std::pow(10.f, gdb / 20.f) : 0.f);
        gate_.setAdaptive(p_[P_DENOISE] > 0.5f);
    }

    static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    float fs_ = 48000.f;
    std::atomic<float> shared_[kParamCount];
    std::atomic<uint32_t> version_{0};
    uint32_t localVersion_ = ~0u;
    float p_[kParamCount];

    PitchShifter pitch_;
    Echo echo_;
    Reverb reverb_;
    NoiseGate gate_;
    Biquad low_, mid_, high_, hp_, lp_;
    bool pitchOn_ = false, gateEnabled_ = false, formantOn_ = false;
    float ringPhase_ = 0.f, ringInc_ = 0.f;
    float tremPhase_ = 0.f, tremInc_ = 0.f, tremDepth_ = 0.f;
    float vibPhase_ = 0.f, vibInc_ = 0.f, vibDepth_ = 0.f, baseRatio_ = 1.f;
    Biquad fa0_, fa1_, fa2_, fb0_, fb1_, fb2_;
    float preLimitPeak_ = 0.f;
};

}  // namespace vm
