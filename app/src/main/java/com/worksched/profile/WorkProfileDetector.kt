package com.worksched.profile

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log

class WorkProfileDetector(private val context: Context) {
    private val userManager: UserManager
        get() = context.getSystemService(Context.USER_SERVICE) as UserManager

    fun workProfileHandle(): UserHandle? {
        val me = Process.myUserHandle()
        return userManager.userProfiles.firstOrNull { it != me }
    }

    fun isWorkProfilePresent(): Boolean = workProfileHandle() != null

    fun workProfileSerial(): Long? {
        val handle = workProfileHandle() ?: return null
        return try {
            userManager.getSerialNumberForUser(handle).takeIf { it >= 0L }.also {
                if (it == null) Log.w("WorkProfileDetector", "Work profile has no accessible serial number")
            }
        } catch (e: SecurityException) {
            Log.w("WorkProfileDetector", "Android denied the work-profile serial query", e)
            null
        }
    }

    fun isWorkProfileQuiet(): Boolean? {
        val handle = workProfileHandle() ?: return null
        return try {
            userManager.isQuietModeEnabled(handle)
        } catch (e: SecurityException) {
            Log.w("WorkProfileDetector", "Android denied the quiet-mode query", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w("WorkProfileDetector", "Work profile changed during quiet-mode query", e)
            null
        }
    }

    fun isWorkProfileStorageUnlocked(): Boolean? {
        val handle = workProfileHandle() ?: return null
        return try {
            userManager.isUserUnlocked(handle)
        } catch (e: SecurityException) {
            Log.w("WorkProfileDetector", "Android denied the work-profile storage-unlock query", e)
            null
        }
    }
}
