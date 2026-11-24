import struct

import pytest

from ai_providers import ProviderError, VoiceProvider
from services.ai_service import AiService
from services.store import Store


def make_wav(n=100) -> bytes:
    data = b"\x00\x00" * n
    return (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
            + b"data" + struct.pack("<I", len(data)) + data)


class TestOnlyProvider(VoiceProvider):
    """TEST DOUBLE ONLY. Never registered in ai_providers.build_provider, so it cannot run in production."""
    __test__ = False
    configured = True

    def __init__(self):
        self.profiles, self.fail = {}, False

    def create_profile(self, *, name, sample_wav):
        pid = f"ext{len(self.profiles) + 1}"
        self.profiles[pid] = sample_wav
        return pid

    def delete_profile(self, provider_profile_id):
        self.profiles.pop(provider_profile_id, None)

    def convert(self, *, provider_profile_id, audio_wav):
        if self.fail:
            raise ProviderError("boom")
        return audio_wav


@pytest.fixture
def provider():
    return TestOnlyProvider()


@pytest.fixture
def store():
    return Store(":memory:")


@pytest.fixture
def svc(store, provider):
    return AiService(store, provider, convert_cost=1, max_upload_bytes=1024 * 1024)
