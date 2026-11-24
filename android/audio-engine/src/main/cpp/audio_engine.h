// Full-duplex low-latency voice engine built on Oboe (AAudio / OpenSL ES fallback).
#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <vector>
#include "dsp.h"

namespace vm {

// Lock-free single-producer/single-consumer float ring buffer (audio thread -> app thread).
class SpscRing {
public:
    explicit SpscRing(size_t capacity) : buf_(capacity), cap_(capacity) {}
    size_t push(const float* d, size_t n) {
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t t = tail_.load(std::memory_order_acquire);
        const size_t freeSpace = cap_ - (h - t);
        if (n > freeSpace) { dropped_.fetch_add(n - freeSpace, std::memory_order_relaxed); n = freeSpace; }
        for (size_t i = 0; i < n; ++i) buf_[(h + i) % cap_] = d[i];
        head_.store(h + n, std::memory_order_release);
        return n;
    }
    size_t pop(float* out, size_t maxN) {
        const size_t t = tail_.load(std::memory_order_relaxed);
        const size_t h = head_.load(std::memory_order_acquire);
        size_t n = std::min(maxN, h - t);
        for (size_t i = 0; i < n; ++i) out[i] = buf_[(t + i) % cap_];
        tail_.store(t + n, std::memory_order_release);
        return n;
    }
    void clear() { tail_.store(head_.load(std::memory_order_acquire), std::memory_order_release); }
    size_t dropped() const { return dropped_.load(std::memory_order_relaxed); }
private:
    std::vector<float> buf_;
    size_t cap_;
    std::atomic<size_t> head_{0}, tail_{0}, dropped_{0};
};

class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    bool start(int32_t inputDeviceId, int32_t outputDeviceId);
    void stop();

    EffectChain& chain() { return chain_; }
    void setMuted(bool m) { muted_.store(m); }
    void setMonitor(bool m) { monitor_.store(m); }
    void setRecording(bool r) { if (r) ring_.clear(); recording_.store(r); }
    size_t readRecorded(float* out, size_t n) { return ring_.pop(out, n); }
    void resetClip() { clipCount_.store(0); }

    // out[0]=inPeak out[1]=outPeak out[2]=clipCount out[3]=needsRestart out[4]=sampleRate
    // out[5]=framesPerBurst out[6]=latencyMs(-1 unknown) out[7]=lowLatencyGranted out[8]=droppedRecordSamples
    void getMeters(float* out9);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    std::mutex streamMutex_;  // never taken on the audio thread
    std::shared_ptr<oboe::AudioStream> in_, out_;
    EffectChain chain_;
    SpscRing ring_;
    std::vector<float> inBuf_, procBuf_;
    std::atomic<float> inPeak_{0}, outPeak_{0};
    std::atomic<int> clipCount_{0};
    std::atomic<bool> muted_{false}, monitor_{true}, recording_{false}, needsRestart_{false}, running_{false};
    std::atomic<float> latencyMs_{-1.f};
    int32_t sampleRate_ = 48000, burst_ = 0;
    bool lowLatency_ = false;
    int blockCounter_ = 0;
};

}  // namespace vm
