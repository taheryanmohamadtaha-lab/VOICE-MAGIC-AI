"""Purchase validation interface.

Real validation needs Google Play Developer API credentials (a service account) which only the
server operator can provide. Until one is implemented and configured, validation is refused
instead of faked: nobody can obtain credits or premium by calling this endpoint.
"""
from __future__ import annotations

from abc import ABC, abstractmethod


class PurchaseValidator(ABC):
    configured: bool = False

    @abstractmethod
    def validate(self, *, product_id: str, purchase_token: str) -> dict:
        """Return {'premium': bool, 'credits': int} for a genuinely verified purchase; raise otherwise."""


class NotConfiguredValidator(PurchaseValidator):
    configured = False

    def validate(self, *, product_id: str, purchase_token: str) -> dict:
        raise NotImplementedError
