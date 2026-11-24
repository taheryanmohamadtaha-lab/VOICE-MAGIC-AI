"""Server settings, read from environment variables. No secrets live in code."""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    db_path: str = "./data/voice_magic.db"
    free_credits: int = 10
    convert_cost: int = 1
    max_upload_mb: int = 25
    ai_provider: str = "none"

    @staticmethod
    def from_env() -> "Settings":
        e = os.environ
        return Settings(
            db_path=e.get("VMA_DB_PATH", "./data/voice_magic.db"),
            free_credits=int(e.get("VMA_FREE_CREDITS", "10")),
            convert_cost=int(e.get("VMA_CONVERT_COST", "1")),
            max_upload_mb=int(e.get("VMA_MAX_UPLOAD_MB", "25")),
            ai_provider=e.get("VMA_AI_PROVIDER", "none").strip().lower(),
        )
