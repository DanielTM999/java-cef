// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "life_span_handler.h"

#include <map>
#include <utility>

#include "browser_process_handler.h"
#include "client_handler.h"
#include "jni_util.h"
#include "util.h"
#include "window_branding.h"

namespace {

// Orion fork: popups adopted by a Java browser that were not created yet,
// keyed by (opener browser id, popup_id). The global ref is owned by the queue
// of |handler| until LifeSpanHandler::OnAfterCreated consumes it. Only touched
// on the CEF UI thread.
struct PendingPopup {
  CefRefPtr<LifeSpanHandler> handler;
  jobject jbrowser;
};

std::map<std::pair<int, int>, PendingPopup>& PendingPopups() {
  static std::map<std::pair<int, int>, PendingPopup> pending;
  return pending;
}

void ForgetPendingPopup(jobject jbrowser) {
  auto& pending = PendingPopups();
  for (auto it = pending.begin(); it != pending.end();) {
    if (it->second.jbrowser == jbrowser) {
      it = pending.erase(it);
    } else {
      ++it;
    }
  }
}

}  // namespace

LifeSpanHandler::LifeSpanHandler(JNIEnv* env, jobject handler)
    : handle_(env, handler) {}

// TODO(JCEF): Expose all parameters.
bool LifeSpanHandler::OnBeforePopup(CefRefPtr<CefBrowser> browser,
                                    CefRefPtr<CefFrame> frame,
                                    int popup_id,
                                    const CefString& target_url,
                                    const CefString& target_frame_name,
                                    WindowOpenDisposition target_disposition,
                                    bool user_gesture,
                                    const CefPopupFeatures& popupFeatures,
                                    CefWindowInfo& windowInfo,
                                    CefRefPtr<CefClient>& client,
                                    CefBrowserSettings& settings,
                                    CefRefPtr<CefDictionaryValue>& extra_info,
                                    bool* no_javascript_access) {
  // Orion fork: upstream cancels every popup in off-screen rendering mode
  // before reaching Java, so target="_blank" links and window.open() were
  // silently dropped. In OSR mode the Java handler can now return a browser
  // (onBeforePopupBrowser) that adopts the real popup, which keeps its
  // window.opener relationship (required by OAuth / "Sign in with" flows).
  // Otherwise the Java onBeforePopup is still called (the embedder can open
  // the URL in a new tab) and the native popup is cancelled, because a
  // windowless popup without a render handler would be invisible.
  const bool is_osr = browser->GetHost()->IsWindowRenderingDisabled();

  ScopedJNIEnv env;
  if (!env)
    return is_osr;

  ScopedJNIBrowser jbrowser(env, browser);
  ScopedJNIFrame jframe(env, frame);
  jframe.SetTemporary();
  ScopedJNIString jtargetUrl(env, target_url);
  ScopedJNIString jtargetFrameName(env, target_frame_name);

  if (is_osr) {
    ScopedJNIObjectResult jpopup(env);
    JNI_CALL_METHOD(env, handle_, "onBeforePopupBrowser",
                    "(Lorg/cef/browser/CefBrowser;Lorg/cef/browser/"
                    "CefFrame;Ljava/lang/String;Ljava/lang/String;)Lorg/cef/"
                    "browser/CefBrowser;",
                    Object, jpopup, jbrowser.get(), jframe.get(),
                    jtargetUrl.get(), jtargetFrameName.get());
    if (jpopup) {
      if (AdoptPopup(env, browser, popup_id, jpopup, windowInfo, client,
                     settings, extra_info)) {
        return false;
      }
      // Let the embedder drop the host it prepared; the legacy path below
      // still gets a chance to open the URL.
      ScopedJNIObjectResult jpopupClient(env);
      JNI_CALL_METHOD(env, jpopup, "getClient", "()Lorg/cef/CefClient;",
                      Object, jpopupClient);
      JNI_CALL_VOID_METHOD(env, jpopupClient, "onPopupBrowserAborted",
                           "(Lorg/cef/browser/CefBrowser;)V", jpopup.get());
    }
  }

  jboolean jreturn = JNI_FALSE;

  JNI_CALL_METHOD(env, handle_, "onBeforePopup",
                  "(Lorg/cef/browser/CefBrowser;Lorg/cef/browser/"
                  "CefFrame;Ljava/lang/String;Ljava/lang/String;)Z",
                  Boolean, jreturn, jbrowser.get(), jframe.get(),
                  jtargetUrl.get(), jtargetFrameName.get());

  if (is_osr)
    return true;
  return (jreturn != JNI_FALSE);
}

