package io.github.libreroute.admin

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.libreroute.util.Logx
import java.util.concurrent.TimeUnit

class RouteSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME = "libreroute_route_sync_worker"
        private const val TAG = "RouteSyncWorker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<RouteSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Logx.i(TAG, "Scheduled periodic route sync (every 12h)")
        }
    }

    override suspend fun doWork(): Result {
        Logx.i(TAG, "Running periodic route sync check")
        val repo = AdminRepository.getInstance(applicationContext)
        if (!repo.isEnrolledInRouteSync()) {
            Logx.d(TAG, "Device not enrolled in route sync; skipping")
            return Result.success()
        }

        return when (val res = repo.syncRoutes(force = true)) {
            is RouteSyncResult.Unchanged -> {
                Logx.i(TAG, "Routes unchanged (rev=${res.revision})")
                Result.success()
            }
            is RouteSyncResult.Updated -> {
                Logx.i(TAG, "Routes updated to rev=${res.newRevision}, count=${res.routes.size}")
                Result.success()
            }
            is RouteSyncResult.Revoked -> {
                Logx.w(TAG, "Routes revoked by server! Active tunnels stopped.")
                Result.success()
            }
            is RouteSyncResult.Failed -> {
                Logx.e(TAG, "Route sync failed: ${res.error}")
                Result.retry()
            }
        }
    }
}
