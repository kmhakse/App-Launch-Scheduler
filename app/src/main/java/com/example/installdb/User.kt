package com.example.installdb
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_activities")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val appName: String,
    val timeSlot: String,
    val title: String,
    val duration: Long
)
