package com.rishi.cascade.trigger

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The bare minimum device admin, present only so the Lock the Screen action has something to call.
 * It requests no policies beyond force-lock, so enabling it cannot wipe or restrict anything.
 */
class LockAdmin : DeviceAdminReceiver() {

    companion object {
        fun component(c: Context) = ComponentName(c, LockAdmin::class.java)

        fun isActive(c: Context): Boolean = try {
            c.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(component(c)) == true
        } catch (e: Exception) { false }

        /** The system screen that asks the user to turn this on. */
        fun enableIntent(c: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(c))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Cascade only uses this to lock the screen when a flow asks it to. " +
                        "It requests no other admin powers."
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun disable(c: Context) {
            try {
                c.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(component(c))
            } catch (e: Exception) { }
        }
    }
}
