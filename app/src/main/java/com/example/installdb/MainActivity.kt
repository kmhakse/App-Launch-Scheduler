package com.example.installdb

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import android.app.TimePickerDialog
import android.content.Context
import java.util.Calendar
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.remember

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.installdb.AppListScreen

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.util.*
import android.provider.Settings
import android.app.AlarmManager
import android.app.PendingIntent

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavController
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

import com.example.installdb.R
import com.example.installdb.User
import com.example.installdb.UserDao
import com.example.installdb.UserDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

data class ScheduledActivity(
    val appName: String,
    val timeSlot: String,
    val title: String,
    val time: String,
)

val scheduledActivities = mutableStateOf(mutableMapOf<Pair<String, String>, String>())

fun getInstalledApps(context: Context): List<Pair<String, String>> {
    val pm: PackageManager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null)
    intent.addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveInfoList = pm.queryIntentActivities(intent, 0)
    return resolveInfoList.map { resolveInfo ->
        val packageName = resolveInfo.activityInfo.packageName
        val appName = pm.getApplicationLabel(resolveInfo.activityInfo.applicationInfo).toString()
        Pair(appName, packageName)
    }.sortedBy { it.first } // Sort the list by appName
}


fun showTimePicker(context: Context, appName: String, timeSlot: String) {
    val calendar = Calendar.getInstance()
    val hour = calendar[Calendar.HOUR_OF_DAY]
    val minute = calendar[Calendar.MINUTE]

    // Check if there's already a scheduled time for this app and time slot
    val currentScheduledTime = scheduledActivities.value[Pair(appName, timeSlot)]

    TimePickerDialog(context, { _, selectedHour: Int, selectedMinute: Int ->
        val timeValue = "$selectedHour:$selectedMinute"

        // Update the mutable state map
        scheduledActivities.value = scheduledActivities.value.toMutableMap().apply {
            this[Pair(appName, timeSlot)] = timeValue
        }

        // Schedule or reschedule the app launch
        scheduleAppLaunch(context, appName, timeSlot, selectedHour, selectedMinute, currentScheduledTime)

        // Update or reschedule the database entry
        val userDatabase = UserDatabase.getInstance(context)
        val userDao = userDatabase.userDao()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val user = User(appName = appName, timeSlot = timeSlot, title = appName, duration = selectedHour.toLong() * 3600 + selectedMinute)
                if (currentScheduledTime != null) {
                    // Update existing record
                    userDao.update(user)
                    Log.d("DatabaseUpdate", "Successfully updated: $user")
                } else {
                    // Insert new record
                    userDao.insert(user)
                    Log.d("DatabaseInsertion", "Successfully inserted: $user")
                }
            } catch (e: Exception) {
                Log.e("DatabaseOperation", "Error operating database", e)
            }
        }

    }, hour, minute, true).show()
}

fun scheduleAppLaunch(
    context: Context,
    appName: String,
    timeSlot: String,
    targetHour: Int,
    targetMinute: Int,
    currentScheduledTime: String?
) {
    val pm: PackageManager = context.packageManager
    val installedApps = getInstalledApps(context)
    val packageName = installedApps.find { it.first == appName }?.second

    if (packageName == null) {
        Log.d("AppListScreen", "Package name not found for app: $appName")
        return
    }

    val workManager = WorkManager.getInstance(context)

    // Cancel the previous work request if exists
    if (currentScheduledTime != null) {
        workManager.cancelAllWorkByTag("$appName-$timeSlot")
    }

    val currentTimeMillis = System.currentTimeMillis()
    val calendar = Calendar.getInstance().apply {
        timeInMillis = currentTimeMillis
        set(Calendar.HOUR_OF_DAY, targetHour)
        set(Calendar.MINUTE, targetMinute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    // If the target time is before the current time, schedule for the next day
    if (calendar.timeInMillis <= currentTimeMillis) {
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }

    val delayInMillis = calendar.timeInMillis - currentTimeMillis

    val workRequest = OneTimeWorkRequestBuilder<LaunchAppWorker>()
        .setInitialDelay(delayInMillis, TimeUnit.MILLISECONDS)
        .setInputData(workDataOf("packageName" to packageName, "appName" to appName, "timeSlot" to timeSlot))
        .addTag("$appName-$timeSlot")
        .build()

    workManager.enqueue(workRequest)

    val scheduledTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(calendar.timeInMillis))

    // Update the mutable state map
    scheduledActivities.value = scheduledActivities.value.toMutableMap().apply {
        this[Pair(appName, timeSlot)] = scheduledTime
    }

    Log.d("AppListScreen", "WorkManager scheduled for $appName ($packageName) at $scheduledTime")
}

@Composable
fun InstalledAppsListWithTimeSlots(
    apps: List<Pair<String, String>>,
    timeSlots: List<String>,
    onAppClick: (String) -> Unit
) {
    val context = LocalContext.current
    val typography = MaterialTheme.typography
    val currentScheduledActivities by scheduledActivities

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF3A09A0)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "SCHEDULE ALARMS FOR INSTALLED APPS",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF0EEF3),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth() // Ensure the text occupies full width
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(apps) { (appName, packageName) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp) // Reduced vertical padding
                        .clip(MaterialTheme.shapes.medium),
                    elevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White) // Set background color to white
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = appName,
                            style = typography.h5.copy(fontSize = 20.sp),
                            color = Color(0xFF6200EE),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            modifier = Modifier
                                .clickable {
                                    onAppClick(packageName)
                                }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            timeSlots.forEachIndexed { index, timeSlot ->
                                val scheduledTime = currentScheduledActivities[Pair(appName, timeSlot)]
                                val alarmText = "Alarm ${index + 1}"
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            showTimePicker(context, appName, timeSlot)
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.clock1),
                                        contentDescription = null,
                                        tint = Color(0xFF3F51B5),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = scheduledTime ?: alarmText,
                                        fontSize = 16.sp,
                                        color = Color(0xFF3F51B5)
                                    )
                                }
                            }
                        }
                    }
                }
                Divider(color = Color(0xFF3F51B5).copy(alpha = 0.2f), thickness = 1.dp)
            }
        }
    }
}

@Composable
fun AppListScreen(navController: NavController) {
    val context = LocalContext.current
    val installedApps = getInstalledApps(context)
    val timeSlots = generateTimeSlots(3)

    InstalledAppsListWithTimeSlots(installedApps, timeSlots,
        onAppClick = { packageName ->
            openApp(context, packageName)
        }
    )
}

fun generateTimeSlots(count: Int): List<String> {
    return (1..count).map { "Time Slot $it" }
}

fun openApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        Log.d("AppLaunch", "Intent is null for package: $packageName")
    } else {
        try {
            Log.d("AppLaunch", "Starting activity for package: $packageName")
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppLaunch", "Error launching app for package: $packageName", e)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    MaterialTheme {
        val navController = rememberNavController()
        NavHost(navController, startDestination = "appList") {
            composable("appList") {
                MaterialTheme {
                    AppListScreen(navController)
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var userDao: UserDao
    private lateinit var userDatabase: UserDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val navController = rememberNavController()
            NavHost(navController, startDestination = "appList") {
                composable("appList") {
                    MaterialTheme {
                        AppListScreen(navController)
                    }
                }
            }
        }

        userDatabase = UserDatabase.getInstance(this)
        userDao = userDatabase.userDao()
    }
}
