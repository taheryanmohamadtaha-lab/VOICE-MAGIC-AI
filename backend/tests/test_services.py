"""Framework-independent tests: consent, credits, ownership, refunds, deletion, honesty."""
import pytest

from ai_providers import NullProvider, build_provider
from services.ai_service import AiService, ServiceError
from services.store import Store, hash_token
from tests.conftest import make_wav


def code(exc):
    return exc.value.code


def test_token_is_stored_hashed_and_resolves():
    s = Store(":memory:")
    u, token = s.create_user(5)
    assert s.user_for_token(token).id == u.id
    assert s.user_for_token("vma_wrong") is None
    row = s._db.execute("SELECT token_hash FROM tokens").fetchone()[0]
    assert row == hash_token(token) and token not in row


def test_consent_is_required(svc, store):
    u, _ = store.create_user(5)
    with pytest.raises(ServiceError) as e:
        svc.create_profile(u, name="me", consent=False, sample_wav=make_wav())
    assert code(e) == "consent_required"


def test_invalid_audio_rejected(svc, store):
    u, _ = store.create_user(5)
    with pytest.raises(ServiceError) as e:
        svc.create_profile(u, name="me", consent=True, sample_wav=b"not a wav file at all, definitely" * 5)
    assert code(e) == "invalid_audio"


def test_convert_spends_credit_and_returns_remaining(svc, store):
    u, _ = store.create_user(3)
    pid = svc.create_profile(u, name="me", consent=True, sample_wav=make_wav())
    out, remaining = svc.convert(u, profile_id=pid, audio_wav=make_wav())
    assert out[:4] == b"RIFF" and remaining == 2
    assert [d for _, d, _ in store.ledger(u.id)] == [3, -1]


def test_insufficient_credits_blocks_conversion(svc, store):
    u, _ = store.create_user(0)
    pid = svc.create_profile(u, name="me", consent=True, sample_wav=make_wav())
    with pytest.raises(ServiceError) as e:
        svc.convert(u, profile_id=pid, audio_wav=make_wav())
    assert code(e) == "insufficient_credits" and e.value.status == 402


def test_provider_failure_refunds_credit(svc, store, provider):
    u, _ = store.create_user(2)
    pid = svc.create_profile(u, name="me", consent=True, sample_wav=make_wav())
    provider.fail = True
    with pytest.raises(ServiceError) as e:
        svc.convert(u, profile_id=pid, audio_wav=make_wav())
    assert code(e) == "provider_error"
    assert store.get_user(u.id).credits == 2


def test_user_cannot_use_someone_elses_profile(svc, store):
    a, _ = store.create_user(5)
    b, _ = store.create_user(5)
    pid = svc.create_profile(a, name="a", consent=True, sample_wav=make_wav())
    with pytest.raises(ServiceError) as e:
        svc.convert(b, profile_id=pid, audio_wav=make_wav())
    assert code(e) == "profile_not_found"


def test_delete_profile_removes_provider_data(svc, store, provider):
    u, _ = store.create_user(1)
    pid = svc.create_profile(u, name="me", consent=True, sample_wav=make_wav())
    assert provider.profiles
    svc.delete_profile(u, pid)
    assert not provider.profiles and store.get_profile(u.id, pid) is None


def test_delete_account_removes_everything(svc, store, provider):
    u, token = store.create_user(4)
    svc.create_profile(u, name="me", consent=True, sample_wav=make_wav())
    svc.delete_account(u)
    assert store.get_user(u.id) is None and store.user_for_token(token) is None
    assert not provider.profiles and store.ledger(u.id) == []


def test_default_provider_never_fakes_ai(store):
    p = build_provider("none")
    assert isinstance(p, NullProvider) and p.configured is False
    s = AiService(store, p, convert_cost=1, max_upload_bytes=1 << 20)
    u, _ = store.create_user(5)
    with pytest.raises(ServiceError) as e:
        s.create_profile(u, name="me", consent=True, sample_wav=make_wav())
    assert code(e) == "ai_not_configured" and e.value.status == 503
    assert s.status(u) == {"credits": 5, "premium": False, "ai_configured": False}


def test_unknown_provider_name_is_an_error():
    with pytest.raises(ValueError):
        build_provider("made-up")


def test_upload_size_limit(store, provider):
    s = AiService(store, provider, convert_cost=1, max_upload_bytes=100)
    u, _ = store.create_user(1)
    with pytest.raises(ServiceError) as e:
        s.create_profile(u, name="me", consent=True, sample_wav=make_wav(500))
    assert code(e) == "file_too_large"
