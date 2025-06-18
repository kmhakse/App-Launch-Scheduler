package com.example.installdb

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class LaunchAppWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val packageName = inputData.getString("packageName")
        val appName = inputData.getString("appName")
        val timeSlot = inputData.getString("timeSlot")

        if (packageName != null) {
            val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                applicationContext.startActivity(launchIntent)

                // Clear the scheduled time from the map
                scheduledActivities.value = scheduledActivities.value.toMutableMap().apply {
                    remove(Pair(appName!!, timeSlot!!))
                }

                Log.d("LaunchAppWorker", "App launched: $packageName")
                return Result.success()
            } else {
                Log.e("LaunchAppWorker", "Unable to launch app: $packageName")
            }
        } else {
            Log.e("LaunchAppWorker", "Package name is null")
        }
        return Result.failure()
    }
}
