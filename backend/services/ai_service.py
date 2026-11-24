"""Business rules: consent, credits, provider calls. No web framework imports here."""
from __future__ import annotations

from ai_providers import ProviderError, ProviderNotConfigured, VoiceProvider

from .store import InsufficientCredits, Store, User


class ServiceError(Exception):
    """Carries a stable machine code (shown in the app) plus an HTTP status."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def validate_wav(data: bytes, max_bytes: int) -> None:
    if len(data) > max_bytes:
        raise ServiceError(413, "file_too_large", "Audio file is too large.")
    if len(data) < 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ServiceError(400, "invalid_audio", "Audio must be a WAV file.")


class AiService:
    def __init__(self, store: Store, provider: VoiceProvider, *, convert_cost: int, max_upload_bytes: int):
        self.store, self.provider = store, provider
        self.convert_cost, self.max_upload_bytes = convert_cost, max_upload_bytes

    def status(self, user: User) -> dict:
        u = self.store.get_user(user.id) or user
        return {"credits": u.credits, "premium": u.premium, "ai_configured": bool(self.provider.configured)}

    def _require_provider(self) -> None:
        if not self.provider.configured:
            raise ServiceError(503, "ai_not_configured", "No AI provider is configured on this server.")

    def create_profile(self, user: User, *, name: str, consent: bool, sample_wav: bytes) -> str:
        if not consent:
            raise ServiceError(400, "consent_required", "Explicit consent is required to create a voice profile.")
        name = name.strip()
        if not name or len(name) > 80:
            raise ServiceError(400, "invalid_name", "Profile name must be 1-80 characters.")
        self._require_provider()
        validate_wav(sample_wav, self.max_upload_bytes)
        try:
            ext_id = self.provider.create_profile(name=name, sample_wav=sample_wav)
        except ProviderNotConfigured as e:
            raise ServiceError(503, "ai_not_configured", str(e))
        except ProviderError as e:
            raise ServiceError(502, "provider_error", str(e))
        return self.store.add_profile(user.id, name, ext_id)

    def convert(self, user: User, *, profile_id: str, audio_wav: bytes) -> tuple[bytes, int]:
        self._require_provider()
        validate_wav(audio_wav, self.max_upload_bytes)
        prof = self.store.get_profile(user.id, profile_id)
        if prof is None:
            raise ServiceError(404, "profile_not_found", "Voice profile not found.")
        try:
            self.store.spend(user.id, self.convert_cost, "convert")
        except InsufficientCredits:
            raise ServiceError(402, "insufficient_credits", "Not enough credits.")
        try:
            out = self.provider.convert(provider_profile_id=prof[1], audio_wav=audio_wav)
        except ProviderError as e:
            self.store.grant(user.id, self.convert_cost, "refund_failed_convert")
            raise ServiceError(502, "provider_error", str(e))
        except BaseException:
            self.store.grant(user.id, self.convert_cost, "refund_failed_convert")
            raise
        remaining = self.store.get_user(user.id).credits  # type: ignore[union-attr]
        return out, remaining

    def delete_profile(self, user: User, profile_id: str) -> None:
        prof = self.store.get_profile(user.id, profile_id)
        if prof is None:
            return
        self.provider.delete_profile(prof[1])
        self.store.delete_profile(user.id, profile_id)

    def delete_account(self, user: User) -> None:
        for ext in self.store.provider_ids(user.id):
            self.provider.delete_profile(ext)
        self.store.delete_user(user.id)
