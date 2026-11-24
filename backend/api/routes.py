"""Thin HTTP layer. All rules live in services/."""
from __future__ import annotations

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import Response

from services.ai_service import AiService, ServiceError
from services.store import User

router = APIRouter(prefix="/v1")


def _err(e: ServiceError) -> HTTPException:
    return HTTPException(e.status, {"code": e.code, "message": e.message})


def current_user(request: Request, authorization: str | None = Header(default=None)) -> User:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(401, {"code": "unauthorized", "message": "Missing token."})
    user = request.app.state.store.user_for_token(authorization[7:].strip())
    if user is None:
        raise HTTPException(401, {"code": "unauthorized", "message": "Invalid token."})
    return user


def svc(request: Request) -> AiService:
    return request.app.state.ai


async def _read(upload: UploadFile, limit: int) -> bytes:
    data = await upload.read(limit + 1)
    return data


@router.get("/status")
def status(user: User = Depends(current_user), ai: AiService = Depends(svc)):
    return ai.status(user)


@router.post("/ai/profiles")
async def create_profile(
    name: str = Form(...), consent: str = Form(...), sample: UploadFile = File(...),
    user: User = Depends(current_user), ai: AiService = Depends(svc),
):
    data = await _read(sample, ai.max_upload_bytes)
    try:
        pid = ai.create_profile(user, name=name, consent=consent.lower() == "true", sample_wav=data)
    except ServiceError as e:
        raise _err(e)
    return {"profile_id": pid}


@router.delete("/ai/profiles/{profile_id}")
def delete_profile(profile_id: str, user: User = Depends(current_user), ai: AiService = Depends(svc)):
    ai.delete_profile(user, profile_id)
    return {"deleted": True}


@router.post("/ai/convert")
async def convert(
    profile_id: str = Form(...), audio: UploadFile = File(...),
    user: User = Depends(current_user), ai: AiService = Depends(svc),
):
    data = await _read(audio, ai.max_upload_bytes)
    try:
        out, remaining = ai.convert(user, profile_id=profile_id, audio_wav=data)
    except ServiceError as e:
        raise _err(e)
    return Response(out, media_type="audio/wav", headers={"X-Credits-Remaining": str(remaining), "X-AI-Generated": "true"})


@router.delete("/me")
def delete_me(user: User = Depends(current_user), ai: AiService = Depends(svc)):
    ai.delete_account(user)
    return {"deleted": True}


@router.post("/billing/validate")
def validate_purchase(user: User = Depends(current_user)):
    # Honest refusal: no purchase validator is configured in this build.
    raise HTTPException(501, {"code": "billing_not_configured", "message": "Purchase validation is not configured on this server."})