bool LifeSpanHandler::AdoptPopup(JNIEnv* env,
                                 CefRefPtr<CefBrowser> browser,
                                 int popup_id,
                                 jobject jpopup,
                                 CefWindowInfo& windowInfo,
                                 CefRefPtr<CefClient>& client,
                                 CefBrowserSettings& settings,
                                 CefRefPtr<CefDictionaryValue>& extra_info) {
  // The popup's callbacks go to the client that owns the Java popup browser,
  // not to the opener's client.
  ScopedJNIObjectResult jclient(env);
  JNI_CALL_METHOD(env, jpopup, "getClient", "()Lorg/cef/CefClient;", Object,
                  jclient);
  if (!jclient)
    return false;

  CefRefPtr<ClientHandler> popupClient =
      GetCefFromJNIObject<ClientHandler>(env, jclient, "CefClientHandler");
  if (!popupClient.get())
    return false;

  CefRefPtr<LifeSpanHandler> popupLifeSpan =
      (LifeSpanHandler*)popupClient->GetLifeSpanHandler().get();
  if (!popupLifeSpan.get())
    return false;

  // Released in LifeSpanHandler::OnAfterCreated (or OnBeforePopupAborted).
  jobject globalRef = env->NewGlobalRef(jpopup);
  popupLifeSpan->registerJBrowser(globalRef);
  PendingPopups()[std::make_pair(browser->GetIdentifier(), popup_id)] = {
      popupLifeSpan, globalRef};

  windowInfo.SetAsWindowless(kNullWindowHandle);
  // JCEF requires Alloy runtime style for browsers integrated into Java UI.
  windowInfo.runtime_style = CEF_RUNTIME_STYLE_ALLOY;
  settings.background_color = CefColorSetARGB(255, 255, 255, 255);
  client = popupClient.get();

  auto router_configs = BrowserProcessHandler::GetMessageRouterConfigs();
  if (router_configs) {
    // Send the message router config to CefHelperApp::OnBrowserCreated.
    extra_info = CefDictionaryValue::Create();
    extra_info->SetList("router_configs", router_configs);
  }

  JNI_CALL_VOID_METHOD(env, jpopup, "notifyBrowserCreated", "()V");
  return true;
}

void LifeSpanHandler::OnBeforePopupAborted(CefRefPtr<CefBrowser> browser,
                                           int popup_id) {
  auto& pending = PendingPopups();
  auto it = pending.find(std::make_pair(browser->GetIdentifier(), popup_id));
  if (it == pending.end())
    return;

  CefRefPtr<LifeSpanHandler> popupLifeSpan = it->second.handler;
  jobject jpopup = it->second.jbrowser;
  pending.erase(it);

  if (!popupLifeSpan->takeJBrowser(jpopup))
    return;

  ScopedJNIEnv env;
  if (env) {
    JNI_CALL_VOID_METHOD(env, popupLifeSpan->handle_, "onPopupBrowserAborted",
                         "(Lorg/cef/browser/CefBrowser;)V", jpopup);
    env->DeleteGlobalRef(jpopup);
  }
}

void LifeSpanHandler::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  // Orion fork: replace Chromium's icon/taskbar identity on windows Chromium
  // owns (DevTools, windowed popups). Runs before the early return below
  // because DevTools browsers have no pending Java browser object.
  window_branding::ApplyToBrowser(browser);

  ScopedJNIEnv env;
  if (!env || jbrowsers_.empty())
    return;

  util::AddCefBrowser(browser);

  jobject jbrowser = jbrowsers_.front();
  jbrowsers_.pop_front();
  ForgetPendingPopup(jbrowser);

  CefRefPtr<ClientHandler> client =
      (ClientHandler*)browser->GetHost()->GetClient().get();
  client->OnAfterCreated();

  // Add a reference to |browser| that will be released in
  // LifeSpanHandler::OnBeforeClose.
  if (SetCefForJNIObject(env, jbrowser, browser.get(), "CefBrowser")) {
    JNI_CALL_VOID_METHOD(env, handle_, "onAfterCreated",
                         "(Lorg/cef/browser/CefBrowser;)V", jbrowser);
  }

  // Release the global ref added in CefBrowser_N::create.
  env->DeleteGlobalRef(jbrowser);
}

bool LifeSpanHandler::DoClose(CefRefPtr<CefBrowser> browser) {
  ScopedJNIEnv env;
  if (!env)
    return false;

  ScopedJNIBrowser jbrowser(env, browser);
  jboolean jreturn = JNI_FALSE;

  JNI_CALL_METHOD(env, handle_, "doClose", "(Lorg/cef/browser/CefBrowser;)Z",
                  Boolean, jreturn, jbrowser.get());

  return (jreturn != JNI_FALSE);
}

void LifeSpanHandler::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  REQUIRE_UI_THREAD();

  // Orion fork: drop the popups this browser opened that were never created
  // or aborted. The global refs stay owned by the popup client's queue.
  auto& pending = PendingPopups();
  for (auto it = pending.begin(); it != pending.end();) {
    if (it->first.first == browser->GetIdentifier()) {
      it = pending.erase(it);
    } else {
      ++it;
    }
  }

  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);

  JNI_CALL_VOID_METHOD(env, handle_, "onBeforeClose",
                       "(Lorg/cef/browser/CefBrowser;)V", jbrowser.get());

  // Clear the browser pointer member of the Java object. This will
  // release the browser reference that was added in
  // LifeSpanHandler::OnAfterCreated.
  SetCefForJNIObject<CefBrowser>(env, jbrowser, nullptr, "CefBrowser");

  CefRefPtr<ClientHandler> client =
      (ClientHandler*)browser->GetHost()->GetClient().get();
  client->OnBeforeClose(browser);
}

void LifeSpanHandler::OnAfterParentChanged(CefRefPtr<CefBrowser> browser) {
  REQUIRE_UI_THREAD();
  ScopedJNIEnv env;
  if (!env)
    return;

  ScopedJNIBrowser jbrowser(env, browser);

  JNI_CALL_VOID_METHOD(env, handle_, "onAfterParentChanged",
                       "(Lorg/cef/browser/CefBrowser;)V", jbrowser.get());
}

void LifeSpanHandler::registerJBrowser(jobject browser) {
  jbrowsers_.push_back(browser);
}

void LifeSpanHandler::unregisterJBrowser(jobject browser) {
  jbrowsers_.remove(browser);
}

bool LifeSpanHandler::takeJBrowser(jobject browser) {
  for (auto it = jbrowsers_.begin(); it != jbrowsers_.end(); ++it) {
    if (*it == browser) {
      jbrowsers_.erase(it);
      return true;
    }
  }
  return false;
}
