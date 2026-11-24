from .base import (  # noqa: F401
    ProviderError,
    ProviderNotConfigured,
    VoiceProvider,
)
from .null import NullProvider  # noqa: F401


def build_provider(name: str) -> VoiceProvider:
    """Registry. Add your adapter here (see docs/API_SETUP.md)."""
    name = (name or "none").lower()
    if name in ("none", ""):
        return NullProvider()
    raise ValueError(
        f"Unknown AI provider '{name}'. Only 'none' ships with this repository; "
        "write an adapter implementing ai_providers.base.VoiceProvider and register it here."
    )
