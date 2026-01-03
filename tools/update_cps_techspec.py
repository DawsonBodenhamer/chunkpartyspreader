import os
import sys
import json
import subprocess
import fnmatch
import re
import argparse
import time

# --- Constants / Defaults ---
CONFIG_FILENAME = "techspec_config.json"
STATS_FILENAME = "cps_run_stats.json"

DEFAULT_MARKER_START = "## CPS Provided Code"
DEFAULT_MARKER_END = "End CPS Provided Code"

DEFAULT_BINARY_EXTENSIONS = {".png", ".ogg"}

# --- Globals ---
VERBOSE = False

# Set from config at runtime
MARKER_START = DEFAULT_MARKER_START
MARKER_END = DEFAULT_MARKER_END
BINARY_EXTENSIONS = set(DEFAULT_BINARY_EXTENSIONS)

# Used by semantic sorter (computed from config)
FEATURE_KEYWORDS = []


def print_error(msg):
    print(f"ERROR: {msg}")


def print_info(msg):
    print(f"INFO: {msg}")


def print_verbose(msg):
    if VERBOSE:
        print(f"  {msg}")


def load_config():
    """Load and validate configuration."""
    print_verbose("Loading configuration...")
    script_dir = os.path.dirname(os.path.abspath(__file__))
    config_path = os.path.join(script_dir, CONFIG_FILENAME)

    if not os.path.exists(config_path):
        print_error(f"Config file not found at {config_path}")
        sys.exit(1)

    try:
        with open(config_path, "r", encoding="utf-8") as f:
            config = json.load(f)
    except json.JSONDecodeError as e:
        print_error(f"Failed to parse config JSON: {e}")
        sys.exit(1)

    # Validate required fields
    required = [
        "root_dir",
        "techspec_pattern",
        "backup_pattern",
        "include_extensions",
        "exclude_patterns",
        "force_include_files",
    ]

    for req in required:
        if req not in config:
            print_error(f"Missing required config field: {req}")
            sys.exit(1)

    # Normalize root_dir
    config["root_dir"] = os.path.abspath(config["root_dir"])
    if not os.path.isdir(config["root_dir"]):
        print_error(f"root_dir does not exist: {config['root_dir']}")
        sys.exit(1)

    return config


def apply_optional_config(config):
    """
    Optional config fields supported (non-breaking):
      - marker_start, marker_end
      - binary_extensions (list[str]) e.g. [".png", ".ogg", ".nbt"]
      - mod_id (string) e.g. "chunkpartyspreader"
      - base_package_paths (list[str]) e.g. ["src/main/java/com/dawson/chunkpartyspreader/"]
      - feature_keywords (list[str]) full override for semantic sorting keyword roots
    """
    global MARKER_START, MARKER_END, BINARY_EXTENSIONS, FEATURE_KEYWORDS

    MARKER_START = config.get("marker_start", DEFAULT_MARKER_START)
    MARKER_END = config.get("marker_end", DEFAULT_MARKER_END)

    bext = config.get("binary_extensions")
    if isinstance(bext, list) and all(isinstance(x, str) for x in bext):
        BINARY_EXTENSIONS = {x.lower() for x in bext}
    else:
        BINARY_EXTENSIONS = set(DEFAULT_BINARY_EXTENSIONS)

    # If user explicitly provides feature_keywords, use them as-is (highest control)
    explicit_keywords = config.get("feature_keywords")
    if isinstance(explicit_keywords, list) and all(isinstance(x, str) for x in explicit_keywords):
        FEATURE_KEYWORDS = [p.replace("\\", "/").lower() for p in explicit_keywords]
        return

    mod_id = config.get("mod_id", "chunkpartyspreader")

    base_package_paths = config.get("base_package_paths")
    if isinstance(base_package_paths, list) and all(isinstance(x, str) for x in base_package_paths) and base_package_paths:
        java_roots = base_package_paths
    else:
        # Reasonable defaults for a Forge single-loader repo
        java_roots = [
            f"src/main/java/com/dawson/{mod_id}/",
            f"src/main/java/net/dawson/{mod_id}/",
        ]

    resource_roots = [
        f"src/main/resources/assets/{mod_id}/",
        f"src/main/resources/data/{mod_id}/",
        "src/main/resources/data/c/",
    ]

    # Normalize to lower + forward slashes for matching
    FEATURE_KEYWORDS = [p.replace("\\", "/").lower() for p in (java_roots + resource_roots)]


