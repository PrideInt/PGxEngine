import argparse
import os
import sys


# // PATHS

def base_dir():
    """Where pgx.db is expected to sit.

    Frozen by PyInstaller the engine is an executable in a folder of its own,
    and the knowledge base travels beside it. Run from a checkout it is the
    project root, two levels up from this file.
    """
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def find_database(given):
    if given:
        return os.path.abspath(given)

    root = base_dir()
    for candidate in ("pgx.db", os.path.join("data", "pgx.db")):
        path = os.path.join(root, candidate)
        if os.path.exists(path):
            return path
    return os.path.join(root, "pgx.db")


# // ENTRY POINT

def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="pgx-engine",
        description="Serve the PGx knowledge base over HTTP on the loopback interface."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--db", default=None, help="path to pgx.db")
    args = parser.parse_args(argv)

    database = find_database(args.db)
    if not os.path.exists(database):
        print(f"Knowledge base not found: {database}", file=sys.stderr)
        print("Build it with: python -m pgx.query.cpic --genes CYP2C19 CYP2D6 CYP2B6 "
              "--out data/pgx.db", file=sys.stderr)
        return 2

    # pgx.api.main reads PGX_DB when the module is first imported, so this has
    # to be set before the import below, not after.
    os.environ["PGX_DB"] = database

    import uvicorn
    from pgx.api.main import app

    # The client watches for this line to know the port is live; it also tells
    # anyone running the engine by hand where to point a browser.
    print(f"pgx-engine serving {database} on http://{args.host}:{args.port}", flush=True)

    # Loopback only. This is an educational tool holding guideline data, and
    # nothing about it should be reachable from another machine.
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")
    return 0


if __name__ == "__main__":
    sys.exit(main())
