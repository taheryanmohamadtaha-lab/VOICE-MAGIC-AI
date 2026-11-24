"""Operator CLI. Usage:  python -m app.admin create-user --credits 20 [--premium]"""
from __future__ import annotations

import argparse

from app.config import Settings
from services.store import Store


def main() -> None:
    ap = argparse.ArgumentParser(prog="app.admin")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("create-user", help="create a user and print their access token (shown once)")
    c.add_argument("--credits", type=int, default=None)
    c.add_argument("--premium", action="store_true")
    g = sub.add_parser("grant", help="add credits to a user id")
    g.add_argument("user_id")
    g.add_argument("amount", type=int)
    args = ap.parse_args()
    s = Settings.from_env()
    store = Store(s.db_path)
    if args.cmd == "create-user":
        user, token = store.create_user(args.credits if args.credits is not None else s.free_credits, args.premium)
        print(f"user_id: {user.id}\ncredits: {user.credits}\npremium: {user.premium}\n")
        print("ACCESS TOKEN (copy it now, it cannot be shown again):")
        print(token)
    else:
        print("credits now:", store.grant(args.user_id, args.amount, "admin_grant"))


if __name__ == "__main__":
    main()
