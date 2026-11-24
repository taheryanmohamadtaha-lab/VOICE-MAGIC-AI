"""SQLite persistence: users, hashed access tokens, voice profiles and the credit ledger.

Credit changes are atomic (single transaction) and every change is written to a ledger.
Access tokens are stored only as SHA-256 hashes.
"""
from __future__ import annotations

import hashlib
import os
import secrets
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Iterator, Optional


class InsufficientCredits(Exception):
    pass


@dataclass(frozen=True)
class User:
    id: str
    credits: int
    premium: bool


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


SCHEMA = """
CREATE TABLE IF NOT EXISTS users(
  id TEXT PRIMARY KEY, credits INTEGER NOT NULL, premium INTEGER NOT NULL DEFAULT 0, created_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS tokens(
  token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE);
CREATE TABLE IF NOT EXISTS profiles(
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL, provider_profile_id TEXT NOT NULL, consent_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS ledger(
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, ts REAL NOT NULL,
  delta INTEGER NOT NULL, reason TEXT NOT NULL);
"""


class Store:
    def __init__(self, path: str):
        if path != ":memory:":
            os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self._lock = threading.Lock()
        self._db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._db.execute("PRAGMA foreign_keys=ON")
        self._db.executescript(SCHEMA)

    @contextmanager
    def _tx(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                yield self._db
                self._db.execute("COMMIT")
            except BaseException:
                self._db.execute("ROLLBACK")
                raise

    # ---- users / tokens
    def create_user(self, credits: int, premium: bool = False) -> tuple[User, str]:
        """Returns the user and the plaintext token. The token is shown once and never stored."""
        uid = uuid.uuid4().hex
        token = "vma_" + secrets.token_urlsafe(32)
        with self._tx() as db:
            db.execute("INSERT INTO users VALUES(?,?,?,?)", (uid, credits, int(premium), time.time()))
            db.execute("INSERT INTO tokens VALUES(?,?)", (hash_token(token), uid))
            if credits:
                db.execute("INSERT INTO ledger(user_id,ts,delta,reason) VALUES(?,?,?,?)", (uid, time.time(), credits, "initial_grant"))
        return User(uid, credits, premium), token

    def user_for_token(self, token: str) -> Optional[User]:
        with self._lock:
            row = self._db.execute(
                "SELECT u.id,u.credits,u.premium FROM tokens t JOIN users u ON u.id=t.user_id WHERE t.token_hash=?",
                (hash_token(token),),
            ).fetchone()
        return User(row[0], row[1], bool(row[2])) if row else None

    def get_user(self, user_id: str) -> Optional[User]:
        with self._lock:
            row = self._db.execute("SELECT id,credits,premium FROM users WHERE id=?", (user_id,)).fetchone()
        return User(row[0], row[1], bool(row[2])) if row else None

    def set_premium(self, user_id: str, premium: bool) -> None:
        with self._tx() as db:
            db.execute("UPDATE users SET premium=? WHERE id=?", (int(premium), user_id))

    # ---- credits (server-side only; the app can never set its own balance)
    def spend(self, user_id: str, amount: int, reason: str) -> int:
        with self._tx() as db:
            row = db.execute("SELECT credits FROM users WHERE id=?", (user_id,)).fetchone()
            if row is None or row[0] < amount:
                raise InsufficientCredits()
            db.execute("UPDATE users SET credits=credits-? WHERE id=?", (amount, user_id))
            db.execute("INSERT INTO ledger(user_id,ts,delta,reason) VALUES(?,?,?,?)", (user_id, time.time(), -amount, reason))
            return row[0] - amount

    def grant(self, user_id: str, amount: int, reason: str) -> int:
        with self._tx() as db:
            db.execute("UPDATE users SET credits=credits+? WHERE id=?", (amount, user_id))
            db.execute("INSERT INTO ledger(user_id,ts,delta,reason) VALUES(?,?,?,?)", (user_id, time.time(), amount, reason))
            return db.execute("SELECT credits FROM users WHERE id=?", (user_id,)).fetchone()[0]

    def ledger(self, user_id: str) -> list[tuple[float, int, str]]:
        with self._lock:
            return list(self._db.execute("SELECT ts,delta,reason FROM ledger WHERE user_id=? ORDER BY id", (user_id,)))

    # ---- profiles
    def add_profile(self, user_id: str, name: str, provider_profile_id: str) -> str:
        pid = uuid.uuid4().hex
        with self._tx() as db:
            db.execute("INSERT INTO profiles VALUES(?,?,?,?,?)", (pid, user_id, name, provider_profile_id, time.time()))
        return pid

    def get_profile(self, user_id: str, profile_id: str) -> Optional[tuple[str, str]]:
        """Returns (name, provider_profile_id) only if the profile belongs to the user."""
        with self._lock:
            row = self._db.execute(
                "SELECT name,provider_profile_id FROM profiles WHERE id=? AND user_id=?", (profile_id, user_id)
            ).fetchone()
        return (row[0], row[1]) if row else None

    def delete_profile(self, user_id: str, profile_id: str) -> None:
        with self._tx() as db:
            db.execute("DELETE FROM profiles WHERE id=? AND user_id=?", (profile_id, user_id))

    def provider_ids(self, user_id: str) -> list[str]:
        with self._lock:
            return [r[0] for r in self._db.execute("SELECT provider_profile_id FROM profiles WHERE user_id=?", (user_id,))]

    def delete_user(self, user_id: str) -> None:
        with self._tx() as db:
            db.execute("DELETE FROM profiles WHERE user_id=?", (user_id,))
            db.execute("DELETE FROM tokens WHERE user_id=?", (user_id,))
            db.execute("DELETE FROM ledger WHERE user_id=?", (user_id,))
            db.execute("DELETE FROM users WHERE id=?", (user_id,))
