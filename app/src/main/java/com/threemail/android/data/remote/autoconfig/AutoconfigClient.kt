package com.threemail.android.data.remote.autoconfig

import com.threemail.android.domain.model.Security
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Thunderbird-style autoconfig discovery. Given an email address, tries the
 * standard autoconfig locations for the domain and parses the returned
 * `clientConfig` XML into IMAP/SMTP settings, so an unknown provider can still
 * be added with just email + password.
 *
 * Lookup order (first success wins), mirroring Thunderbird/K-9:
 *  1. `https://autoconfig.<domain>/mail/config-v1.1.xml?emailaddress=<email>`
 *  2. `https://<domain>/.well-known/autoconfig/mail/config-v1.1.xml?emailaddress=<email>`
 *  3. Mozilla's ISPDB: `https://autoconfig.thunderbird.net/v1.1/<domain>`
 *
 * Network / parse failures return null; the caller falls back to a built-in
 * preset or manual entry.
 */
@Singleton
class AutoconfigClient @Inject constructor() {

    data class DiscoveredConfig(
        val displayName: String?,
        val imapHost: String,
        val imapPort: Int,
        val imapSecurity: Security,
        val smtpHost: String,
        val smtpPort: Int,
        val smtpSecurity: Security,
        /** True when the incoming server's username template was the full email. */
        val usernameIsEmail: Boolean
    )

    suspend fun discover(email: String): DiscoveredConfig? = withContext(Dispatchers.IO) {
        val domain = email.substringAfter('@', "").lowercase().takeIf { it.isNotBlank() }
            ?: return@withContext null
        val enc = URLEncoder.encode(email, "UTF-8")
        val urls = listOf(
            "https://autoconfig.$domain/mail/config-v1.1.xml?emailaddress=$enc",
            "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml?emailaddress=$enc",
            "https://autoconfig.thunderbird.net/v1.1/$domain"
        )
        for (url in urls) {
            val body = fetch(url) ?: continue
            val cfg = runCatching { parse(body) }.getOrNull() ?: continue
            return@withContext cfg
        }
        null
    }

    private fun fetch(url: String): String? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        val conn = (parsed.openConnection() as? HttpURLConnection) ?: return null
        conn.requestMethod = "GET"
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/xml, text/xml")
        conn.setRequestProperty("User-Agent", "3mail/1.0 (+autoconfig)")
        return runCatching {
            if (conn.responseCode in 200..299) {
                conn.inputStream?.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                null
            }
        }.getOrNull().also { conn.disconnect() }
    }

    private fun parse(xml: String): DiscoveredConfig? {
        val doc = buildSecureParser().parse(InputSource(StringReader(xml)))
        val imap = firstServer(doc.getElementsByTagName("incomingServer"), "imap") ?: return null
        val smtp = firstServer(doc.getElementsByTagName("outgoingServer"), "smtp") ?: return null

        val imapHost = childText(imap, "hostname") ?: return null
        val imapPort = childText(imap, "port")?.toIntOrNull() ?: return null
        val smtpHost = childText(smtp, "hostname") ?: return null
        val smtpPort = childText(smtp, "port")?.toIntOrNull() ?: return null

        return DiscoveredConfig(
            displayName = textOf(doc, "displayName"),
            imapHost = imapHost,
            imapPort = imapPort,
            imapSecurity = socketSecurity(childText(imap, "socketType")),
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpSecurity = socketSecurity(childText(smtp, "socketType")),
            usernameIsEmail = (childText(imap, "username") ?: "")
                .contains("%EMAILADDRESS%", ignoreCase = true)
        )
    }

    /**
     * DocumentBuilder hardened against XXE - the XML comes from a domain the
     * user is about to trust with mail, but not yet, so disable DTDs and
     * external entities before parsing.
     */
    private fun buildSecureParser() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder()

    private fun firstServer(nodes: NodeList, type: String): Element? {
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            if (el.getAttribute("type").equals(type, ignoreCase = true)) return el
        }
        return null
    }

    private fun childText(parent: Element, tag: String): String? {
        val list = parent.getElementsByTagName(tag)
        for (i in 0 until list.length) {
            val el = list.item(i) as? Element ?: continue
            val text = el.textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun textOf(doc: Document, tag: String): String? {
        val list = doc.getElementsByTagName(tag)
        for (i in 0 until list.length) {
            val el = list.item(i) as? Element ?: continue
            val text = el.textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun socketSecurity(socketType: String?): Security = when (socketType?.uppercase()) {
        "SSL" -> Security.SSL_TLS
        "STARTTLS" -> Security.STARTTLS
        else -> Security.NONE
    }
}
