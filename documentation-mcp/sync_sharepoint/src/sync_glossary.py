import os
import csv
import sys
import argparse
import json
from urllib.parse import urlparse

try:
  import webview
except ImportError:
  print("WARNING: pywebview is not installed. Run 'pip install pywebview'.")
  sys.exit(1)

# These are populated by command-line arguments
DEST = ""
REST_API_URL = ""
COMPANY_NAME = "your_company"

# Set to True to run invisibly in the background. Set to False to see the window.
RUN_IN_BACKGROUND = True

global_window = None
has_fetched = False

def load_company_from_config():
  script_dir = os.path.dirname(os.path.abspath(__file__))
  sync_dir = os.path.normpath(os.path.join(script_dir, ".."))
  for filename in ["sync-config.json", "sync-config-example.json"]:
    config_path = os.path.join(sync_dir, filename)
    if os.path.exists(config_path):
      try:
        with open(config_path, "r", encoding="utf-8") as f:
          data = json.load(f)
          if "your_company" in data and data["your_company"]:
            return data["your_company"]
      except Exception as e:
        print(f"[DEBUG] Error reading {config_path}: {e}")
  return "your_company"

def on_loaded(window):
  global has_fetched, COMPANY_NAME, REST_API_URL
  if has_fetched:
    return

  current_url = window.get_current_url()
  print(f"[DEBUG] on_loaded fired! Current URL: {current_url}")

  expected_domain = COMPANY_NAME if "." in COMPANY_NAME else f"goto.{COMPANY_NAME}.com"

  login_indicators = [
    'login.microsoftonline.com',
    'login.live.com',
    'login.windows.net',
    'msft.sts.microsoft.com',
    'adfs',
    'okta',
    'auth',
    'saml',
    'oauth',
    'signin'
  ]

  domain_matched = False
  if current_url:
    current_lower = current_url.lower()
    if any(ind in current_lower for ind in login_indicators):
      print("[INFO] Authentication / MFA required for Glossary. Displaying browser window...")
      try:
        window.show()
      except Exception:
        pass
      return

    if expected_domain.lower() in current_lower:
      domain_matched = True
    elif COMPANY_NAME != "your_company" and COMPANY_NAME.lower() in current_lower:
      domain_matched = True
    else:
      try:
        target_host = urlparse(REST_API_URL).netloc.lower()
        if target_host and target_host in current_lower:
          domain_matched = True
      except Exception:
        pass

  # As long as we are on the company domain, we can fetch
  if domain_matched:
    has_fetched = True
    print(f"Page loaded ({current_url}). Fetching list data via internal API...")
    js_code = f"""
        fetch("{REST_API_URL}", {{ headers: {{ "Accept": "application/json;odata=nometadata" }} }})
        .then(response => response.json())
        .then(data => {{
            if(data && data.value) {{
                window.pywebview.api.receive_data(data.value);
            }} else {{
                window.pywebview.api.receive_error("No value in response.");
            }}
        }})
        .catch(err => {{
            window.pywebview.api.receive_error(err.toString());
        }});
        """
    window.evaluate_js(js_code)
  else:
    print(f"[DEBUG] URL did not match {expected_domain}, waiting for redirect or login...")

class Api:
  def receive_error(self, err):
    print(f">>> ERROR fetching glossary: {err}")
    if global_window:
      global_window.destroy()

  def receive_data(self, value):
    try:
      if not value:
        print(">>> WARNING: Glossary list is empty.")
      else:
        print(f">>> SUCCESS: Received {len(value)} items from the Glossary!")
        # Debug purposes
        # print(f">>> First item sample: {value[0]}")

        os.makedirs(os.path.dirname(DEST), exist_ok=True)
        keys = value[0].keys()
        exclude = {"odata.type", "odata.id", "odata.editLink", "FileSystemObjectType", "Id", "ServerRedirectedEmbedUri", "ServerRedirectedEmbedUrl", "ContentTypeId", "ComplianceAssetId", "OData__ColorTag", "OData__ExtendedDescription", "OData__IconOverlay", "OData__UIVersionString", "GUID", "AuthorId", "EditorId", "Attachments"}
        headers = [k for k in keys if k not in exclude]

        with open(DEST, 'w', newline='', encoding='utf-8') as f:
          writer = csv.DictWriter(f, fieldnames=headers, extrasaction='ignore')
          writer.writeheader()
          for row in value:
            writer.writerow(row)
        print(f">>> SUCCESS: Glossary saved to {DEST}")
    except Exception as e:
      print(f">>> ERROR writing CSV: {e}")
    finally:
      if global_window:
        global_window.destroy()

def main():
  global global_window, DEST, REST_API_URL, COMPANY_NAME

  parser = argparse.ArgumentParser()
  parser.add_argument("--dest", required=True, help="Destination path for the CSV")
  parser.add_argument("--api-url", required=True, help="REST API endpoint for the list")
  parser.add_argument("--list-url", required=True, help="URL of the list page to open")
  parser.add_argument("--company", default=None, help="Company name for SharePoint URL validation")
  args = parser.parse_args()

  DEST = args.dest
  REST_API_URL = args.api_url
  list_url = args.list_url

  COMPANY_NAME = args.company if args.company else load_company_from_config()

  api = Api()

  global_window = webview.create_window('Fetching Glossary', list_url, js_api=api, hidden=RUN_IN_BACKGROUND)

  global_window.events.loaded += lambda: on_loaded(global_window)

  # We switch to Edge Chromium instead of MSHTML! It can easily render SharePoint and supports fetch.
  try:
    webview.start(gui='edgechromium', private_mode=False)
  except Exception as e:
    print(f">>> Could not start Edge Chromium. Falling back to default... Error: {e}")
    webview.start(private_mode=False)

if __name__ == '__main__':
  main()
