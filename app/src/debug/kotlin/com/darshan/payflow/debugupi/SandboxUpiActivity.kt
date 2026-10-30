package com.darshan.payflow.debugupi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.darshan.payflow.R
import com.darshan.payflow.core.Money
import com.darshan.payflow.data.qr.QrParseResult
import com.darshan.payflow.data.qr.UpiQrPayload
import com.darshan.payflow.data.qr.UpiQrPayloadParser
import com.darshan.payflow.ui.theme.PayFlowTheme
import java.util.Locale
import kotlin.random.Random

/**
 * Debug-only fake UPI app ("Sandbox UPI (debug)"). Receives `upi://pay`, shows what it was asked to pay and returns the
 * standard UPI `response` extra (`txnId=…&responseCode=…&Status=…&txnRef=<echoed tr>&ApprovalRefNo=…`) so PayFlow's
 * UPI-apps path can be driven end to end on an emulator: success, failure, pending, cancel, and a tampered `txnRef`.
 */
class SandboxUpiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val payload = (intent?.dataString?.let(UpiQrPayloadParser::parse) as? QrParseResult.Valid)?.payload
        setContent { PayFlowTheme { SandboxUpiScreen(payload, ::finishWith) } }
    }

    private fun finishWith(answer: Answer) {
        val tr = (intent?.dataString?.let(UpiQrPayloadParser::parse) as? QrParseResult.Valid)?.payload?.txnRef.orEmpty()
        val response = when (answer) {
            Answer.CANCEL -> null
            Answer.SUCCESS -> responseFor("SUCCESS", "00", tr)
            Answer.FAILURE -> responseFor("FAILURE", "ZA", tr)
            Answer.PENDING -> responseFor("SUBMITTED", "BT", tr)
            Answer.TAMPER -> responseFor("SUCCESS", "00", "PFTAMPERED" + tr.takeLast(6))
        }
        if (response == null) setResult(Activity.RESULT_CANCELED) else setResult(Activity.RESULT_OK, Intent().putExtra("response", response))
        finish()
    }

    private fun responseFor(status: String, code: String, txnRef: String): String {
        val txnId = "SBX" + Random.nextLong(1_000_000_000L, 9_999_999_999L)
        val approval = String.format(Locale.ROOT, "%06d", Random.nextInt(0, 1_000_000))
        return "txnId=$txnId&responseCode=$code&Status=$status&txnRef=$txnRef&ApprovalRefNo=$approval"
    }

    enum class Answer { SUCCESS, FAILURE, PENDING, CANCEL, TAMPER }
}

@androidx.compose.runtime.Composable
private fun SandboxUpiScreen(payload: UpiQrPayload?, onAnswer: (SandboxUpiActivity.Answer) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.sandbox_upi_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.sandbox_upi_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (payload == null) {
                Text(stringResource(R.string.sandbox_upi_invalid), color = MaterialTheme.colorScheme.error)
            } else {
                Text(stringResource(R.string.sandbox_upi_to), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Text(payload.payeeName, style = MaterialTheme.typography.titleLarge)
                Text(payload.payeeVpa, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(payload.amountPaise?.let(Money::formatInr) ?: stringResource(R.string.sandbox_upi_no_amount), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.sandbox_upi_ref, payload.txnRef ?: "-"), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onAnswer(SandboxUpiActivity.Answer.SUCCESS) }, enabled = payload != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.sandbox_upi_success))
            }
            OutlinedButton(onClick = { onAnswer(SandboxUpiActivity.Answer.FAILURE) }, enabled = payload != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sandbox_upi_failure)) }
            OutlinedButton(onClick = { onAnswer(SandboxUpiActivity.Answer.PENDING) }, enabled = payload != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sandbox_upi_pending)) }
            OutlinedButton(onClick = { onAnswer(SandboxUpiActivity.Answer.TAMPER) }, enabled = payload != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sandbox_upi_tamper)) }
            OutlinedButton(onClick = { onAnswer(SandboxUpiActivity.Answer.CANCEL) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sandbox_upi_cancel)) }
        }
    }
}
