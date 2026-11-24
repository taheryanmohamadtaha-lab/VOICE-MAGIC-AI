"""Provider-independent contract for cloud voice AI. Adapters are replaceable."""
from __future__ import annotations

from abc import ABC, abstractmethod


class ProviderError(Exception):
    """The provider failed to process a request (credits are refunded)."""


class ProviderNotConfigured(ProviderError):
    """No real provider is set up on this server."""


class VoiceProvider(ABC):
    #: True only when a real, working AI backend is behind this adapter.
    configured: bool = False

    @abstractmethod
    def create_profile(self, *, name: str, sample_wav: bytes) -> str:
        """Create a voice profile from ONE consented sample. Returns the provider's profile id."""

    @abstractmethod
    def delete_profile(self, provider_profile_id: str) -> None:
        """Delete the profile and any stored samples at the provider."""

    @abstractmethod
    def convert(self, *, provider_profile_id: str, audio_wav: bytes) -> bytes:
        """Convert audio into the profile's voice. Returns WAV bytes."""
