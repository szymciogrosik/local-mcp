import os
import time
import threading

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

def authenticate(
    host: str = "company.sharepoint.com",
    auth_url: str = None,
    test_path: str = None,
    window_title: str = "Sync files with Toolkit - SharePoint Login Required"
):
  """
  Opens a pywebview window to authenticate the WebClient service for WebDAV paths.
  Closes automatically once authentication is successful.
  """
  # 1. Check if the target WebDAV path is already accessible
  if test_path:
    try:
      if os.path.exists(test_path):
        print(f"SharePoint path is already accessible ({test_path}). Auto-authentication not required.")
        return
    except Exception:
      pass

  try:
    import webview
  except ImportError:
    print("WARNING: pywebview is not installed. Skipping auto-authentication.")
    return

  url = auth_url if auth_url else f"https://{host}"
  print(f"Opening SharePoint login window for {url}...")
  print("Please login if prompted (including MFA). The window will close automatically once connected.")

  has_visited_login = False
  stop_event = threading.Event()
  login_completed = False

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

  def on_loaded(window):
    nonlocal has_visited_login, login_completed
    if login_completed:
      return

    current_url = window.get_current_url()
    if not current_url:
      return

    current_lower = current_url.lower()
    print(f"[DEBUG] Auth window loaded URL: {current_url}")

    # Check if we are currently on an authentication / MFA page
    if any(ind in current_lower for ind in login_indicators):
      has_visited_login = True
      print("[INFO] Authentication / MFA in progress. Please approve any login prompts in the browser...")
      return

    # If we have visited the login page and now returned to the company domain
    if has_visited_login and host.lower() in current_lower:
      print("Successfully authenticated via login redirect! Giving WinINet a moment to save cookies...")
      login_completed = True
      stop_event.set()
      time.sleep(3)
      try:
        window.destroy()
      except Exception:
        pass
      return

    # If test_path was provided, verify if it became accessible
    if test_path:
      try:
        if os.path.exists(test_path):
          print("Successfully authenticated! WebDAV path is now accessible.")
          login_completed = True
          stop_event.set()
          time.sleep(2)
          try:
            window.destroy()
          except Exception:
            pass
          return
      except Exception:
        pass
      print("[INFO] Waiting for SharePoint authentication... If prompted, please sign in.")
    else:
      # If no test_path and hasn't visited login, keep open for user interaction
      print("[INFO] Page loaded. If you need to log in, please complete sign-in in the window.")

  def background_check(window):
    while not stop_event.is_set():
      time.sleep(2)
      if stop_event.is_set():
        break
      if test_path:
        try:
          if os.path.exists(test_path):
            print("Successfully authenticated! WebDAV path is now accessible. Closing window...")
            stop_event.set()
            time.sleep(2)
            try:
              window.destroy()
            except Exception:
              pass
            break
        except Exception:
          pass

  window = webview.create_window(window_title, url)
  window.events.loaded += lambda: on_loaded(window)

  if test_path:
    checker_thread = threading.Thread(target=background_check, args=(window,), daemon=True)
    checker_thread.start()

  try:
    # Force MSHTML engine to share cookies with the Windows WebClient (WinINet) service for WebDAV
    webview.start(gui='mshtml', private_mode=False)
  finally:
    stop_event.set()

if __name__ == "__main__":
  authenticate()
