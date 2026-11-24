from __future__ import annotations

from fastapi import FastAPI

from ai_providers import build_provider
from api.routes import router
from app.config import Settings
from services.ai_service import AiService
from services.store import Store


def create_app(settings: Settings | None = None, *, provider=None, store: Store | None = None) -> FastAPI:
    s = settings or Settings.from_env()
    app = FastAPI(title="Voice Magic AI backend", version="0.1.0")
    app.state.settings = s
    app.state.store = store or Store(s.db_path)
    app.state.ai = AiService(
        app.state.store,
        provider or build_provider(s.ai_provider),
        convert_cost=s.convert_cost,
        max_upload_bytes=s.max_upload_mb * 1024 * 1024,
    )
    app.include_router(router)

    @app.get("/health")
    def health():
        return {"ok": True}

    return app


def app_factory() -> FastAPI:  # uvicorn app.main:app_factory --factory
    return create_app()
