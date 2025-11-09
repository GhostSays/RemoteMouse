package com.example.remotemouseclient

import android.app.Activity
import android.app.Application
import android.os.Bundle

class MyApplicattion : Application() {
    // variáveis para manter controle de activities
    private var activityReferences = 0
    private var isActivityChangingConfigurations = false

    override fun onCreate() {
        super.onCreate()

        // callback, usado para manter controle de activities
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                // nova activity iniciada
                activityReferences++
            }

            override fun onActivityStopped(activity: Activity) {
                // activity parada
                isActivityChangingConfigurations = activity.isChangingConfigurations
                activityReferences--
                // fechar sockets se não houver nenhuma activity
                if (activityReferences == 0 && !isActivityChangingConfigurations) {
                    SocketManager.closeAllSockets()
                }
            }

            // callbacks padrão
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
