import os
import json
import subprocess
import sys
import concurrent.futures
from auth_sharepoint import authenticate

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "..", "config", "mcp-config.json"))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="company.sharepoint.com")
    args = parser.parse_args()

    if not os.path.exists(CONFIG_FILE):
        print(f"ERROR: Config file not found: {CONFIG_FILE}")
        return

    with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
        config = json.load(f)

    sources = config.get("sources", [])
    sharepoint_sources = [s for s in sources if s.get("sharepointSync")]

    if not sharepoint_sources:
        print("No 'sharepointSync' configurations found in mcp-config.json. Nothing to sync.")
        return

    # 1. Authenticate (use the host and optional window title from the first sharepoint source)
    first_sharepoint_sync = sharepoint_sources[0]["sharepointSync"]
    first_source = first_sharepoint_sync["toolkitSourceUrl"]
    path_parts = [p for p in first_source.replace('\\', '/').split('/') if p]
    host = path_parts[0].split('@')[0] if path_parts else args.host
    window_title = "Sync files with Toolkit - SharePoint Login Required"

    print(f"Step 1: Authenticating to SharePoint ({host})...")
    authenticate(host, window_title=window_title)

    # 2. Run syncs dynamically in parallel
    def run_single_sync(idx, source_config):
        sync_data = source_config["sharepointSync"]
        source_path = sync_data["toolkitSourceUrl"]
        dest = sync_data["localDestinationPath"]
        sync_config_name = source_config["mountedPath"]

        try:
            result = subprocess.run([
                sys.executable, os.path.join(SCRIPT_DIR, "sync_sharepoint.py"),
                "--source", source_path,
                "--dest", dest,
                "--sync-config", sync_config_name
            ], check=True, capture_output=True, text=True)
            return (idx, sync_config_name, True, result.stdout, "")
        except subprocess.CalledProcessError as e:
            return (idx, sync_config_name, False, e.stdout, e.stderr)

    has_errors = False
    print(f"\nStarting {len(sharepoint_sources)} synchronizations in parallel...")
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(sharepoint_sources)) as executor:
        futures = {executor.submit(run_single_sync, idx, sc): sc for idx, sc in enumerate(sharepoint_sources, 1)}

        for future in concurrent.futures.as_completed(futures):
            idx, sync_config_name, success, stdout, stderr = future.result()
            print(f"\n{'='*50}\nResults for: {sync_config_name}\n{'='*50}")
            if stdout:
                print(stdout.strip())
            if not success:
                if stderr:
                    print(stderr.strip())
                print(f"\n>>> ERROR: Synchronization failed for {sync_config_name}")
                has_errors = True
            else:
                print(f"\n>>> SUCCESS: {sync_config_name} finished.")

    if has_errors:
        print("\nFinished with errors. Some synchronizations failed!")
        sys.exit(1)
    else:
        print("\nAll synchronizations completed successfully!")

if __name__ == "__main__":
    main()