def load_previous_stats():
    """Load stats from the previous run to calculate diffs."""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    stats_path = os.path.join(script_dir, STATS_FILENAME)
    if os.path.exists(stats_path):
        try:
            with open(stats_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}
    return {}


def save_current_stats(stats_data):
    """Save current stats to disk."""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    stats_path = os.path.join(script_dir, STATS_FILENAME)
    try:
        with open(stats_path, "w", encoding="utf-8") as f:
            json.dump(stats_data, f, indent=2)
    except Exception as e:
        print_verbose(f"Failed to save stats file: {e}")


def get_git_branch(root_dir):
    """Get current git branch name."""
    print_verbose("Detecting Git branch...")
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            cwd=root_dir,
            capture_output=True,
            text=True,
            check=True,
        )
        branch = result.stdout.strip()
        if not branch:
            raise ValueError("Empty branch name")
        print_verbose(f"Current Branch: {branch}")
        return branch
    except Exception as e:
        print_error(f"Failed to determine Git branch. {e}")
        sys.exit(1)


def sanitize_branch_name(branch_name):
    return branch_name.replace("/", "_")


def load_gitignore(root_dir):
    """Load .gitignore patterns (best-effort, simplified)."""
    gitignore_path = os.path.join(root_dir, ".gitignore")
    patterns = []
    if os.path.exists(gitignore_path):
        try:
            with open(gitignore_path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#"):
                        patterns.append(line)
        except Exception:
            pass
    return patterns


def is_ignored(rel_path, ignore_patterns):
    """Check if a path matches ignore patterns (best-effort, simplified)."""
    for pattern in ignore_patterns:
        # Case 1: Explicit Directory (ends with /)
        if pattern.endswith("/"):
            if rel_path.startswith(pattern):
                return True
            if ("/" + pattern) in rel_path:
                return True

        # Case 2: File Glob OR Implicit Directory (no trailing /)
        else:
            # 2a. Standard Glob Match (file name or full path string)
            if fnmatch.fnmatch(os.path.basename(rel_path), pattern):
                return True
            if fnmatch.fnmatch(rel_path, pattern):
                return True

            # 2b. Implicit Directory Match (treat pattern as a folder)
            if rel_path.startswith(pattern + "/"):
                return True
            if ("/" + pattern + "/") in rel_path:
                return True

    return False


def get_language_id(filename):
    """Map extension to markdown language identifier."""
    ext = os.path.splitext(filename)[1].lower()
    mapping = {
        ".java": "java",
        ".kt": "kotlin",
        ".kts": "kotlin",
        ".json": "json",
        ".yml": "yaml",
        ".yaml": "yaml",
        ".md": "markdown",
        ".txt": "text",
        ".toml": "toml",
        ".cfg": "ini",
        ".gradle": "groovy",
        ".properties": "properties",
        ".mcmeta": "json",
        ".mcfunction": "mcfunction",
        ".py": "python",
        ".bat": "bat",
        ".sh": "bash",
    }
    return mapping.get(ext, "")


def parse_changelog(content):
    """
    Extract the top 3 release sections from CHANGELOG.md.
    Release section: Starts with '## [' and continues until the 4th header or EOF.
    """
    lines = content.splitlines()
    output_lines = []
    section_count = 0
    in_section = False

    release_header_re = re.compile(r"^##\s+\[.*")

    for line in lines:
        if release_header_re.match(line):
            section_count += 1
            if section_count > 3:
                break
            in_section = True
            output_lines.append(line)
            continue
        if in_section:
            output_lines.append(line)

    return "\n".join(output_lines).strip()


def get_semantic_sort_key(rel_path):
    """
    Sorting key:
      (LoaderPriority, RootTypePriority, FeaturePriority, Directory, FilePriority, Filename)

    Uses FEATURE_KEYWORDS (mod-aware) so your actual mod trees cluster together.
    """
    lower = rel_path.lower()
    filename = os.path.basename(lower)
    directory = os.path.dirname(lower)

    # 1) Loader Priority (kept for compatibility with multi-loader repos)
    loader_prio = 99
    if lower in ["changelog.md", "readme.md", "readme_curseforge_style.md"]:
        loader_prio = 0
    elif "/" not in lower:
        loader_prio = 1
    elif lower.startswith("common/"):
        loader_prio = 2
    elif lower.startswith("fabric/"):
        loader_prio = 3
    elif lower.startswith("neoforge/") or lower.startswith("forge/"):
        loader_prio = 4
    elif lower.startswith(".github"):
        loader_prio = 90

    # 2) Root Type Priority
    if "src/main/resources" in lower or "src/main/generated" in lower:
        root_type_prio = 2
    elif "src/main/java" in lower or "src/main/kotlin" in lower:
        root_type_prio = 1
    else:
        root_type_prio = 0

    # 3) Feature Priority
    meta_keywords = [
        "license",
        "readme",
        "fabric.mod.json",
        "mods.toml",
        "neoforge.mods.toml",
        "pack.mcmeta",
        "mixins.json",
        "gradle.properties",
        "settings.gradle",
        "build.gradle",
    ]
    is_meta_file = any(x in filename for x in meta_keywords)

    if is_meta_file:
        feat_prio = -10
        feature_root = "meta"
    else:
        feature_root = ""

        for kw in FEATURE_KEYWORDS:
            if kw in lower:
                idx = lower.find(kw) + len(kw)
                remainder = lower[idx:]

                if "/" in remainder:
                    parts = remainder.split("/")
                    candidate = parts[0]
                    if candidate in ["fabric", "neoforge", "forge"] and len(parts) > 1:
                        feature_root = parts[1]
                    else:
                        feature_root = candidate
                else:
                    feature_root = "root_package"
                break

        if not feature_root:
            if "mixin" in lower:
                feature_root = "mixin"
            elif "src/main/resources/assets" in lower:
                feature_root = "root_assets"
            elif "src/main/resources/data" in lower:
                feature_root = "root_data"

        feat_prio = 50  # Default

        # Priority 0: Entrypoints, Configs, Datagen
        if feature_root in ["root_package", "config", "registry", "init", "main", "datagen"]:
            feat_prio = 0
        # Priority 1: Core Game Objects
        elif feature_root in ["block", "item", "entity", "fluid", "effect", "enchantment", "potion", "sound", "root_assets"]:
            feat_prio = 10
        # Priority 2: Gameplay Systems
        elif feature_root in [
            "advancement",
            "recipe",
            "loot",
            "tag",
            "data",
            "networking",
            "component",
            "command",
            "event",
            "function",
            "data_maps",
            "root_data",
        ]:
            feat_prio = 20
        # Priority 4: World Gen
        elif feature_root in ["world", "biome", "structure", "dimension"]:
            feat_prio = 30
        # Priority 5: Client/Visuals
        elif feature_root in ["client", "screen", "gui", "render", "model", "texture", "particle", "animation", "geo", "blockstates"]:
            feat_prio = 40
        # Priority 90: Tech/Mixins/Compat
        elif feature_root in ["mixin", "integration", "compat", "util", "access", "accessor"]:
            feat_prio = 90
        elif feature_root in ["lang"]:
            feat_prio = 99

    # 4) Filename Weight
    file_prio = 10
    if is_meta_file:
        file_prio = -1
    elif any(x in filename for x in ["registry", "registries", "modblocks", "moditems", "modentities"]):
        file_prio = 0

    return loader_prio, root_type_prio, feat_prio, directory, file_prio, filename


def remove_java_imports(content):
    """
    Removes all import statements and swallows subsequent blank lines,
    then inserts a single placeholder line.
    """
    lines = content.splitlines()
    new_lines = []
    in_import_block = False
    placeholder_added = False

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("import "):
            in_import_block = True
            if not placeholder_added:
                new_lines.append("// (Imports omitted to save token count)")
                placeholder_added = True
            continue
        if in_import_block:
            if stripped == "":
                continue
            else:
                new_lines.append("")
                in_import_block = False
        new_lines.append(line)
    return "\n".join(new_lines)


def format_diff(current, previous):
    """Helper to format '10 (+2)' or '10 (-1)' or '10'."""
    if previous is None:
        return f"{current}"

    diff = current - previous
    if diff > 0:
        return f"{current} (+{diff})"
    if diff < 0:
        return f"{current} ({diff})"
    return f"{current}"


def print_stats_table(full_stats, omitted_stats, prev_full, prev_omitted):
    """Pretty print the statistics with diffs."""
    print("")

    def print_section(title, current_data, prev_data):
        if not current_data:
            return
        print(f"  {title}:")
        sorted_data = sorted(current_data.items(), key=lambda x: (-x[1], x[0]))
        for ext, count in sorted_data:
            prev_count = prev_data.get(ext, 0) if prev_data else None
            count_str = format_diff(count, prev_count)
            display_ext = ext if ext else "(no-ext)"
            print(f"    {display_ext:<10} : {count_str}")

    print_section("Included Content (Full Code)", full_stats, prev_full)
    if full_stats and omitted_stats:
        print("")
    print_section("Omitted Content (Structure Only)", omitted_stats, prev_omitted)


def main():
    start_time = time.time()

    parser = argparse.ArgumentParser()
    parser.add_argument("--structure-only", action="store_true", help="Omit all content.")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose logging.")
    args = parser.parse_args()

    global VERBOSE
    VERBOSE = args.verbose

    print_info("--- CPS Techspec Updater Started ---")

    config = load_config()
    apply_optional_config(config)

    root_dir = config["root_dir"]

    raw_branch = get_git_branch(root_dir)
    sanitized_branch = sanitize_branch_name(raw_branch)

    all_prev_stats = load_previous_stats()
    prev_branch_stats = all_prev_stats.get(raw_branch, {})
    prev_total_files = prev_branch_stats.get("total_files", None)
    prev_full_stats = prev_branch_stats.get("full_stats", {})
    prev_omitted_stats = prev_branch_stats.get("omitted_stats", {})

    techspec_filename = config["techspec_pattern"].replace("{branch}", sanitized_branch)
    backup_filename = config["backup_pattern"].replace("{branch}", sanitized_branch)
    techspec_path = os.path.join(root_dir, techspec_filename)
    backup_path = os.path.join(root_dir, backup_filename)

    if not os.path.exists(techspec_path):
        print_error(f"Tech spec file not found: {techspec_filename}")
        sys.exit(1)

    # --- Branch Specific Exclusions ---
    branch_excludes_map = config.get("branch_specific_excludes", {})
    extra_excludes = []
    print_verbose("Checking branch-specific rules...")
    for pattern, excludes in branch_excludes_map.items():
        if re.search(pattern, raw_branch):
            print_info(f"Branch '{raw_branch}' matches rule '{pattern}'. Excluding: {excludes}")
            extra_excludes.extend(excludes)

    # Discovery
    print_info("Scanning files...")
    gitignore = load_gitignore(root_dir)
    exclude_patterns = config["exclude_patterns"] + extra_excludes
    omit_content_patterns = config.get("omit_content_patterns", [])
    include_exts = set(config["include_extensions"])
    force_include = set(config["force_include_files"])
    forbidden = {techspec_filename, backup_filename}

    included_files = []

    for root, dirs, files in os.walk(root_dir):
        if ".git" in dirs:
            dirs.remove(".git")
        for file in files:
            abs_path = os.path.join(root, file)
            rel_path = os.path.relpath(abs_path, root_dir).replace("\\", "/")
            if rel_path in forbidden:
                continue

            should_include = False
            if rel_path in force_include:
                should_include = True
            else:
                ext = os.path.splitext(file)[1].lower()
                if ext in include_exts:
                    if not is_ignored(rel_path, gitignore) and not is_ignored(rel_path, exclude_patterns):
                        should_include = True

            if should_include:
                included_files.append(rel_path)
                print_verbose(f"[+] {rel_path}")

    # Sort
    print_info("Sorting files (Semantic Priority)...")
    included_files.sort(key=get_semantic_sort_key)

    # Generation
    print_info(f"Generating blocks for {len(included_files)} files...")
    final_output_parts = []
    last_directory = None
    last_was_compact = False

    # Stats Tracking (Current)
    current_full_stats = {}
    current_omitted_stats = {}

    for rel_path in included_files:
        current_directory = os.path.dirname(rel_path)
        filename = os.path.basename(rel_path)
        ext = os.path.splitext(rel_path)[1].lower()

        is_binary = ext in BINARY_EXTENSIONS
        is_omitted = is_ignored(rel_path, omit_content_patterns)
        is_compact = is_binary or is_omitted or args.structure_only

        # Track Stats
        target_stats = current_omitted_stats if is_compact else current_full_stats
        target_stats[ext] = target_stats.get(ext, 0) + 1

        separator = ""
        if current_directory != last_directory:
            if last_directory is not None:
                separator = "\n\n"
            display_dir = current_directory if current_directory else "Repository Root"
            separator += f"### 📂 `{display_dir}/`\n"
            last_directory = current_directory
            last_was_compact = False
        else:
            if is_compact and last_was_compact:
                separator = "\n"
            else:
                separator = "\n\n"

        # Header line for each file
        if is_omitted and not args.structure_only and not is_binary:
            block_content = f"`{filename}` (Content omitted)"
        else:
            block_content = f"`{filename}`"

        note_data = config.get("file_notes", {}).get(rel_path)
        if note_data and note_data.get("position") == "before":
            block_content += "\n" + note_data.get("note", "")

        if not is_compact:
            lang = get_language_id(rel_path)
            abs_path = os.path.join(root_dir, rel_path)
            try:
                with open(abs_path, "r", encoding="utf-8", errors="replace") as f:
                    content = f.read()
                if rel_path == "CHANGELOG.md":
                    content = parse_changelog(content)
                if rel_path.endswith(".java"):
                    content = remove_java_imports(content)
                block_content += f"\n```{lang}\n{content}\n```"
            except Exception as e:
                print_error(f"Read error: {rel_path} ({e})")
                block_content += "\n```text\n(Read Error)\n```"

        if note_data and note_data.get("position") == "after":
            block_content += "\n" + note_data.get("note", "")

        final_output_parts.append(separator + block_content)
        last_was_compact = is_compact

    full_content_body = "".join(final_output_parts)

    # Backup & Write
    print_info(f"Writing to {techspec_path} (Backup: {backup_filename})...")
    try:
        with open(techspec_path, "r", encoding="utf-8") as f:
            original = f.read()

        with open(backup_path, "w", encoding="utf-8") as f:
            f.write(original)

        start_idx = original.find(MARKER_START)
        end_idx = original.find(MARKER_END)

        if start_idx == -1 or end_idx == -1 or end_idx < start_idx:
            raise ValueError("Markers missing or invalid.")

        # Cut after the marker line
        start_cut = original.find("\n", start_idx)
        if start_cut == -1:
            start_cut = start_idx + len(MARKER_START)

        pre = original[:start_cut]
        post = original[end_idx:]
        final_doc = f"{pre}\n\n{full_content_body}\n\n{post}"

        with open(techspec_path, "w", encoding="utf-8") as f:
            f.write(final_doc)

        # Print Report
        total_files = len(included_files)
        total_diff_str = format_diff(total_files, prev_total_files)

        print_info("--- Success ---")
        print(f"  Branch: {raw_branch}")
        print(f"  Files:  {total_diff_str}")

        print_stats_table(current_full_stats, current_omitted_stats, prev_full_stats, prev_omitted_stats)

        print(f"\n  Time:   {round(time.time() - start_time, 2)}s")

        # Save stats for next run
        all_prev_stats[raw_branch] = {
            "timestamp": time.time(),
            "total_files": total_files,
            "full_stats": current_full_stats,
            "omitted_stats": current_omitted_stats,
        }
        save_current_stats(all_prev_stats)

    except Exception as e:
        print_error(f"Operation failed: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
