#!/usr/bin/env python3
"""Write newtube.json, the manifest the in-app updater reads (Settings -> About -> Check for updates).

Format (SharedModules appupdatechecker2, UpdateManifest): a "package" object with the download
links, one list per ABI ("downloadUrlList_<Build.SUPPORTED_ABIS[0]>") plus "downloadUrlList" for any
other ABI, each with the size of its file in bytes ("downloadSize_<abi>", "downloadSize" - the update
screen shows it before anything is downloaded), and one object per version name with its
versionCode and changelog lines ("changelog", "changelog_<language>"). The app offers the highest
versionCode and shows the changelog of every listed version newer than the installed one. Every
top-level key but "package" is read as a version, so new fields must go inside "package".

The changelog lines are the bold lead of each bullet of CHANGELOG.md / CHANGELOG.es.md, from the
newest few versions. Usage (release.yml runs it on the built APKs):

    tools/update_manifest.py --tag v1.10.3 --dist dist --out dist/newtube.json
"""
import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO = "steeben/NewTube_Plus"
# x86_64 and anything else get the universal APK (arm64-v8a + armeabi-v7a), the README's "not sure"
# file: x86_64 devices run it through ARM translation. One without translation had no release APK
# to install in the first place (the x86 one runs only where 32-bit x86 is also supported).
ABIS = ["arm64-v8a", "armeabi-v7a", "x86"]
SKIP_SECTIONS = re.compile(r"still limited|behind the scenes|todav[ií]a limitad|sigue limitad|entre bastidores",
                           re.IGNORECASE)
MAX_LINES_PER_VERSION = 6


def version_code(name):
    major, minor, patch = (int(p) for p in name.split("."))
    return major * 10000 + minor * 100 + patch


def plain(text):
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)  # links -> their text
    return re.sub(r"\s+", " ", text.replace("`", "")).strip()


def lead(bullet):
    """The bullet's bold lead, else its first sentence."""
    m = re.match(r"\*\*(.+?)\*\*", bullet)
    if m:
        return plain(m.group(1)).rstrip(":")
    text = plain(bullet.replace("**", ""))
    m = re.match(r"(.+?[.!?])(\s|$)", text)
    return m.group(1) if m else text


def parse_changelog(path):
    """{version name: [lines]} in file order (newest first)."""
    versions, current, section, bullet = {}, None, "", None

    def flush():
        if bullet is not None and current is not None and not SKIP_SECTIONS.search(section):
            versions[current].append(lead(bullet))

    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.startswith("## "):
            flush()
            heading = re.match(r"## (\d+\.\d+\.\d+)\b", raw)
            bullet, section = None, ""
            current = heading.group(1) if heading else None  # "## Unreleased" belongs to no version
            if current:
                versions[current] = []
        elif raw.startswith("### "):
            flush()
            bullet, section = None, raw[4:]
        elif raw.startswith("- "):
            flush()
            bullet = raw[2:]
        elif bullet is not None and raw.startswith("  "):
            bullet += " " + raw.strip()
        else:
            flush()
            bullet = None
    flush()
    return versions


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True, help="release tag, e.g. v1.10.3")
    ap.add_argument("--dist", required=True, help="folder with the release APKs")
    ap.add_argument("--out", required=True)
    ap.add_argument("--versions", type=int, default=4, help="how many versions' changelogs to list")
    args = ap.parse_args()

    name = args.tag[1:] if args.tag.startswith("v") else args.tag
    english = parse_changelog(ROOT / "CHANGELOG.md")
    spanish = parse_changelog(ROOT / "CHANGELOG.es.md")
    newest = next(iter(english), None)
    if newest != name:
        sys.exit(f"CHANGELOG.md starts at {newest}, not {name}")

    gradle = (ROOT / "smarttubetv" / "build.gradle").read_text(encoding="utf-8")
    # The stmobile flavor's pair (defaultConfig still carries upstream's 2387 / "31.97").
    code = re.search(r'versionCode\s+(\d+)\s+versionName\s+"' + re.escape(name) + '"', gradle)
    if not code or int(code.group(1)) != version_code(name):
        sys.exit(f"build.gradle has no versionCode {version_code(name)} for versionName {name}")

    dist = pathlib.Path(args.dist)
    base = f"https://github.com/{REPO}/releases/download/{args.tag}"
    package = {}
    for abi in ABIS:
        apk = f"NewTube_{name}_{abi}.apk"
        if (dist / apk).is_file():
            package[f"downloadUrlList_{abi}"] = [f"{base}/{apk}"]
            package[f"downloadSize_{abi}"] = (dist / apk).stat().st_size
    universal = f"NewTube_{name}_universal.apk"
    if not (dist / universal).is_file():
        sys.exit(f"{universal} is missing from {dist}")
    package["downloadUrlList"] = [f"{base}/{universal}"]
    package["downloadSize"] = (dist / universal).stat().st_size

    manifest = {"package": package}
    for version in list(english)[:args.versions]:
        entry = {"versionCode": version_code(version),
                 "changelog": english[version][:MAX_LINES_PER_VERSION]}
        if spanish.get(version):
            entry["changelog_es"] = spanish[version][:MAX_LINES_PER_VERSION]
        manifest[version] = entry

    pathlib.Path(args.out).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
