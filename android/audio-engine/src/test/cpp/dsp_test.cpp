// Host-side verification of the DSP chain (no Android required).
// Build & run:  g++ -O2 -std=c++17 -I../main/cpp dsp_test.cpp -o dsp_test && ./dsp_test
#include "dsp.h"
#include <cstdio>
#include <cstdlib>
#include <random>

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL: %s\n", msg); ++failures; } else std::printf("ok:   %s\n", msg); } while (0)

static float estimateFreq(const std::vector<float>& x, int from, int to, float fs) {
    int zc = 0;
    for (int i = from + 1; i < to; ++i) if ((x[i - 1] < 0) != (x[i] < 0)) ++zc;
    return (zc / 2.f) / ((to - from) / fs);
}

static std::vector<float> run(vm::EffectChain& c, const std::vector<float>& in) {
    std::vector<float> out(in.size());
    const int B = 480;
    for (size_t i = 0; i < in.size(); i += B) {
        int n = (int)std::min<size_t>(B, in.size() - i);
        c.processBlock(in.data() + i, out.data() + i, n);
    }
    return out;
}

static std::vector<float> sine(float f, float fs, int n, float a = 0.5f) {
    std::vector<float> x(n);
    for (int i = 0; i < n; ++i) x[i] = a * std::sin(2.f * (float)M_PI * f * i / fs);
    return x;
}

int main() {
    const float fs = 48000.f;
    float p[vm::kParamCount];

    for (float semis : {12.f, -12.f, 7.f}) {
        vm::EffectChain c; c.prepare(fs);
        vm::defaultParams(p); p[vm::P_PITCH] = semis; c.setParams(p);
        auto out = run(c, sine(440.f, fs, 48000));
        float expect = 440.f * std::pow(2.f, semis / 12.f);
        float got = estimateFreq(out, 24000, 47000, fs);
        std::printf("      pitch %+.0f st: expected %.1f Hz, measured %.1f Hz\n", semis, expect, got);
        char m[96]; std::snprintf(m, sizeof m, "pitch shift %+.0f semitones within 4%%", semis);
        CHECK(std::fabs(got - expect) / expect < 0.04f, m);
    }

    { // echo: impulse -> repeat at delay
        vm::EffectChain c; c.prepare(fs);
        vm::defaultParams(p); p[vm::P_ECHO_MIX] = 1.f; p[vm::P_ECHO_DELAY_MS] = 100.f; p[vm::P_ECHO_FEEDBACK] = 0.f; c.setParams(p);
        std::vector<float> in(12000, 0.f); in[0] = 0.8f;
        auto out = run(c, in);
        int peak = 1; for (int i = 1; i < (int)out.size(); ++i) if (std::fabs(out[i]) > std::fabs(out[peak])) peak = i;
        std::printf("      echo repeat at sample %d (expected 4800)\n", peak);
        CHECK(peak == 4800 && out[peak] > 0.7f, "echo repeats at configured delay");
    }

    { // reverb: tail exists, decays, finite
        vm::EffectChain c; c.prepare(fs);
        vm::defaultParams(p); p[vm::P_REVERB_MIX] = 0.6f; p[vm::P_REVERB_ROOM] = 0.7f; c.setParams(p);
        std::vector<float> in(96000, 0.f); in[0] = 0.8f;
        auto out = run(c, in);
        auto energy = [&](int a, int b) { double e = 0; for (int i = a; i < b; ++i) e += out[i] * out[i]; return e; };
        double e1 = energy(4800, 14400), e2 = energy(80000, 90000);
        std::printf("      reverb energy early %.6f late %.6f\n", e1, e2);
        CHECK(e1 > 1e-5 && e2 < e1, "reverb has a tail that decays");
    }

    { // noise suppression reduces steady low-level noise
        vm::EffectChain c; c.prepare(fs);
        vm::defaultParams(p); p[vm::P_DENOISE] = 1.f; c.setParams(p);
        std::mt19937 g(1); std::uniform_real_distribution<float> d(-0.01f, 0.01f);
        std::vector<float> in(96000); for (auto& v : in) v = d(g);
        auto out = run(c, in);
        auto rms = [](const std::vector<float>& x, int a, int b) { double e = 0; for (int i = a; i < b; ++i) e += x[i] * x[i]; return std::sqrt(e / (b - a)); };
        double rin = rms(in, 48000, 96000), rout = rms(out, 48000, 96000);
        std::printf("      noise rms in %.5f out %.5f\n", rin, rout);
        CHECK(rout < rin * 0.5, "adaptive noise suppression attenuates steady noise");
    }

    { // stress: extreme params on noise must stay finite and within [-1,1]
        vm::EffectChain c; c.prepare(44100.f);
        float q[vm::kParamCount] = {12, 1, 1, 900, 0.9f, 1, 1, 2000, 1, 18, 18, 18, -10, 1, 4, 1, 1};
        c.setParams(q);
        std::mt19937 g(2); std::uniform_real_distribution<float> d(-1.f, 1.f);
        std::vector<float> in(88200); for (auto& v : in) v = d(g);
        auto out = run(c, in);
        bool ok = true; for (float v : out) if (!std::isfinite(v) || std::fabs(v) > 1.0001f) ok = false;
        CHECK(ok, "extreme parameters stay finite and limited to [-1,1]");
        CHECK(c.takePreLimitPeak() > 1.f, "pre-limit peak reports clipping");
    }

    { // live parameter change while processing must not glitch to NaN
        vm::EffectChain c; c.prepare(fs);
        auto in = sine(300.f, fs, 4800);
        bool ok = true;
        for (int k = 0; k < 50; ++k) {
            vm::defaultParams(p); p[vm::P_PITCH] = (float)(k % 25) - 12.f; p[vm::P_ECHO_MIX] = 0.3f; c.setParams(p);
            auto out = run(c, in);
            for (float v : out) if (!std::isfinite(v)) ok = false;
        }
        CHECK(ok, "parameter changes between blocks are glitch-free (finite)");
    }

    std::printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
