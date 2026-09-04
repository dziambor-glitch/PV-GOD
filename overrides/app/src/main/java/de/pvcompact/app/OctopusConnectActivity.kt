package de.pvcompact.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OctopusConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OctopusConnectScreen()
            }
        }
    }
}

@Composable
private fun OctopusConnectScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val store = remember { CredentialStore(context) }
    val existing = remember { store.getOctopusConfig() }

    var accountNumber by rememberSaveable { mutableStateOf(existing.accountNumber) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var success by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Octopus Energy verbinden", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Diese Anmeldung ist eine Übergangslösung für die aktuelle Octopus/Kraken-API. " +
                "E-Mail und Passwort werden nur für den einmaligen Token-Abruf verwendet und nicht gespeichert."
        )

        OutlinedTextField(
            value = accountNumber,
            onValueChange = { accountNumber = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Octopus Kundennummer (z. B. A-12345678)") },
            singleLine = true
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Octopus E-Mail") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Octopus Passwort") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true
        )

        Button(
            onClick = {
                busy = true
                success = false
                status = "Verbindung wird hergestellt …"
                val account = accountNumber.trim()
                val mail = email.trim()
                val pass = password

                Thread {
                    try {
                        val token = OctopusTokenExchange.obtainRefreshToken(mail, pass)
                        val config = store.getOctopusConfig().copy(
                            accountNumber = account,
                            apiKey = "",
                            refreshToken = token
                        )
                        store.saveOctopusConfig(config)

                        // Direkt mit dem gespeicherten Token testen. Falls einzelne
                        // Tarif-/Messfelder im Kraken-Schema variieren, bleibt der Token
                        // trotzdem gespeichert und kann von der Haupt-App verwendet werden.
                        val test = runCatching { OctopusApi(config).load() }.getOrNull()
                        activity?.runOnUiThread {
                            password = ""
                            busy = false
                            success = true
                            status = if (test?.source == "Octopus Energy API") {
                                "Verbunden. Refresh Token wurde verschlüsselt gespeichert."
                            } else {
                                "Token wurde erfolgreich erzeugt und verschlüsselt gespeichert. Öffne jetzt die Haupt-App und den Tab Octopus."
                            }
                        }
                    } catch (e: Exception) {
                        activity?.runOnUiThread {
                            password = ""
                            busy = false
                            success = false
                            status = "Anmeldung fehlgeschlagen: ${e.message?.take(180) ?: "unbekannter Fehler"}"
                        }
                    }
                }.start()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && accountNumber.isNotBlank() && email.isNotBlank() && password.isNotBlank()
        ) {
            Text("Einmalig anmelden und Token speichern")
        }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (status.isNotBlank()) {
            Text(status)
        }

        if (success) {
            Text(
                "Dein Octopus-Passwort ist nicht gespeichert. In PVCompact liegt nur der verschlüsselte Refresh Token."
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { activity?.finish() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Zurück")
        }
    }
}

private object OctopusTokenExchange {
    private const val ENDPOINT = "https://api.oeg-kraken.energy/v1/graphql/"

    fun obtainRefreshToken(email: String, password: String): String {
        require(email.isNotBlank()) { "E-Mail fehlt" }
        require(password.isNotBlank()) { "Passwort fehlt" }

        val query = """
            mutation ObtainKrakenToken(\$email: String!, \$password: String!) {
              obtainKrakenToken(input: {email: \$email, password: \$password}) {
                token
                refreshToken
                refreshExpiresIn
              }
            }
        """.trimIndent()

        val payload = JSONObject()
            .put("query", query)
            .put(
                "variables",
                JSONObject()
                    .put("email", email)
                    .put("password", password)
            )

        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "PVCompact/0.3 Android")

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(payload.toString())
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${body.take(160)}")
            }

            val root = JSONObject(body)
            val errors = root.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val message = errors.optJSONObject(0)?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Kraken hat die Anmeldung abgelehnt"
                throw IllegalStateException(message)
            }

            val auth = root.optJSONObject("data")?.optJSONObject("obtainKrakenToken")
                ?: throw IllegalStateException("Keine Token-Antwort erhalten")
            val refreshToken = auth.optString("refreshToken")
            if (refreshToken.isBlank()) {
                throw IllegalStateException("Kein Refresh Token erhalten")
            }
            return refreshToken
        } finally {
            connection.disconnect()
        }
    }
}
