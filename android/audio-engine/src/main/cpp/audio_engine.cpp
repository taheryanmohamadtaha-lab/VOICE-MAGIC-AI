#include "audio_engine.h"
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VoiceMagicAudio", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VoiceMagicAudio", __VA_ARGS__)

namespace vm {

static constexpr size_t kRingSamples = 48000 * 20;  // 20 s of slack for the writer thread
static constexpr size_t kMaxBlock = 16384;

AudioEngine::AudioEngine() : ring_(kRingSamples) {
    inBuf_.assign(kMaxBlock, 0.f);
    procBuf_.assign(kMaxBlock, 0.f);
}

AudioEngine::~AudioEngine() { stop(); }

bool AudioEngine::start(int32_t inputDeviceId, int32_t outputDeviceId) {
    stop();
    std::lock_guard<std::mutex> lock(streamMutex_);
    needsRestart_.store(false);

    oboe::AudioStreamBuilder ob;
    ob.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setUsage(oboe::Usage::Media)
        ->setDeviceId(outputDeviceId)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result r = ob.openStream(out_);
    if (r != oboe::Result::OK) {
        LOGE("output open failed: %s", oboe::convertToText(r));
        out_.reset();
        return false;
    }

    oboe::AudioStreamBuilder ib;
    ib.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(out_->getSampleRate())
        ->setInputPreset(oboe::InputPreset::VoicePerformance)
        ->setDeviceId(inputDeviceId);
    r = ib.openStream(in_);
    if (r != oboe::Result::OK) {
        LOGE("input open failed: %s", oboe::convertToText(r));
        out_->close();
        out_.reset();
        in_.reset();
        return false;
    }

    sampleRate_ = out_->getSampleRate();
    burst_ = out_->getFramesPerBurst();
    lowLatency_ = out_->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
    out_->setBufferSizeInFrames(burst_ * 2);
    chain_.prepare((float)sampleRate_);
    inPeak_.store(0);
    outPeak_.store(0);
    latencyMs_.store(-1.f);
    blockCounter_ = 0;

    if (in_->requestStart() != oboe::Result::OK || out_->requestStart() != oboe::Result::OK) {
        LOGE("stream start failed");
        out_->close(); in_->close(); out_.reset(); in_.reset();
        return false;
    }
    running_.store(true);
    LOGI("started sr=%d burst=%d lowLatency=%d", sampleRate_, burst_, (int)lowLatency_);
    return true;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    running_.store(false);
    if (out_) { out_->stop(); out_->close(); out_.reset(); }  // callback is finished after close()
    if (in_) { in_->stop(); in_->close(); in_.reset(); }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) {
    float* out = static_cast<float*>(audioData);
    int32_t done = 0;
    float inPk = 0.f, outPk = 0.f, prePk = 0.f;
    const bool silent = muted_.load(std::memory_order_relaxed) || !monitor_.load(std::memory_order_relaxed);
    const bool rec = recording_.load(std::memory_order_relaxed);

    while (done < numFrames) {
        const int32_t n = std::min<int32_t>(numFrames - done, (int32_t)kMaxBlock);
        int32_t got = 0;
        if (in_) {
            auto res = in_->read(inBuf_.data(), n, 0);  // non-blocking
            if (res) got = res.value();
        }
        if (got < n) std::fill(inBuf_.begin() + got, inBuf_.begin() + n, 0.f);
        for (int32_t i = 0; i < n; ++i) inPk = std::max(inPk, std::fabs(inBuf_[i]));

        chain_.processBlock(inBuf_.data(), procBuf_.data(), n);
        prePk = std::max(prePk, chain_.takePreLimitPeak());
        for (int32_t i = 0; i < n; ++i) outPk = std::max(outPk, std::fabs(procBuf_[i]));
        if (rec) ring_.push(procBuf_.data(), (size_t)n);

        if (silent) std::fill(out + done, out + done + n, 0.f);
        else std::memcpy(out + done, procBuf_.data(), sizeof(float) * (size_t)n);
        done += n;
    }

    inPeak_.store(std::max(inPk, inPeak_.load(std::memory_order_relaxed) * 0.85f), std::memory_order_relaxed);
    outPeak_.store(std::max(outPk, outPeak_.load(std::memory_order_relaxed) * 0.85f), std::memory_order_relaxed);
    if (prePk >= 1.0f) clipCount_.fetch_add(1, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    LOGE("stream error: %s", oboe::convertToText(error));
    needsRestart_.store(true);  // app layer polls this and restarts (e.g. headset unplugged)
}

void AudioEngine::getMeters(float* o) {
    o[0] = inPeak_.load(); o[1] = outPeak_.load();
    o[2] = (float)clipCount_.load();
    o[3] = needsRestart_.load() ? 1.f : 0.f;
    o[4] = (float)sampleRate_;
    o[5] = (float)burst_;
    o[7] = lowLatency_ ? 1.f : 0.f;
    o[8] = (float)ring_.dropped();
    float lat = -1.f;
    if (running_.load() && streamMutex_.try_lock()) {
        if (out_) {
            auto r = out_->calculateLatencyMillis();
            if (r) lat = (float)r.value();
        }
        streamMutex_.unlock();
    }
    if (lat >= 0) latencyMs_.store(lat);
    o[6] = latencyMs_.load();
}

}  // namespace vm
