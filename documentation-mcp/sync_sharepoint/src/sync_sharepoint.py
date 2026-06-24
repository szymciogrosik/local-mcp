import os
import json
import shutil
import time
import argparse
import concurrent.futures
import dataclasses
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "..", "config", "mcp-config.json"))
MAX_WORKERS = 16  # Optimal for network I/O latency (WebDAV/SMB)

def get_long_path(path: str) -> str:
    """Prepends the Windows long path prefix to bypass the 260 character MAX_PATH limit."""
    abs_path = os.path.abspath(path)
    if abs_path.startswith('\\\\?\\'):
        return abs_path
    if abs_path.startswith('\\\\'):
        # Network UNC path
        return '\\\\?\\UNC\\' + abs_path[2:]
    # Local drive path
    return '\\\\?\\' + abs_path

DEST_PATH = r"C:\_documents_copy\default"
CONFIG_FILE_SYNC_CONFIG_NAME = "/default-config-name"
DEFAULT_SUPPORTED_EXTENSIONS = [
    ".docx", ".xlsx", ".pptx", ".pdf", ".csv", ".tsv",
    ".json", ".html", ".xml", ".txt", ".md", ".rtf"
]

@dataclasses.dataclass
class SyncTask:
    src_path: str
    dest_path: str

def process_file(task: SyncTask) -> tuple[str, bool, str]:
    """
    Evaluates and copies a single file.
    Runs concurrently. Returns (destination_path, was_copied, error_message).
    """
    try:
        if os.path.exists(task.dest_path):
            try:
                s_stat = os.stat(task.src_path)
                d_stat = os.stat(task.dest_path)

                # Check size and modification time (2-second tolerance for FAT/Network limits)
                if s_stat.st_size == d_stat.st_size and abs(s_stat.st_mtime - d_stat.st_mtime) < 2.0:
                    return task.dest_path, False, ""
            except OSError:
                pass  # Fallback to force copy if stat fails across network

        shutil.copy2(task.src_path, task.dest_path)
        return task.dest_path, True, ""
    except Exception as e:
        return task.dest_path, False, str(e)


