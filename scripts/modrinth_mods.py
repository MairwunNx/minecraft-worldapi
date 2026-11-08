"""Upload every platform's jar to Modrinth as a version of the project, each with the environment it runs in.

Standard library only: MODRINTH_TOKEN=… python3 -I scripts/modrinth_mods.py --version X.Y.Z --notes release-notes.md
The release workflow runs it after `./gradlew build` and Minotaur's sync of the description. Minotaur can't set a
version's `environment`, which Modrinth asks of every version (and refuses a version without while the project is in
review), so the jars go up through Modrinth's v3 API here: Paper's plugin on the server only, the mods on the client or
the server. With `worldapi.mods` off in gradle.properties, only Paper's jar goes up, as X.Y.Z from worldapi-X.Y.Z.jar.
A version already uploaded is skipped.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROJECT = "worldapi"
API = "https://api.modrinth.com"
AGENT = "MairwunNx/minecraft-worldapi (github.com/MairwunNx)"
PLATFORMS = {
    # module: its name on the version, its loaders, where it runs, the projects it can't run without, and the ones it
    # works with. The mods run on either side: on a dedicated server and under a singleplayer world. Players joining a
    # server with them need nothing on their side, and Kotlin is inside the jar.
    "paper": ("Paper", ["paper", "purpur"], "server_only", [], []),
    "fabric": ("Fabric", ["fabric"], "client_or_server", ["fabric-api"], ["luckperms"]),
    "neoforge": ("NeoForge", ["neoforge"], "client_or_server", [], ["luckperms"]),
}


def request(method, path, token, body=None, headers=None):
    req = urllib.request.Request(f"{API}{path}", data=body, method=method,
                                 headers={"User-Agent": AGENT, "Authorization": token, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        sys.exit(f"{method} {path}: {error.code} {error.read().decode(errors='replace')}")


def project_id(slug, token):
    return request("GET", f"/v2/project/{slug}", token)["id"]


def multipart(data, file_name, file_bytes):
    boundary = uuid.uuid4().hex
    parts = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"data\"\r\nContent-Type: application/json\r\n\r\n".encode(),
        json.dumps(data).encode(), b"\r\n",
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\n"
        f"Content-Type: application/java-archive\r\n\r\n".encode(),
        file_bytes, f"\r\n--{boundary}--\r\n".encode(),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", required=True)
    parser.add_argument("--notes", required=True, help="a Markdown file with the release notes")
    args = parser.parse_args()
    token = os.environ.get("MODRINTH_TOKEN") or sys.exit("MODRINTH_TOKEN isn't set")
    notes = Path(args.notes).read_text(encoding="utf-8")
    project = project_id(PROJECT, token)
    uploaded = {v["version_number"] for v in request("GET", f"/v2/project/{project}/version", token)}

    mods = re.search(r"^worldapi\.mods=true$", (ROOT / "gradle.properties").read_text(), re.M) is not None
    for module, (name, loaders, environment, needs, works_with) in PLATFORMS.items():
        if not mods and module != "paper":
            continue
        number = f"{args.version}+{module}" if mods else args.version
        if number in uploaded:
            print(f"{number}: already on Modrinth")
            continue
        build = (ROOT / module / "build.gradle.kts").read_text()
        game = re.search(r'val minecraft = "([^"]+)"|gameVersions\.addAll\("([^"]+)"\)', build)
        game = game.group(1) or game.group(2)
        jar_name = f"worldapi-{module}-{args.version}.jar" if mods else f"worldapi-{args.version}.jar"
        jar = ROOT / module / "build/libs" / jar_name
        data = {
            "name": f"WorldAPI {args.version} for {name}" if mods else f"WorldAPI {args.version}",
            "version_number": number, "changelog": notes,
            "game_versions": [game], "loaders": loaders, "version_type": "release", "featured": True,
            "project_id": project, "status": "listed", "environment": environment,
            "dependencies": [{"project_id": project_id(slug, token), "dependency_type": "required"} for slug in needs]
            + [{"project_id": project_id(slug, token), "dependency_type": "optional"} for slug in works_with],
            "file_parts": ["file"], "primary_file": "file",
        }
        body, content_type = multipart(data, jar.name, jar.read_bytes())
        version = request("POST", "/v3/version", token, body, {"Content-Type": content_type})
        print(f"{number}: {version['id']}, {version.get('environment')}")


if __name__ == "__main__":
    main()
