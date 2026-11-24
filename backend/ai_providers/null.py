from __future__ import annotations

from .base import ProviderNotConfigured, VoiceProvider


class NullProvider(VoiceProvider):
    """Default. Never pretends: every call fails with a clear 'not configured' error."""

    configured = False

    def _fail(self):
        raise ProviderNotConfigured("No AI provider is configured on this server.")

    def create_profile(self, *, name, sample_wav):
        self._fail()

    def delete_profile(self, provider_profile_id):
        return None  # nothing was ever stored

    def convert(self, *, provider_profile_id, audio_wav):
        self._fail()