def main():
    global DEST_PATH, CONFIG_FILE_SYNC_CONFIG_NAME, DEFAULT_SUPPORTED_EXTENSIONS
    parser = argparse.ArgumentParser(description="SharePoint to Local Sync Script")
    parser.add_argument("--source", required=True, help="Source path to sync from")
    parser.add_argument("--dest", default=DEST_PATH, help="Destination path to sync to")
    parser.add_argument("--sync-config", default=CONFIG_FILE_SYNC_CONFIG_NAME, help="Target source path configuration name in mcp-config.json")
    parser.add_argument("--extensions", nargs='*', default=DEFAULT_SUPPORTED_EXTENSIONS, help="List of supported extensions to sync (e.g. .docx .pdf)")
    args = parser.parse_args()

    SOURCE_PATH = get_long_path(args.source)
    DEST_PATH = get_long_path(args.dest)
    CONFIG_FILE_SYNC_CONFIG_NAME = args.sync_config
    DEFAULT_SUPPORTED_EXTENSIONS = args.extensions

    print("=" * 50)
    print(" SharePoint to Local Sync Script (Python based)")
    print("=" * 50)

    if not os.path.exists(CONFIG_FILE):
        print(f"ERROR: Config file not found: {CONFIG_FILE}")
        return

    if not os.path.exists(SOURCE_PATH):
        print(f"ERROR: Source path is not accessible right now: {SOURCE_PATH}")
        return

    with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
        config = json.load(f)

    # Match exactly, or allow Git Bash path conversion prefix by checking endswith
    target_source = next((s for s in config.get("sources", []) if s.get("mountedPath") == CONFIG_FILE_SYNC_CONFIG_NAME or CONFIG_FILE_SYNC_CONFIG_NAME.replace('\\', '/').endswith(s.get("mountedPath"))), None)

    if not target_source:
        print(f"ERROR: Could not find configuration for source path '{CONFIG_FILE_SYNC_CONFIG_NAME}'")
        return

    extensions_config = target_source.get("supportedExtensions", DEFAULT_SUPPORTED_EXTENSIONS)
    extensions = {ext.lower() for ext in extensions_config}
    directoryPrefixes = [p.lower() for p in target_source.get("directoryPrefixes", [])]

    print(f"Extensions to sync: {', '.join(extensions)}")
    if directoryPrefixes:
        print(f"Syncing ONLY folders starting with: {', '.join(directoryPrefixes)}")

    os.makedirs(DEST_PATH, exist_ok=True)

    # 1. Sequential local cleanup of unauthorized top-level folders
    for d_folder in os.listdir(DEST_PATH):
        d_path = os.path.join(DEST_PATH, d_folder)
        if os.path.isdir(d_path) and directoryPrefixes:
            if not any(d_folder.lower().startswith(p) for p in directoryPrefixes):
                print(f"Removing unconfigured folder from destination: {d_folder}")
                shutil.rmtree(d_path, ignore_errors=True)

    # 2. Phase 1: Discovery (Concurrent across top-level folders)
    tasks = []
    discovery_folders = []
    for s_folder in os.listdir(SOURCE_PATH):
        s_path = os.path.join(SOURCE_PATH, s_folder)
        if not os.path.isdir(s_path):
            continue

        if directoryPrefixes and not any(s_folder.lower().startswith(p) for p in directoryPrefixes):
            continue

        discovery_folders.append(s_folder)

    def discover_folder(s_folder):
        local_tasks = []
        s_path = os.path.join(SOURCE_PATH, s_folder)
        d_path = os.path.join(DEST_PATH, s_folder)
        os.makedirs(d_path, exist_ok=True)

        for root, _, files in os.walk(s_path):
            rel_path = os.path.relpath(root, s_path)
            dest_root = os.path.join(d_path, rel_path) if rel_path != '.' else d_path

            # Create local directories
            os.makedirs(dest_root, exist_ok=True)

            for file in files:
                if os.path.splitext(file)[1].lower() in extensions:
                    local_tasks.append(SyncTask(
                        src_path=os.path.join(root, file),
                        dest_path=os.path.normpath(os.path.join(dest_root, file))
                    ))
        return local_tasks

    if discovery_folders:
        print(f"Discovering files concurrently across {len(discovery_folders)} top-level folders...")
        with concurrent.futures.ThreadPoolExecutor(max_workers=min(len(discovery_folders), MAX_WORKERS)) as executor:
            for result_tasks in executor.map(discover_folder, discovery_folders):
                tasks.extend(result_tasks)

    # 3. Phase 2: Concurrent Execution
    valid_dest_files = set()
    if tasks:
        print(f"Dispatching {len(tasks)} file checks/copies concurrently...")
        with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
            futures = {executor.submit(process_file, task): task for task in tasks}

            for future in concurrent.futures.as_completed(futures):
                dest_path, was_copied, err_msg = future.result()
                valid_dest_files.add(dest_path)

                if err_msg:
                    print(f"  [ERROR] Failed to process {os.path.basename(dest_path)}: {err_msg}")
                elif was_copied:
                    print(f"  Copied: {os.path.basename(dest_path)}")

    # 4. Phase 3: Purge orphaned files
    for root, _, files in os.walk(DEST_PATH):
        for file in files:
            d_file_path = os.path.normpath(os.path.join(root, file))
            ext = os.path.splitext(file)[1].lower()

            if ext in extensions and d_file_path not in valid_dest_files:
                print(f"  Removing orphaned file: {file}")
                try:
                    os.remove(d_file_path)
                except OSError as e:
                    print(f"  [ERROR] Failed to remove {file}: {e}")

    print("=" * 50)
    print(f" Sync completed successfully for {CONFIG_FILE_SYNC_CONFIG_NAME}!")
    print("=" * 50)

if __name__ == "__main__":
    main()
