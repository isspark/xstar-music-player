package life.xstar.music.player.data.nas

import android.content.Context
import androidx.work.*
import life.xstar.music.player.XstarApplication
import java.util.concurrent.TimeUnit

object NasSyncScheduler {
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<NasSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("nas-sync-recovery", ExistingWorkPolicy.KEEP, request)
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("nas-sync-recovery") }
}

class NasSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as XstarApplication
        val repository = app.nasRepository
        if (repository.status.value.busy) return Result.retry()
        val task = app.database.nasSyncDao().task(repository.config.value.id) ?: return Result.success()
        if (task.state !in listOf("running", "waiting")) return Result.success()
        repository.sync(automatic = true)
        val latest = app.database.nasSyncDao().task(repository.config.value.id)
        return if (latest?.state in listOf("running", "waiting")) Result.retry() else Result.success()
    }
}
