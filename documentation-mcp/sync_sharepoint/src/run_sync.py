import os
import json
import subprocess
import sys
import argparse
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
  sharepoint_doc_sources = [s for s in sources if s.get("sharepointFilesSync")]
  sharepoint_glossary_sources = [s for s in sources if s.get("sharepointGlossarySync")]
  all_sharepoint_sources = sharepoint_doc_sources + sharepoint_glossary_sources

  if not all_sharepoint_sources:
    print("No SharePoint configurations found in mcp-config.json. Nothing to sync.")
    return

  # 1. Authenticate (use the host and optional window title from the first sharepoint source)
  first_source_config = all_sharepoint_sources[0]
  if first_source_config.get("sharepointFilesSync"):
    sync_data = first_source_config.get("sharepointFilesSync")
    first_source = sync_data["toolkitSourceUrl"]
  else:
    first_source = first_source_config["sharepointGlossarySync"]["listUrl"]

  if "://" in first_source:
    host = first_source.split("://")[1].split("/")[0]
  else:
    path_parts = [p for p in first_source.replace('\\', '/').split('/') if p]
    host = path_parts[0].split('@')[0] if path_parts else args.host

  window_title = "Sync files with Toolkit - SharePoint Login Required"

  print(f"Step 1: Authenticating to SharePoint ({host})...")
  authenticate(host, window_title=window_title)

  # 2. Run syncs dynamically in parallel
  def run_single_sync(idx, source_config):
    sync_config_name = source_config.get("mountedPath", "Unknown")

    try:
      if "sharepointGlossarySync" in source_config:
        sync_data = source_config["sharepointGlossarySync"]
        list_url = sync_data["listUrl"]
        api_url = sync_data["apiUrl"]
        dest = sync_data["localDestinationPath"]

        result = subprocess.run([
          sys.executable, os.path.join(SCRIPT_DIR, "sync_glossary.py"),
          "--dest", dest,
          "--api-url", api_url,
          "--list-url", list_url
        ], check=True, capture_output=True, text=True)
      else:
        sync_data = source_config.get("sharepointFilesSync")
        source_path = sync_data["toolkitSourceUrl"]
        dest = sync_data["localDestinationPath"]

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
  print(f"\nStarting {len(all_sharepoint_sources)} synchronizations in parallel...")
  with concurrent.futures.ThreadPoolExecutor(max_workers=len(all_sharepoint_sources)) as executor:
    futures = {executor.submit(run_single_sync, idx, sc): sc for idx, sc in enumerate(all_sharepoint_sources, 1)}

    for future in concurrent.futures.as_completed(futures):
      idx, sync_config_name, success, stdout, stderr = future.result()
      print(f"\n{'='*50}\nResults for: {sync_config_name}\n{'='*50}")
      if stdout:
        print(stdout.strip())
      if not success:
        if stderr:
          print(stderr.strip())
        print(f"\n\033[91m>>> ERROR: Synchronization failed for {sync_config_name}\033[0m")
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
