
package com.lethargicrunner.grokbrowserinspector

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var documentStartCapture = false

    private val detectionScript = """
        (() => {
          if (window.__grokInspectorDetectionInstalled) return;
          window.__grokInspectorDetectionInstalled = true;

          const endpoint =
            /^\/rest\/app-chat\/conversations_v2\/[A-Za-z0-9_-]+$/;

          function report(url, status, contentType, method, contentLength) {
            try {
              const u = new URL(url, location.href);

              if (u.origin !== "https://grok.com") return;
              if (!endpoint.test(u.pathname)) return;
              if (!window.GrokInspector) return;

              window.GrokInspector.postMessage(JSON.stringify({
                path: u.pathname,
                status: Number(status) || 0,
                contentType: String(contentType || "").slice(0, 100),
                method: String(method || "GET").slice(0, 10),
                contentLength: String(contentLength || "").slice(0, 20)
              }));
            } catch (_) {}
          }

          const originalFetch = window.fetch;

          window.fetch = function(...args) {
            const request = args[0];
            const init = args[1] || {};

            const url = request instanceof Request
              ? request.url : String(request);

            const method = init.method ||
              (request instanceof Request ? request.method : "GET");

            return originalFetch.apply(this, args).then(response => {
              try {
                report(
                  response.url || url,
                  response.status,
                  response.headers.get("content-type"),
                  method,
                  response.headers.get("content-length")
                );
              } catch (_) {}

              return response;
            });
          };

          const originalOpen = XMLHttpRequest.prototype.open;

          XMLHttpRequest.prototype.open = function(method, url, ...rest) {
            this.__grokInspectorMethod = method;
            this.__grokInspectorUrl = url;
            return originalOpen.call(this, method, url, ...rest);
          };

          const originalSend = XMLHttpRequest.prototype.send;

          XMLHttpRequest.prototype.send = function(...args) {
            this.addEventListener("load", () => {
              try {
                report(
                  this.responseURL || this.__grokInspectorUrl,
                  this.status,
                  this.getResponseHeader("content-type"),
                  this.__grokInspectorMethod,
                  this.getResponseHeader("content-length")
                );
              } catch (_) {}
            });

            return originalSend.apply(this, args);
          };
        })();
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        setupDetection()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (!documentStartCapture &&
                    url?.startsWith("https://grok.com") == true
                ) {
                    view?.evaluateJavascript(detectionScript, null)
                }
            }
        }

        webView.webChromeClient = WebChromeClient()
        setContentView(webView)

        if (savedInstanceState == null) {
            webView.loadUrl("https://grok.com/")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun setupDetection() {
        if (!WebViewFeature.isFeatureSupported(
                WebViewFeature.WEB_MESSAGE_LISTENER
            )
        ) {
            Toast.makeText(
                this,
                "Capture réseau non disponible sur ce WebView",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        WebViewCompat.addWebMessageListener(
            webView,
            "GrokInspector",
            setOf("https://grok.com"),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: androidx.webkit.JavaScriptReplyProxy
                ) {
                    if (!isMainFrame) return
                    if (sourceOrigin.scheme != "https") return
                    if (sourceOrigin.host != "grok.com") return

                    message.data?.let { recordDetection(it) }
                }
            }
        )

        if (WebViewFeature.isFeatureSupported(
                WebViewFeature.DOCUMENT_START_SCRIPT
            )
        ) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                detectionScript,
                setOf("https://grok.com")
            )
            documentStartCapture = true
        }
    }

    private fun recordDetection(data: String) {
        try {
            val received = JSONObject(data)
            val path = received.optString("path")

            if (!Regex(
                "^/rest/app-chat/conversations_v2/[A-Za-z0-9_-]+$"
            ).matches(path)) return

            val status = received.optInt("status")

            val event = JSONObject()
                .put("detected_at_epoch_ms", System.currentTimeMillis())
                .put("path", path)
                .put("status", status)
                .put("method", received.optString("method"))
                .put("content_type", received.optString("contentType"))
                .put("content_length", received.optString("contentLength"))

            val directory = File(filesDir, "network-detection")
            if (!directory.exists()) directory.mkdirs()

            File(directory, "events.jsonl").appendText(
                event.toString() + "\n"
            )

            runOnUiThread {
                Toast.makeText(
                    this,
                    "Réponse conversation détectée : HTTP $status",
                    Toast.LENGTH_SHORT
                ).show()
            }

        } catch (_: Exception) {
            // Une donnée invalide ne doit pas interrompre le navigateur.
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
