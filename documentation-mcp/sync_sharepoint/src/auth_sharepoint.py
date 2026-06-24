import os
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

def authenticate(host: str = "company.sharepoint.com", window_title: str = "Sync files with Toolkit - SharePoint Login Required"):
    """
    Opens a pywebview window to authenticate the WebClient service for WebDAV paths.
    Closes automatically once authentication is successful.
    """
    try:
        import webview
    except ImportError:
        print("WARNING: pywebview is not installed. Skipping auto-authentication.")
        return

    url = f"https://{host}"
    print(f"Opening Sharepoint login window for {url}...")
    print("Please login. The window will close automatically once connected.")

    def on_loaded(window):
        current_url = window.get_current_url()
        if current_url and current_url.startswith(url) and 'login.microsoftonline.com' not in current_url:
            print("Successfully authenticated! Closing window...")
            time.sleep(2)  # Give WinINet a moment to save cookies
            window.destroy()

    profile_dir = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".webview_profile"))
    window = webview.create_window(window_title, url)
    window.events.loaded += lambda: on_loaded(window)
    webview.start(private_mode=False, storage_path=profile_dir)

if __name__ == "__main__":
    authenticate()
