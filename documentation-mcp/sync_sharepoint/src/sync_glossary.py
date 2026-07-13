import os
import csv
import sys
import argparse

try:
  import webview
except ImportError:
  print("WARNING: pywebview is not installed. Run 'pip install pywebview'.")
  sys.exit(1)

# These are populated by command-line arguments
DEST = ""
REST_API_URL = ""

# Set to True to run invisibly in the background. Set to False to see the window.
RUN_IN_BACKGROUND = True

global_window = None
has_fetched = False

def on_loaded(window):
  global has_fetched
  if has_fetched:
    return

  current_url = window.get_current_url()
  print(f"[DEBUG] on_loaded fired! Current URL: {current_url}")

  # As long as we are on the netcompany domain, we can fetch
  if current_url and 'goto.netcompany.com' in current_url:
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
    print("[DEBUG] URL did not match goto.netcompany.com, waiting for redirect or login...")

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
  global global_window, DEST, REST_API_URL

  parser = argparse.ArgumentParser()
  parser.add_argument("--dest", required=True, help="Destination path for the CSV")
  parser.add_argument("--api-url", required=True, help="REST API endpoint for the list")
  parser.add_argument("--list-url", required=True, help="URL of the list page to open")
  args = parser.parse_args()

  DEST = args.dest
  REST_API_URL = args.api_url
  list_url = args.list_url

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
