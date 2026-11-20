package com.lethargicrunner.grokbrowserinspector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var documentStartCapture = false
    private var captureEnabled = false
    private var pendingSampleFile: File? = null

    private val exportRequestCode = 4102

    private val journalFile: File
        get() = File(File(filesDir, "network-detection"), "events.jsonl")

    private val payloadFile: File
        get() = File(File(filesDir, "network-detection"), "conversation_payloads.jsonl")

    private val payloadExportRequestCode = 4103
    private val sampleExportRequestCode = 4104

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
              if (!/^\/rest\/app-chat\/[A-Za-z0-9_./-]+$/.test(u.pathname)) return;
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

          function reportPayload(url, status, contentType, method, body) {
            try {
              const u = new URL(url, location.href);
              if (u.origin !== "https://grok.com") return;

              const m = String(method || "GET").toUpperCase();
              const isV2 = /^\/rest\/app-chat\/conversations_v2\/[A-Za-z0-9_-]+$/.test(u.pathname);
              const isNode = /^\/rest\/app-chat\/conversations\/[A-Za-z0-9_-]+\/response-node$/.test(u.pathname);
              const isLoad = /^\/rest\/app-chat\/conversations\/[A-Za-z0-9_-]+\/load-responses$/.test(u.pathname);

              if (!(isV2 && m === "GET") &&
                  !(isNode && m === "GET") &&
                  !(isLoad && m === "POST")) return;

              if (Number(status) !== 200) return;
              if (!String(contentType || "").toLowerCase().includes("application/json")) return;
              if (typeof body !== "string" || body.length === 0 || body.length > 1000000) return;

              JSON.parse(body);

              if (!window.GrokInspector) return;
              window.GrokInspector.postMessage(JSON.stringify({
                type: "conversation_payload",
                path: u.pathname,
                method: m,
                status: 200,
                body: body
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

              try {
                const contentType = response.headers.get("content-type") || "";
                if (response.status === 200 &&
                    contentType.toLowerCase().includes("application/json")) {
                  response.clone().text().then(body => {
                    reportPayload(
                      response.url || url,
                      response.status,
                      contentType,
                      method,
                      body
                    );
                  }).catch(() => {});
                }
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

                const contentType = this.getResponseHeader("content-type") || "";
                if (this.status === 200 &&
                    contentType.toLowerCase().includes("application/json") &&
                    (!this.responseType || this.responseType === "text")) {
                  reportPayload(
                    this.responseURL || this.__grokInspectorUrl,
                    this.status,
                    contentType,
                    this.__grokInspectorMethod,
                    this.responseText
                  );
                }
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val diagnosticButton = Button(this).apply {
            text = "Diagnostic"
            setOnClickListener { showDiagnostic() }
        }

        val exportButton = Button(this).apply {
            text = "Exporter le journal"
            setOnClickListener { exportJournal() }
        }

        val payloadExportButton = Button(this).apply {
            text = "Exporter JSON"
            setOnClickListener { exportPayloads() }
        }

        controls.addView(
            diagnosticButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        controls.addView(
            exportButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        controls.addView(
            payloadExportButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        root.addView(
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val campaignControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val captureButton = Button(this).apply {
            text = "Démarrer capture"
            setOnClickListener {
                captureEnabled = !captureEnabled
                text = if (captureEnabled) "Arrêter capture" else "Démarrer capture"
                Toast.makeText(
                    this@MainActivity,
                    if (captureEnabled) "Capture des conversations activée" else "Capture des conversations arrêtée",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val sampleButton = Button(this).apply {
            text = "Exporter échantillon (5)"
            setOnClickListener { exportSample() }
        }

        campaignControls.addView(captureButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        campaignControls.addView(sampleButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(campaignControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

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

                    message.data?.let {
                        if (it.contains("\"type\":\"conversation_payload\"")) {
                            recordPayload(it)
                        } else {
                            recordDetection(it)
                        }
                    }
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
                "^/rest/app-chat/[A-Za-z0-9_./-]+$"
            ).matches(path)) return

            val status = received.optInt("status")

            // L'identifiant réel de conversation n'est pas conservé.
            val event = JSONObject()
                .put("detected_at_epoch_ms", System.currentTimeMillis())
                .put("path", path
                    .replace(Regex("(/conversations(?:_v2)?/)[A-Za-z0-9_-]+")) { match ->
                        match.groupValues[1] + "[ID]"
                    }
                    .replace(Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "[ID]"))
                .put("status", status)
                .put("method", received.optString("method"))
                .put("content_type", received.optString("contentType"))
                .put("content_length", received.optString("contentLength"))

            val directory = journalFile.parentFile
            if (directory != null && !directory.exists()) {
                directory.mkdirs()
            }

            journalFile.appendText(event.toString() + "\n")

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

    private fun recordPayload(data: String) {
        if (!captureEnabled) return
        try {
            if (data.toByteArray(Charsets.UTF_8).size > 1_100_000) return

            val received = JSONObject(data)
            if (received.optString("type") != "conversation_payload") return

            val path = received.optString("path")
            val method = received.optString("method").uppercase()

            val isV2 = Regex(
                "^/rest/app-chat/conversations_v2/[A-Za-z0-9_-]+$"
            ).matches(path)
            val isNode = Regex(
                "^/rest/app-chat/conversations/[A-Za-z0-9_-]+/response-node$"
            ).matches(path)
            val isLoad = Regex(
                "^/rest/app-chat/conversations/[A-Za-z0-9_-]+/load-responses$"
            ).matches(path)

            if (!(isV2 && method == "GET") &&
                !(isNode && method == "GET") &&
                !(isLoad && method == "POST")) return

            if (received.optInt("status") != 200) return

            val body = received.optString("body")
            if (body.isBlank() || body.toByteArray(Charsets.UTF_8).size > 1_000_000) return

            val parsedBody = org.json.JSONTokener(body).nextValue()

            val conversationId = if (isV2) {
                path.substringAfterLast("/")
            } else {
                path.removePrefix("/rest/app-chat/conversations/").substringBefore("/")
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(conversationId.toByteArray(Charsets.UTF_8))
            val conversationKey = digest.joinToString("") { "%02x".format(it) }

            val safePath = path.replace(
                Regex("(/conversations(?:_v2)?/)[A-Za-z0-9_-]+")
            ) { match ->
                match.groupValues[1] + "[ID]"
            }

            val event = JSONObject()
                .put("captured_at_epoch_ms", System.currentTimeMillis())
                .put("conversation_key", conversationKey)
                .put("path", safePath)
                .put("method", method)
                .put("status", 200)
                .put("body", parsedBody)

            val directory = payloadFile.parentFile
            if (directory != null && !directory.exists()) {
                directory.mkdirs()
            }

            payloadFile.appendText(event.toString() + "\n")

            runOnUiThread {
                Toast.makeText(
                    this,
                    "Contenu JSON de conversation capturé",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (_: Exception) {
            // Une réponse invalide ne doit pas interrompre le navigateur.
        }
    }

    private fun exportSample() {
        if (!payloadFile.exists() || payloadFile.length() == 0L) {
            Toast.makeText(this, "Aucune conversation capturée", Toast.LENGTH_SHORT).show()
            return
        }
        val rows = payloadFile.readLines().mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                val key = json.optString("conversation_key")
                if (key.isBlank()) null else Pair(line, key)
            }.getOrNull()
        }
        val selectedKeys = rows.map { it.second }.distinct().take(5).toSet()
        if (selectedKeys.isEmpty()) {
            Toast.makeText(this, "Aucune capture compatible. Démarre une capture puis ouvre des conversations.", Toast.LENGTH_LONG).show()
            return
        }
        val output = StringBuilder()
        var writtenBytes = 0L
        for (line in rows.filter { it.second in selectedKeys }.map { it.first }) {
            val size = line.toByteArray(Charsets.UTF_8).size.toLong() + 1L
            if (writtenBytes + size > 20L * 1024L * 1024L) break
            output.append(line).append('\n')
            writtenBytes += size
        }
        val sampleFile = File(cacheDir, "grok_sample_5.jsonl")
        sampleFile.writeText(output.toString(), Charsets.UTF_8)
        pendingSampleFile = sampleFile
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "grok_sample_5.jsonl")
        }
        Toast.makeText(this, "Échantillon prêt : ${selectedKeys.size} conversations, limite 20 Mo", Toast.LENGTH_LONG).show()
        startActivityForResult(intent, sampleExportRequestCode)
    }

    private fun showDiagnostic() {
        val events = if (journalFile.exists()) {
            journalFile.readLines().filter { it.isNotBlank() }
        } else {
            emptyList()
        }

        val parsed = events.mapNotNull {
            runCatching { JSONObject(it) }.getOrNull()
        }

        val successCount = parsed.count { it.optInt("status") == 200 }
        val errorCount = parsed.count { it.optInt("status") >= 400 }
        val payloadCount = if (payloadFile.exists()) {
            payloadFile.readLines().count { it.isNotBlank() }
        } else {
            0
        }

        val formatter = SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss",
            Locale.getDefault()
        )

        val latest = parsed.takeLast(10).map { event ->
            val date = formatter.format(
                Date(event.optLong("detected_at_epoch_ms"))
            )
            "$date | HTTP ${event.optInt("status")} | " +
                "${event.optString("method")} | " +
                event.optString("path")
        }

        val report = buildString {
            appendLine("DIAGNOSTIC RÉSEAU")
            appendLine()
            appendLine("Événements enregistrés : ${parsed.size}")
            appendLine("Réponses HTTP 200 : $successCount")
            appendLine("Réponses HTTP >= 400 : $errorCount")
            appendLine("Corps JSON capturés : $payloadCount")
            appendLine("Fichier présent : ${journalFile.exists()}")
            appendLine()
            appendLine("10 DERNIERS ÉVÉNEMENTS")
            appendLine()

            if (latest.isEmpty()) {
                appendLine("Aucun événement enregistré.")
            } else {
                latest.forEach {
                    appendLine(it)
                    appendLine()
                }
            }

            appendLine("Les corps JSON sont conservés séparément dans le stockage privé de l'application.")
        }

        val textView = TextView(this).apply {
            text = report
            textSize = 14f
            setPadding(32, 24, 32, 24)
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        AlertDialog.Builder(this)
            .setTitle("Grok Inspector")
            .setView(scrollView)
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun exportJournal() {
        if (!journalFile.exists()) {
            Toast.makeText(
                this,
                "Aucun journal à exporter",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "events.jsonl")
        }

        startActivityForResult(intent, exportRequestCode)
    }

    private fun exportPayloads() {
        if (!payloadFile.exists() || payloadFile.length() == 0L) {
            Toast.makeText(
                this,
                "Aucun contenu JSON capturé pour le moment",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "conversation_payloads.jsonl")
        }

        startActivityForResult(intent, payloadExportRequestCode)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != exportRequestCode &&
            requestCode != payloadExportRequestCode &&
            requestCode != sampleExportRequestCode) return

        if (resultCode != RESULT_OK) {
            if (requestCode == sampleExportRequestCode) {
                pendingSampleFile?.delete()
                pendingSampleFile = null
            }
            return
        }

        val uri = data?.data
        if (uri == null) {
            if (requestCode == sampleExportRequestCode) {
                pendingSampleFile?.delete()
                pendingSampleFile = null
            }
            return
        }
        val sourceFile = when (requestCode) {
            payloadExportRequestCode -> payloadFile
            sampleExportRequestCode -> pendingSampleFile ?: throw IllegalStateException("Échantillon temporaire introuvable")
            else -> journalFile
        }

        try {
            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Ouverture du fichier impossible")

            output.use { destination ->
                sourceFile.inputStream().use { source ->
                    source.copyTo(destination)
                }
            }

            Toast.makeText(
                this,
                when (requestCode) {
                    payloadExportRequestCode -> "Contenus JSON exportés avec succès"
                    sampleExportRequestCode -> "Échantillon exporté avec succès"
                    else -> "Journal exporté avec succès"
                },
                Toast.LENGTH_LONG
            ).show()

        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Erreur export : ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        } finally {
            if (requestCode == sampleExportRequestCode) {
                pendingSampleFile?.delete()
                pendingSampleFile = null
            }
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
