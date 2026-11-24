"""HTTP-level tests. Need fastapi + httpx (installed by CI); skipped where they are missing."""
import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")

from fastapi.testclient import TestClient  # noqa: E402

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402
from services.store import Store  # noqa: E402
from tests.conftest import TestOnlyProvider, make_wav  # noqa: E402


@pytest.fixture
def env():
    store = Store(":memory:")
    prov = TestOnlyProvider()
    app = create_app(Settings(), provider=prov, store=store)
    user, token = store.create_user(2)
    return TestClient(app), {"Authorization": f"Bearer {token}"}, prov


def test_status_requires_token():
    c = TestClient(create_app(Settings(), store=Store(":memory:")))
    assert c.get("/v1/status").status_code == 401
    assert c.get("/v1/status", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_default_server_reports_ai_not_configured():
    store = Store(":memory:")
    c = TestClient(create_app(Settings(), store=store))
    _, token = store.create_user(3)
    r = c.get("/v1/status", headers={"Authorization": f"Bearer {token}"})
    assert r.json() == {"credits": 3, "premium": False, "ai_configured": False}
    files = {"sample": ("s.wav", make_wav(), "audio/wav")}
    r = c.post("/v1/ai/profiles", data={"name": "me", "consent": "true"}, files=files, headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 503 and r.json()["detail"]["code"] == "ai_not_configured"


def test_full_flow(env):
    c, h, _ = env
    files = {"sample": ("s.wav", make_wav(), "audio/wav")}
    r = c.post("/v1/ai/profiles", data={"name": "me", "consent": "true"}, files=files, headers=h)
    pid = r.json()["profile_id"]
    r = c.post("/v1/ai/convert", data={"profile_id": pid}, files={"audio": ("a.wav", make_wav(), "audio/wav")}, headers=h)
    assert r.status_code == 200 and r.headers["X-Credits-Remaining"] == "1" and r.headers["X-AI-Generated"] == "true"
    assert c.delete(f"/v1/ai/profiles/{pid}", headers=h).status_code == 200
    assert c.delete("/v1/me", headers=h).status_code == 200
    assert c.get("/v1/status", headers=h).status_code == 401


def test_consent_false_rejected(env):
    c, h, _ = env
    r = c.post("/v1/ai/profiles", data={"name": "me", "consent": "false"}, files={"sample": ("s.wav", make_wav(), "audio/wav")}, headers=h)
    assert r.status_code == 400 and r.json()["detail"]["code"] == "consent_required"


def test_billing_validation_is_honestly_unavailable(env):
    c, h, _ = env
    r = c.post("/v1/billing/validate", headers=h)
    assert r.status_code == 501 and r.json()["detail"]["code"] == "billing_not_configured"
