package id.kaptenmeong.sehat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Jembatan ke Health Connect: membaca total langkah harian
 * (termasuk dari jam pintar / Google Fit / Samsung Health yang tersambung).
 * Dipanggil dari JavaScript sebagai window.AndroidHealth.
 */
class HealthBridge(private val act: Activity, private val web: WebView) {

    companion object {
        const val REQ_HC = 20
        private const val PROVIDER = "com.google.android.apps.healthdata"
    }

    private val scope = MainScope()
    private val perms = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    @JavascriptInterface
    fun status(): String = try {
        when (HealthConnectClient.getSdkStatus(act)) {
            HealthConnectClient.SDK_AVAILABLE -> "ok"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
            else -> "none"
        }
    } catch (e: Throwable) {
        "none"
    }

    @JavascriptInterface
    fun connect() {
        act.runOnUiThread {
            try {
                val contract = PermissionController.createRequestPermissionResultContract()
                act.startActivityForResult(contract.createIntent(act, perms), REQ_HC)
            } catch (e: Throwable) {
                openStore()
            }
        }
    }

    @JavascriptInterface
    fun openStore() {
        act.runOnUiThread {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER")))
            } catch (e: Throwable) {
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$PROVIDER")))
                } catch (ignored: Throwable) { }
            }
        }
    }

    @JavascriptInterface
    fun refresh(days: Int) {
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(act)
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(perms)) {
                    push("{\"ok\":false,\"reason\":\"perm\"}")
                    return@launch
                }
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val out = JSONObject()
                val n = days.coerceIn(1, 31)
                for (i in 0 until n) {
                    val d = today.minusDays(i.toLong())
                    val start = d.atStartOfDay(zone).toInstant()
                    val end = d.plusDays(1).atStartOfDay(zone).toInstant()
                    val res = client.aggregate(
                        AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end))
                    )
                    out.put(d.toString(), res[StepsRecord.COUNT_TOTAL] ?: 0L)
                }
                push(JSONObject().put("ok", true).put("days", out).toString())
            } catch (e: Throwable) {
                push(JSONObject().put("ok", false).put("reason", "error").put("err", e.message ?: "").toString())
            }
        }
    }

    private fun push(json: String) {
        web.post { web.evaluateJavascript("window.__health && window.__health($json)", null) }
    }
}
